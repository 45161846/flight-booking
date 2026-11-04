INSERT INTO airlines(code, name) VALUES ('SU', 'Aeroflot');
INSERT INTO flights(id, flight_number, airline_code, origin, destination, departure_time, arrival_time,
                    total_seats, available_seats, price_minor, status)
VALUES ('11111111-1111-1111-1111-111111111111','SU100','SU','SVO','LED',
        ((CURRENT_DATE + 30) + TIME '09:00') AT TIME ZONE 'UTC',
        ((CURRENT_DATE + 30) + TIME '10:30') AT TIME ZONE 'UTC',10,10,500000,'SCHEDULED'),
       ('22222222-2222-2222-2222-222222222222','SU200','SU','SVO','LED',
        ((CURRENT_DATE + 31) + TIME '14:00') AT TIME ZONE 'UTC',
        ((CURRENT_DATE + 31) + TIME '15:30') AT TIME ZONE 'UTC',1,1,700000,'SCHEDULED'),
       ('33333333-3333-3333-3333-333333333333','SU300','SU','LED','SVO',
        ((CURRENT_DATE + 30) + TIME '16:00') AT TIME ZONE 'UTC',
        ((CURRENT_DATE + 30) + TIME '17:30') AT TIME ZONE 'UTC',20,20,450000,'CANCELLED');
