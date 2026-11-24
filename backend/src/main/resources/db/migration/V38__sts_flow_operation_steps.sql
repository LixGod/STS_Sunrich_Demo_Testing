-- Step 8 of the Ship2Ship STS flow: fendering with hose delivery and the tug arrangement are ticked like the other events.
INSERT IGNORE INTO operation_event_type (code, label, phase, is_interval, sort_order, updated_at, version) VALUES
    ('FENDERS_HOSES_DELIVERED', 'Fenders rigged and hoses delivered', 'BERTHING', FALSE, 4, UTC_TIMESTAMP(6), 0),
    ('TUGS_FAST',               'Tugs made fast',                     'BERTHING', FALSE, 15, UTC_TIMESTAMP(6), 0);
