#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Tests use dedicated databases; never point these variables at a production database.
./scripts/podman.sh up -d --build
if [[ -f .env ]]; then set -a; source .env; set +a; fi
for service in flight booking; do
  container="$(podman ps -q --filter "label=com.docker.compose.project=${COMPOSE_PROJECT_NAME:-flight-booking}" --filter "label=com.docker.compose.service=$service-db")"
  database="${service}s_test"
  if [[ "$service" == booking ]]; then database=bookings_test; fi
  for attempt in {1..60}; do
    if podman exec "$container" pg_isready -q -U "$service"; then break; fi
    sleep 1
  done
  exists="$(podman exec "$container" psql -U "$service" -d postgres -Atqc "SELECT 1 FROM pg_database WHERE datname = '$database'")"
  if [[ "$exists" != 1 ]]; then podman exec "$container" createdb -U "$service" "$database"; fi
done
export FLIGHT_TEST_DB_URL="jdbc:postgresql://localhost:${FLIGHT_DB_PORT:-55441}/flights_test"
export FLIGHT_TEST_DB_USER=flight
export FLIGHT_TEST_DB_PASSWORD="${FLIGHT_DB_PASSWORD:-flight-local}"
export BOOKING_TEST_DB_URL="jdbc:postgresql://localhost:${BOOKING_DB_PORT:-55442}/bookings_test"
export BOOKING_TEST_DB_USER=booking
export BOOKING_TEST_DB_PASSWORD="${BOOKING_DB_PASSWORD:-booking-local}"
./gradlew test --rerun-tasks --no-daemon --max-workers=2
BASE_URL="http://localhost:${BOOKING_HTTP_PORT:-8080}" python3 scripts/smoke.py
