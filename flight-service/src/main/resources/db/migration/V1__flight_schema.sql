CREATE TABLE airlines (
    code varchar(3) PRIMARY KEY CHECK (code ~ '^[A-Z0-9]{2,3}$'),
    name text NOT NULL CHECK (length(name) > 0)
);
CREATE TABLE flights (
    id uuid PRIMARY KEY,
    flight_number varchar(10) NOT NULL CHECK (length(flight_number) > 0),
    airline_code varchar(3) NOT NULL REFERENCES airlines(code),
    origin char(3) NOT NULL CHECK (origin ~ '^[A-Z]{3}$'),
    destination char(3) NOT NULL CHECK (destination ~ '^[A-Z]{3}$'),
    departure_time timestamptz NOT NULL,
    departure_date date GENERATED ALWAYS AS ((departure_time AT TIME ZONE 'UTC')::date) STORED,
    arrival_time timestamptz NOT NULL,
    total_seats integer NOT NULL CHECK (total_seats > 0),
    available_seats integer NOT NULL CHECK (available_seats >= 0 AND available_seats <= total_seats),
    price_minor bigint NOT NULL CHECK (price_minor > 0),
    status varchar(20) NOT NULL CHECK (status IN ('SCHEDULED','DEPARTED','CANCELLED','COMPLETED')),
    UNIQUE (flight_number, departure_date),
    CHECK (origin <> destination),
    CHECK (arrival_time > departure_time),
    CHECK (departure_time >= '0001-01-01 00:00:00+00' AND arrival_time < '10000-01-01 00:00:00+00')
);
CREATE INDEX flights_search_idx ON flights(origin, destination, departure_date) WHERE status = 'SCHEDULED';
-- Durable command keys serialize reserve/release, even when release arrives before reserve.
CREATE TABLE reservation_commands (
    booking_id uuid PRIMARY KEY,
    released boolean NOT NULL DEFAULT false
);
CREATE TABLE seat_reservations (
    id uuid PRIMARY KEY,
    flight_id uuid NOT NULL REFERENCES flights(id),
    booking_id uuid NOT NULL UNIQUE REFERENCES reservation_commands(booking_id),
    seat_count integer NOT NULL CHECK (seat_count > 0),
    unit_price_minor bigint NOT NULL CHECK (unit_price_minor > 0),
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE','RELEASED','EXPIRED'))
);
CREATE INDEX reservations_flight_idx ON seat_reservations(flight_id);
-- Revision is part of every cache key. An old in-flight fill cannot revive old data.
CREATE TABLE cache_revision (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
INSERT INTO cache_revision(singleton, revision) VALUES (true, 0);
