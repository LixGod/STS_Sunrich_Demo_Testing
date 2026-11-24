ALTER TABLE role ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE role SET name = 'Admin' WHERE code = 'SYSTEM_ADMIN';

INSERT INTO permission (code, module, description)
VALUES ('ADMIN.ROLE.WRITE', 'ADMIN', 'Create roles and change their permissions');

INSERT INTO role_permission (role_id, permission_code)
SELECT id, 'ADMIN.ROLE.WRITE' FROM role WHERE code = 'SYSTEM_ADMIN';
