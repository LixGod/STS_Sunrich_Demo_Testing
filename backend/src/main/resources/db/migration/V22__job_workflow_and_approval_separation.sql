-- SRS D01: an administrator needs a separately assigned business role to approve business records.
DELETE rp FROM role_permission rp JOIN role r ON r.id=rp.role_id
WHERE r.code='SYSTEM_ADMIN' AND rp.permission_code IN (
 'COMMERCIAL.QUOTATION.APPROVE','TECH.VESSEL.VERIFY','TECH.JOB.REVIEW','TECH.JPO.APPROVE',
 'TECH.MOORING.APPROVE','TECH.WEATHER.APPROVE','OPS.ALLOCATION.APPROVE','OPS.LIVE.APPROVE',
 'OPS.INCIDENT.REVIEW','OPS.MOVEMENT.APPROVE','OPS.RECOVERY.APPROVE','REPORT.FINAL.APPROVE','FIN.INVOICE.APPROVE');

INSERT INTO permission(code,module,description) VALUES
 ('JOB.WRITE','JOBS','Advance jobs and request readiness or closure within scope'),
 ('JOB.READINESS.APPROVE','JOBS','Approve readiness requested by another person within scope'),
 ('JOB.CLOSE.APPROVE','JOBS','Approve closure requested by another person within scope'),
 ('JOB.OVERRIDE','JOBS','Record management overrides, exceptions and reopening within scope');
INSERT INTO role_permission(role_id,permission_code)
 SELECT id,'JOB.WRITE' FROM role WHERE code='OPERATIONS';
INSERT INTO role_permission(role_id,permission_code)
 SELECT id,'JOB.READINESS.APPROVE' FROM role WHERE code='TECHNICAL';
INSERT INTO role_permission(role_id,permission_code)
 SELECT r.id,p.code FROM role r JOIN permission p ON p.code IN ('JOB.CLOSE.APPROVE','JOB.OVERRIDE')
 WHERE r.code='MANAGEMENT_APPROVER';

ALTER TABLE sts_job
 ADD readiness_requested_by BINARY(16) NULL,
 ADD readiness_requested_at DATETIME(6) NULL,
 ADD closure_requested_by BINARY(16) NULL,
 ADD closure_requested_at DATETIME(6) NULL,
 ADD workflow_revision INT NOT NULL DEFAULT 1,
 ADD resume_status VARCHAR(30) NULL;

CREATE TABLE job_transition_rule (
 from_status VARCHAR(30) NOT NULL, to_status VARCHAR(30) NOT NULL,
 permission_code VARCHAR(100) NOT NULL, reason_required BOOLEAN NOT NULL DEFAULT FALSE,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 PRIMARY KEY(from_status,to_status),
 FOREIGN KEY(permission_code) REFERENCES permission(code) ON DELETE RESTRICT
) ENGINE=InnoDB;
INSERT INTO job_transition_rule(from_status,to_status,permission_code,reason_required) VALUES
 ('OPEN','DOCUMENTS_PENDING','JOB.WRITE',FALSE),
 ('DOCUMENTS_PENDING','PLANNING','JOB.WRITE',FALSE),
 ('PLANNING','APPROVED_FOR_READINESS','JOB.READINESS.APPROVE',FALSE),
 ('APPROVED_FOR_READINESS','MOBILISING','JOB.WRITE',FALSE),
 ('MOBILISING','IN_PROGRESS','JOB.WRITE',FALSE),
 ('IN_PROGRESS','DEMOBILISING','JOB.WRITE',FALSE),
 ('DEMOBILISING','REPORT_PENDING','JOB.WRITE',FALSE),
 ('REPORT_PENDING','FINANCIAL_CLOSING','JOB.WRITE',FALSE),
 ('FINANCIAL_CLOSING','CLOSED','JOB.CLOSE.APPROVE',FALSE),
 ('CLOSED','REPORT_PENDING','JOB.OVERRIDE',TRUE);

INSERT INTO job_transition_rule(from_status,to_status,permission_code,reason_required)
SELECT states.status,exceptions.status,'JOB.OVERRIDE',TRUE FROM
 (SELECT 'OPEN' status UNION ALL SELECT 'DOCUMENTS_PENDING' UNION ALL SELECT 'PLANNING'
  UNION ALL SELECT 'APPROVED_FOR_READINESS' UNION ALL SELECT 'MOBILISING' UNION ALL SELECT 'IN_PROGRESS'
  UNION ALL SELECT 'DEMOBILISING' UNION ALL SELECT 'REPORT_PENDING' UNION ALL SELECT 'FINANCIAL_CLOSING') states
CROSS JOIN (SELECT 'ON_HOLD' status UNION ALL SELECT 'SUSPENDED' UNION ALL SELECT 'CANCELLED') exceptions;
