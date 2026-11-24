-- Finance approves the Final Disbursement Account (FDA).
INSERT IGNORE INTO role_permission (role_id, permission_code)
SELECT id, 'FIN.FDA.APPROVE' FROM role WHERE code = 'FINANCE';
