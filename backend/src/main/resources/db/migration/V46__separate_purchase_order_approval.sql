-- Finance approves vendor bills; Management approves purchase orders.
INSERT INTO permission(code,module,description) VALUES
 ('FIN.COST.PO.APPROVE','FINANCE','Approve or close purchase orders submitted by another person');

INSERT IGNORE INTO role_permission(role_id,permission_code)
SELECT id,'FIN.COST.PO.APPROVE' FROM role WHERE code='MANAGEMENT_APPROVER';

UPDATE alert_rule SET label='Vendor bill awaiting Finance approval' WHERE code='COST_APPROVAL';
INSERT INTO alert_rule(code,label,permission_code,route,updated_at) VALUES
 ('PO_APPROVAL','Purchase order awaiting Management approval','FIN.COST.PO.APPROVE','finance',UTC_TIMESTAMP(6));
