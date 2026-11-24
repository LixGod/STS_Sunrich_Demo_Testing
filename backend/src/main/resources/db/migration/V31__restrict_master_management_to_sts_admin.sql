-- Setup screens and master changes belong to STS Admin; business roles keep read access for form lookups.
INSERT IGNORE INTO role_permission (role_id, permission_code)
SELECT r.id, p.code
FROM role r
CROSS JOIN permission p
WHERE r.code = 'STS_ADMIN' AND p.code LIKE 'MASTER.%';

DELETE rp
FROM role_permission rp
JOIN role r ON r.id = rp.role_id
JOIN permission p ON p.code = rp.permission_code
WHERE p.code LIKE 'MASTER.%.WRITE' AND r.code <> 'STS_ADMIN';
