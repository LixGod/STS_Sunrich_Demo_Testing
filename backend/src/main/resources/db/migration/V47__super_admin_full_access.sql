-- Super Admin may perform every configured action and assign those actions to other users.
INSERT IGNORE INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r CROSS JOIN permission p WHERE r.code = 'SYSTEM_ADMIN';

UPDATE role SET description = 'Full application access, user permissions and audit.' WHERE code = 'SYSTEM_ADMIN';
