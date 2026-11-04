CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    user_id VARCHAR(100) NOT NULL CHECK (length(trim(user_id)) > 0),
    flight_id UUID NOT NULL,
    passenger_name VARCHAR(200) NOT NULL CHECK (length(trim(passenger_name)) > 0),
    passenger_email VARCHAR(254) NOT NULL CHECK (length(trim(passenger_email)) > 0),
    seat_count INTEGER NOT NULL CHECK (seat_count > 0),
    total_price_minor BIGINT NOT NULL CHECK (total_price_minor > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX bookings_user_created_idx ON bookings(user_id, created_at DESC, id);

-- A committed intent exists before any potentially ambiguous ReserveSeats call.
-- It is removed in the same transaction as successful creation of the booking.
CREATE TABLE reservation_cleanup (
    booking_id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Cancellation is recoverable even if PostgreSQL fails after remote release.
CREATE TABLE cancellation_intents (
    booking_id UUID PRIMARY KEY REFERENCES bookings(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
