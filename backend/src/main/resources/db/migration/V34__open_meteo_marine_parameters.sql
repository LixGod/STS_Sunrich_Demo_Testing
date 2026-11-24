INSERT INTO weather_parameter (code, label, unit, sort_order, updated_at, version) VALUES
    ('WAVE_DIRECTION', 'Wave direction', '°', 25, UTC_TIMESTAMP(6), 0),
    ('SWELL_WAVE_HEIGHT', 'Swell wave height', 'm', 40, UTC_TIMESTAMP(6), 0),
    ('SWELL_WAVE_DIRECTION', 'Swell wave direction', '°', 50, UTC_TIMESTAMP(6), 0),
    ('OCEAN_CURRENT_VELOCITY', 'Ocean current velocity', 'km/h', 60, UTC_TIMESTAMP(6), 0)
ON DUPLICATE KEY UPDATE code = weather_parameter.code;
