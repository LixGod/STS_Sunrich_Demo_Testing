INSERT INTO permission (code, module, description)
VALUES ('OPS.MOVEMENT.CHANGE.APPROVE', 'OPERATIONS', 'Approve or return jetty trip corrections and voids requested by another person');

INSERT INTO role_permission (role_id, permission_code)
SELECT role_id, 'OPS.MOVEMENT.CHANGE.APPROVE' FROM role_permission
WHERE permission_code = 'OPS.MOVEMENT.APPROVE';

INSERT IGNORE INTO role_permission (role_id, permission_code)
SELECT id, 'OPS.MOVEMENT.CHANGE.APPROVE' FROM role WHERE code = 'MANAGEMENT_APPROVER';

INSERT INTO user_permission_override (user_id, permission_code, allowed)
SELECT user_id, 'OPS.MOVEMENT.CHANGE.APPROVE', allowed FROM user_permission_override
WHERE permission_code = 'OPS.MOVEMENT.APPROVE';
