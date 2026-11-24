-- Reports & History: the job history (management) report. Provisional grants until the owners say who should see it;
-- Super Admin (V28) keeps to user access and audit.
INSERT INTO permission (code, module, description) VALUES
    ('REPORT.MANAGEMENT.READ', 'REPORTS', 'View and export the job history report within scope');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'REPORT.MANAGEMENT.READ' FROM role r WHERE r.code IN ('MANAGEMENT_APPROVER', 'AUDITOR');
