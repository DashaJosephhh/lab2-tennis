-- Для демонстрационной базы, которая создаётся при первом обычном запуске.
SELECT * FROM members;
SELECT * FROM courts;
SELECT * FROM bookings;
INSERT INTO members (id, full_name) VALUES (100, 'Новый участник'), (101, 'Ещё один участник');
INSERT INTO bookings VALUES (100, 100, 1, '2026-12-01', 900, 60, 1500.0, FALSE);
SELECT id, total_price FROM bookings WHERE total_price > 1400;
UPDATE bookings SET paid = TRUE, total_price = 1400.0 WHERE id = 100;
SELECT * FROM bookings WHERE id = 100;
EXPLAIN SELECT * FROM bookings WHERE booking_date = '2026-12-01';
DELETE FROM bookings WHERE id = 100;
DELETE FROM members WHERE id >= 100;
CREATE TABLE notes (id INT PRIMARY KEY, text STRING);
INSERT INTO notes VALUES (1, 'Проверка CREATE TABLE');
SELECT * FROM notes;
DROP TABLE notes;
