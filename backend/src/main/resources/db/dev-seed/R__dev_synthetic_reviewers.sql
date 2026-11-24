-- Synthetic operations manager and STS setup administrator.
INSERT IGNORE INTO app_user(id,external_subject_id,full_name,work_email,department,position,status,language,
 mfa_enabled,created_at,updated_at,version) VALUES
 (UUID_TO_BIN('01920000-0000-7000-9000-000000000410'),'dev-sts-admin','Demo STS Admin',
 'sts.admin@demo.invalid','IT','STS Administrator','ACTIVE','en',FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0),
 (UUID_TO_BIN('01920000-0000-7000-9000-000000000408'),'dev-ops-manager','Demo Operations Manager',
 'ops.manager@demo.invalid','Operations','Operations Manager','ACTIVE','en',FALSE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0);
INSERT IGNORE INTO user_role_scope(id,user_id,role_id,branch_id,valid_from,valid_to,created_at) VALUES
 (UUID_TO_BIN('01920000-0000-7000-9000-000000000511'),UUID_TO_BIN('01920000-0000-7000-9000-000000000410'),
 UUID_TO_BIN('01920000-0000-7000-8000-000000000009'),NULL,'2026-01-01',NULL,UTC_TIMESTAMP(6)),
 (UUID_TO_BIN('01920000-0000-7000-9000-000000000508'),UUID_TO_BIN('01920000-0000-7000-9000-000000000408'),
 UUID_TO_BIN('01920000-0000-7000-8000-000000000004'),NULL,'2026-01-01',NULL,UTC_TIMESTAMP(6));
-- The field manager prepares records; the separate Management Approver login reviews them.
DELETE FROM user_role_scope WHERE id=UUID_TO_BIN('01920000-0000-7000-9000-000000000510')
 AND user_id=UUID_TO_BIN('01920000-0000-7000-9000-000000000408');
