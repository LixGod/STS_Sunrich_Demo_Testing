-- STS Admin stores a vessel's GA once; Marine reads it in every selected job.
INSERT IGNORE INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r CROSS JOIN permission p
WHERE r.code = 'STS_ADMIN' AND p.code IN ('DOCUMENT.READ', 'DOCUMENT.WRITE', 'TECH.VESSEL.READ');
