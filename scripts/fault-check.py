#!/usr/bin/env python3
"""Disrupt only this Compose project to verify Sentinel and circuit recovery.

Run on the new demo stack with no concurrent booking traffic. All stopped
containers are restarted in finally. Existing data and volumes are preserved.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent.parent
BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "flight-booking")
FLIGHT_PATH = "/flights/11111111-1111-1111-1111-111111111111"
STOPPED = set()
CONTAINERS = {}
NETWORK_HOSTS = {}


def command(*args, timeout=30):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(args)}\n{result.stderr.strip()}")
    return result.stdout.strip()


def compose_id(service):
    raw = command("podman", "ps", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}",
                  "--filter", f"label=com.docker.compose.service={service}")
    ids = [line.strip() for line in raw.splitlines() if line.strip()]
    if len(ids) != 1:
        raise RuntimeError(f"Expected exactly one running {service} container in this project, got {raw!r}")
    container_id = ids[0]
    details = json.loads(command("podman", "inspect", container_id))[0]
    labels = details.get("Config", {}).get("Labels", {}) or {}
    actual_project = labels.get("io.podman.compose.project") or labels.get("com.docker.compose.project")
    actual_service = labels.get("io.podman.compose.service") or labels.get("com.docker.compose.service")
    if actual_project != PROJECT or (actual_service is not None and actual_service != service):
        raise RuntimeError(f"Refusing to touch {container_id}: labels identify {actual_project!r}/{actual_service!r}")
    hosts = {service, details.get("Name", "").lstrip("/")}
    for network in details.get("NetworkSettings", {}).get("Networks", {}).values():
        hosts.add(network.get("IPAddress", ""))
        hosts.update(network.get("Aliases", []) or [])
    NETWORK_HOSTS[service] = {host for host in hosts if host}
    return container_id


def redis(service, *args, sentinel=False):
    return command("podman", "exec", CONTAINERS[service], "redis-cli", "--raw",
                   "-p", "26379" if sentinel else "6379", *args, timeout=10)


def current_master():
    raw = redis("sentinel-1", "SENTINEL", "get-master-addr-by-name", "flights", sentinel=True)
    lines = raw.splitlines()
    if len(lines) < 2 or lines[1].strip() != "6379":
        raise RuntimeError(f"Unexpected Sentinel master response: {raw!r}")
    host = lines[0].strip()
    matches = [service for service in ("redis-master", "redis-replica") if host in NETWORK_HOSTS[service]]
    if len(matches) != 1:
        raise RuntimeError(f"Sentinel master {host!r} does not identify a Redis container in this project")
    return matches[0]


def get():
    try:
        with urlopen(BASE_URL + FLIGHT_PATH, timeout=15) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, json.loads(error.read())


def require_flight():
    status, body = get()
    if status != 200 or body.get("id") != FLIGHT_PATH.rsplit("/", 1)[1]:
        raise AssertionError(f"Expected seeded flight HTTP 200, got {status}: {body!r}")
    return body


def stop(service):
    # Track before the subprocess: a timeout may still leave the container stopped.
    STOPPED.add(service)
    command("podman", "stop", "--time", "2", CONTAINERS[service])


def start(service):
    command("podman", "start", CONTAINERS[service])
    STOPPED.discard(service)


def wait_until(predicate, timeout, description):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except (RuntimeError, AssertionError, URLError, TimeoutError, OSError) as error:
            last_error = error
        time.sleep(0.5)
    raise AssertionError(f"Timed out after {timeout}s: {description}; last error={last_error}")


def check_cache(service):
    require_flight()
    keys = redis(service, "--scan", "--pattern", "flight:*").splitlines()
    matching = [key for key in keys if "11111111-1111-1111-1111-111111111111" in key]
    if not matching:
        return False
    return any(1 <= int(redis(service, "TTL", key)) <= 300 for key in matching)


def sentinel_failover():
    require_flight()
    require_flight()
    old_master = current_master()
    stop(old_master)
    # Redis is only a cache: reads must work even before election finishes.
    require_flight()
    wait_until(lambda: current_master() != old_master, 30, "Sentinel promotes the replica")
    new_master = current_master()
    role = redis(new_master, "ROLE").splitlines()[0]
    if role != "master":
        raise AssertionError(f"Sentinel selected {new_master}, but its role is {role!r}")
    # Replication may have copied the old cached value. Remove only this demo
    # flight's cache keys so the subsequent request proves an actual refill.
    for key in redis(new_master, "--scan", "--pattern", "flight:*").splitlines():
        if "11111111-1111-1111-1111-111111111111" in key:
            redis(new_master, "DEL", key)
    wait_until(lambda: check_cache(new_master), 15, "cache refills with a finite TTL on the new master")
    start(old_master)
    print(f"PASS Sentinel: stopped {old_master}, promoted {new_master}, REST survived and cache refilled (TTL <= 300s)", flush=True)


def circuit_recovery():
    stop("flight-service")
    deadline = time.monotonic() + 45
    opened = False
    attempts = 0
    while time.monotonic() < deadline and attempts < 15:
        attempts += 1
        status, body = get()
        if status == 503 and body.get("code") == "CIRCUIT_OPEN":
            opened = True
            break
        if status not in (503, 504):
            raise AssertionError(f"Expected a dependency failure while Flight is stopped, got {status}: {body!r}")
    if not opened:
        raise AssertionError(f"Circuit did not open after {attempts} failed requests")
    started = time.monotonic()
    status, body = get()
    elapsed = time.monotonic() - started
    if status != 503 or body.get("code") != "CIRCUIT_OPEN" or elapsed >= 1.0:
        raise AssertionError(f"Open circuit must fail fast: HTTP {status}, elapsed={elapsed:.3f}s, body={body!r}")
    start("flight-service")
    wait_until(lambda: get()[0] == 200, 45, "Flight restart and circuit recovery")
    print(f"PASS Circuit Breaker: OPEN after {attempts} failures, next 503 in {elapsed:.3f}s, recovered to HTTP 200", flush=True)


def restore():
    success = True
    for service in list(STOPPED):
        try:
            start(service)
        except Exception as error:
            success = False
            print(f"RESTORE FAILED {service}: {error}", file=sys.stderr)
    return success


def main():
    success = False
    try:
        for service in ("sentinel-1", "redis-master", "redis-replica", "flight-service"):
            CONTAINERS[service] = compose_id(service)
        sentinel_failover()
        circuit_recovery()
        success = True
    except Exception as error:
        print(f"FAIL {type(error).__name__}: {error}", file=sys.stderr, flush=True)
    finally:
        restored = restore()
        success = restored and success
    print(f"Fault checks result={'PASS' if success else 'FAIL'}; container restoration={'OK' if restored else 'FAILED'}", flush=True)
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
