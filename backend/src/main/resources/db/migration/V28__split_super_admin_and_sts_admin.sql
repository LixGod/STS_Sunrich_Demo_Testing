-- Super Admin controls users, role grants and audits. STS Admin maintains application master data.
UPDATE role
SET name = 'Super Admin',
    description = 'Manages user access and reviews login, logout and audit records.'
WHERE code = 'SYSTEM_ADMIN';

INSERT INTO role (id, code, name, description)
VALUES (UUID_TO_BIN('01920000-0000-7000-8000-000000000009'), 'STS_ADMIN', 'STS Admin',
        'Manages Ship2Ship organization, commercial, finance, technical and resource setup.');

DELETE FROM role_permission
WHERE role_id = (SELECT id FROM role WHERE code = 'SYSTEM_ADMIN')
  AND permission_code NOT IN ('ADMIN.USER.READ', 'ADMIN.USER.WRITE', 'ADMIN.ROLE.READ', 'AUDIT.READ');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code
FROM role r
JOIN permission p ON p.module = 'MASTERS'
WHERE r.code = 'STS_ADMIN';
