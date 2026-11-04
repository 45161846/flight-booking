#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v podman >/dev/null; then
  echo "Install Podman first: https://podman.io" >&2; exit 1
fi
if ! podman info >/dev/null 2>&1; then
  if [[ "$(uname -s)" == Darwin ]]; then
    podman machine start
  else
    echo "Podman is unavailable. Run podman info to diagnose." >&2; exit 1
  fi
fi
if command -v podman-compose >/dev/null; then
  export PODMAN_COMPOSE_PROVIDER="$(command -v podman-compose)"
elif [[ ! -x .local/compose/bin/podman-compose ]]; then
  python3 -m venv .local/compose
  .local/compose/bin/python -m pip install -r scripts/compose-requirements.txt
  export PODMAN_COMPOSE_PROVIDER="$PWD/.local/compose/bin/podman-compose"
else
  export PODMAN_COMPOSE_PROVIDER="$PWD/.local/compose/bin/podman-compose"
fi
if [[ $# -eq 0 ]]; then set -- up --build -d; fi
exec podman compose "$@"
