#!/usr/bin/env python3
"""Repeatable HTTP scenarios against a running, seeded Flight Booking stack.

Python standard library only. Override BASE_URL for a nondefault Booking port.
The suite cancels every booking it creates, including on assertion failure.
"""
from concurrent.futures import ThreadPoolExecutor
import json
import os
import sys
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from uuid import uuid4

BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080").rstrip("/")
FLIGHT_ID = "11111111-1111-1111-1111-111111111111"
LAST_SEAT_ID = "22222222-2222-2222-2222-222222222222"
USER_ID = f"smoke-{uuid4()}"
CREATED = []
CREATED_LOCK = threading.Lock()
PASSED = 0


def request(method, path, body=None):
    payload = None if body is None else json.dumps(body).encode("utf-8")
    req = Request(BASE_URL + path, data=payload, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urlopen(req, timeout=20) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except HTTPError as error:
        raw = error.read()
        try:
            result = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            result = raw.decode("utf-8", errors="replace")
        return error.code, result


def expect(status, actual, body):
    if actual != status:
        raise AssertionError(f"Expected HTTP {status}, got {actual}: {body!r}")
    return body


def get(path):
    status, body = request("GET", path)
    return expect(200, status, body)


def booking_body(flight_id=FLIGHT_ID, seats=2):
    return {
        "user_id": USER_ID,
        "flight_id": flight_id,
        "passenger_name": "Smoke Passenger",
        "passenger_email": "smoke@example.com",
        "seat_count": seats,
    }


def create(flight_id=FLIGHT_ID, seats=2):
    status, body = request("POST", "/bookings", booking_body(flight_id, seats))
    if status == 201:
        with CREATED_LOCK:
            CREATED.append(body["id"])
    return status, body


def cancel(booking_id):
    status, body = request("POST", f"/bookings/{booking_id}/cancel")
    return expect(200, status, body)


def search(date=None):
    params = {"origin": "SVO", "destination": "LED"}
    if date is not None:
        params["date"] = date
    return get("/flights?" + urlencode(params))


def find(flights, flight_id):
    matches = [flight for flight in flights if flight["id"] == flight_id]
    if len(matches) != 1:
        raise AssertionError(f"Expected one flight {flight_id}, got {len(matches)}")
    return matches[0]


def passed(name):
    global PASSED
    PASSED += 1
    print(f"PASS {name}", flush=True)


def wait_ready():
    deadline = time.monotonic() + 90
    while True:
        try:
            status, body = request("GET", "/actuator/health")
            if status == 200 and body.get("status") == "UP":
                return
        except (URLError, TimeoutError, OSError):
            pass
        if time.monotonic() >= deadline:
            raise AssertionError(f"Booking Service did not become healthy at {BASE_URL}")
        time.sleep(1)


def run():
    wait_ready()
    flight = get(f"/flights/{FLIGHT_ID}")
    initial_available = flight["available_seats"]
    assert flight["price_minor"] == 500000, "Seed price must be 500000 kopecks"
    assert initial_available >= 2, "Seed flight needs at least two free seats"
    assert flight["status"] == "SCHEDULED"
    date = flight["departure_time"][:10]
    passed("get seeded flight and money units")

    flights = search()
    assert isinstance(flights, list)
    assert all(item["status"] == "SCHEDULED" for item in flights)
    assert find(flights, FLIGHT_ID)["available_seats"] == initial_available
    dated = search(date)
    assert find(dated, FLIGHT_ID)["departure_time"][:10] == date
    assert all(item["departure_time"][:10] == date for item in dated)
    passed("search with and without optional UTC date")

    status, body = request("GET", "/flights/not-a-uuid")
    assert expect(400, status, body)["code"] == "INVALID_ARGUMENT"
    status, body = request("GET", "/flights/00000000-0000-0000-0000-000000000000")
    assert expect(404, status, body)["code"] == "NOT_FOUND"
    status, body = request("GET", "/bookings/not-a-uuid")
    assert expect(400, status, body)["code"] == "INVALID_ARGUMENT"
    status, body = request("GET", "/bookings/00000000-0000-0000-0000-000000000000")
    assert expect(404, status, body)["code"] == "BOOKING_NOT_FOUND"
    passed("invalid identifiers return 400; missing entities return 404")

    status, body = create()
    booking = expect(201, status, body)
    assert booking["status"] == "CONFIRMED"
    assert booking["total_price_minor"] == 1000000
    assert booking["seat_count"] == 2 and booking["user_id"] == USER_ID
    passed("create confirmed booking with a fixed price snapshot")

    assert get(f"/flights/{FLIGHT_ID}")["available_seats"] == initial_available - 2
    assert find(search(date), FLIGHT_ID)["available_seats"] == initial_available - 2
    passed("reserve invalidates flight and search cache")

    saved = get(f"/bookings/{booking['id']}")
    assert saved == booking
    user_bookings = get("/bookings?" + urlencode({"user_id": USER_ID}))
    assert [item["id"] for item in user_bookings] == [booking["id"]]
    passed("get booking and list bookings by user")

    cancelled = cancel(booking["id"])
    assert cancelled["status"] == "CANCELLED"
    assert cancel(booking["id"])["status"] == "CANCELLED"
    assert get(f"/flights/{FLIGHT_ID}")["available_seats"] == initial_available
    assert find(search(date), FLIGHT_ID)["available_seats"] == initial_available
    passed("cancel is idempotent and restores cached availability")

    status, body = create(seats=1000000)
    assert expect(409, status, body)["code"] == "RESOURCE_EXHAUSTED"
    assert get(f"/flights/{FLIGHT_ID}")["available_seats"] == initial_available
    assert len(get("/bookings?" + urlencode({"user_id": USER_ID}))) == 1
    passed("overcapacity returns 409 and creates no booking")

    invalid = booking_body(seats=0)
    status, body = request("POST", "/bookings", invalid)
    assert expect(400, status, body)["code"] == "INVALID_ARGUMENT"
    invalid = booking_body()
    invalid["passenger_email"] = "invalid-email"
    status, body = request("POST", "/bookings", invalid)
    assert expect(400, status, body)["code"] == "INVALID_ARGUMENT"
    status, body = request("GET", "/flights?origin=SVO&destination=LED&date=invalid")
    assert expect(400, status, body)["code"] == "INVALID_ARGUMENT"
    passed("invalid count, email and date return 400")

    last_seat = get(f"/flights/{LAST_SEAT_ID}")
    assert last_seat["available_seats"] == 1, "Race fixture must have exactly one free seat"
    last_date = last_seat["departure_time"][:10]
    assert find(search(last_date), LAST_SEAT_ID)["available_seats"] == 1
    start = threading.Barrier(8)

    def buyer(_):
        start.wait(timeout=10)
        return create(LAST_SEAT_ID, 1)

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(buyer, range(8)))
    winners = [body for status, body in results if status == 201]
    rejected = [(status, body) for status, body in results if status != 201]
    assert len(winners) == 1, f"Expected one winner, received {results!r}"
    assert len(rejected) == 7
    assert all(status == 409 and body["code"] == "RESOURCE_EXHAUSTED" for status, body in rejected), rejected
    assert get(f"/flights/{LAST_SEAT_ID}")["available_seats"] == 0
    assert find(search(last_date), LAST_SEAT_ID)["available_seats"] == 0
    assert cancel(winners[0]["id"])["status"] == "CANCELLED"
    assert get(f"/flights/{LAST_SEAT_ID}")["available_seats"] == 1
    assert find(search(last_date), LAST_SEAT_ID)["available_seats"] == 1
    passed("8 concurrent buyers: exactly 1 booking, 7 capacity conflicts; cancellation restores the last seat")


def cleanup():
    failures = []
    with CREATED_LOCK:
        ids = list(CREATED)
    for booking_id in ids:
        try:
            cancel(booking_id)
        except Exception as error:
            failures.append(f"{booking_id}: {error}")
    if failures:
        print("Cleanup failed; retry POST /bookings/{id}/cancel for:", file=sys.stderr)
        for failure in failures:
            print("  " + failure, file=sys.stderr)
    return not failures


def main():
    success = False
    try:
        run()
        success = True
    except Exception as error:
        print(f"FAIL {type(error).__name__}: {error}", file=sys.stderr, flush=True)
    finally:
        success = cleanup() and success
    print(f"{PASSED} HTTP scenario groups passed; result={'PASS' if success else 'FAIL'}", flush=True)
    return 0 if success else 1


if __name__ == "__main__":
    sys.exit(main())
