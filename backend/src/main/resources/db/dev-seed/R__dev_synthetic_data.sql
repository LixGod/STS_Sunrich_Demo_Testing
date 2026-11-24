-- LOCAL DEVELOPMENT ONLY (loaded by the "local" profile). Synthetic, clearly fictitious data (SRS D14).
-- Users match the development login stub in application-local.yml.

INSERT IGNORE INTO branch (id, code, name, timezone, address, active, created_at, updated_at, version)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000201'),
        'MUM', 'Demo Mumbai Branch', 'Asia/Kolkata', NULL, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
       (UUID_TO_BIN('01920000-0000-7000-9000-000000000202'),
        'KDL', 'Demo Kandla Branch', 'Asia/Kolkata', NULL, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
       (UUID_TO_BIN('01920000-0000-7000-9000-000000000203'),
        'FUJ', 'Demo Fujairah Branch', 'Asia/Dubai', NULL, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO app_user (id, external_subject_id, full_name, work_email, department, position, status, language,
                             mfa_enabled, created_at, updated_at, version)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000401'), 'dev-admin', 'Demo Admin', 'admin@demo.invalid',
        'IT', 'Admin', 'ACTIVE', 'en', FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
       (UUID_TO_BIN('01920000-0000-7000-9000-000000000402'), 'dev-ops-mumbai', 'Demo Operations (Mumbai)',
        'ops.mumbai@demo.invalid', 'Operations', 'Operations Coordinator', 'ACTIVE', 'en', FALSE, UTC_TIMESTAMP(6),
        UTC_TIMESTAMP(6), 0),
       (UUID_TO_BIN('01920000-0000-7000-9000-000000000403'), 'dev-finance', 'Demo Finance', 'finance@demo.invalid',
        'Finance', 'Finance Executive', 'ACTIVE', 'en', FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO user_role_scope (id, user_id, role_id, branch_id, valid_from, valid_to, created_at)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000501'), UUID_TO_BIN('01920000-0000-7000-9000-000000000401'),
        UUID_TO_BIN('01920000-0000-7000-8000-000000000001'), NULL, '2026-01-01', NULL, UTC_TIMESTAMP(6)),
       (UUID_TO_BIN('01920000-0000-7000-9000-000000000502'), UUID_TO_BIN('01920000-0000-7000-9000-000000000402'),
        UUID_TO_BIN('01920000-0000-7000-8000-000000000004'),
        UUID_TO_BIN('01920000-0000-7000-9000-000000000201'), '2026-01-01', NULL, UTC_TIMESTAMP(6)),
       (UUID_TO_BIN('01920000-0000-7000-9000-000000000503'), UUID_TO_BIN('01920000-0000-7000-9000-000000000403'),
        UUID_TO_BIN('01920000-0000-7000-8000-000000000007'),
        NULL, '2026-01-01', NULL, UTC_TIMESTAMP(6));
