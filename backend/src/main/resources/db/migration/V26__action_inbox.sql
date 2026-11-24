-- SRS FR-WF-01 and screen catalogue: persistent, scoped actions and configured lead/escalation times.
CREATE TABLE alert_rule (
 code VARCHAR(40) NOT NULL PRIMARY KEY,label VARCHAR(150) NOT NULL,permission_code VARCHAR(80) NOT NULL,
 route VARCHAR(40) NOT NULL,lead_minutes INT NULL,escalation_minutes INT NULL,enabled BOOLEAN NOT NULL DEFAULT TRUE,
 updated_at DATETIME(6) NOT NULL,updated_by BINARY(16) NULL,version BIGINT NOT NULL DEFAULT 0,
 CHECK(lead_minutes IS NULL OR lead_minutes>=0),CHECK(escalation_minutes IS NULL OR escalation_minutes>0)
) ENGINE=InnoDB;
INSERT INTO alert_rule(code,label,permission_code,route,updated_at) VALUES
 ('DOCUMENTS','Required vessel documents need review','TECH.JOB.READ','technical',UTC_TIMESTAMP(6)),
 ('JPO_APPROVAL','JPO awaiting approval','TECH.JPO.APPROVE','jpo',UTC_TIMESTAMP(6)),
 ('MOORING_APPROVAL','Mooring plan awaiting approval','TECH.MOORING.APPROVE','mooring-plan',UTC_TIMESTAMP(6)),
 ('WEATHER_APPROVAL','Weather limits awaiting approval','TECH.WEATHER.APPROVE','weather',UTC_TIMESTAMP(6)),
 ('WEATHER_BREACH','Weather exceeds approved job limits','TECH.WEATHER.READ','weather',UTC_TIMESTAMP(6)),
 ('ETA_CHANGE','Vessel ETA revised; review resource impacts','OPS.LIVE.READ','operations',UTC_TIMESTAMP(6)),
 ('ALLOCATION','Resource allocation exception awaiting decision','OPS.ALLOCATION.APPROVE','resources',UTC_TIMESTAMP(6)),
 ('CERTIFICATE','An allocated resource certificate is expiring','OPS.ALLOCATION.READ','resources',UTC_TIMESTAMP(6)),
 ('CLOCK_DUE','Accepted charging block is approaching expiry','OPS.MOVEMENT.READ','movements',UTC_TIMESTAMP(6)),
 ('OVERRUN','Additional-hours owner notice needs release or delivery','OPS.MOVEMENT.READ','movements',UTC_TIMESTAMP(6)),
 ('INCIDENT','Incident or corrective action remains open','OPS.INCIDENT.READ','operations',UTC_TIMESTAMP(6)),
 ('DELAY','Operational delay remains open','OPS.LIVE.READ','operations',UTC_TIMESTAMP(6)),
 ('RECOVERY','Equipment shortage or damage needs resolution','OPS.RECOVERY.READ','movements',UTC_TIMESTAMP(6)),
 ('REPORT_APPROVAL','Final report awaiting approval','REPORT.FINAL.APPROVE','final-report',UTC_TIMESTAMP(6)),
 ('COST_APPROVAL','Vendor bill or purchase order awaiting approval','FIN.COST.APPROVE','finance',UTC_TIMESTAMP(6)),
 ('INVOICE_APPROVAL','Customer invoice or credit awaiting approval','FIN.INVOICE.APPROVE','invoices',UTC_TIMESTAMP(6)),
 ('FDA_APPROVAL','FDA awaiting approval','FIN.FDA.APPROVE','finance',UTC_TIMESTAMP(6)),
 ('RECEIVABLE','Customer invoice is overdue','FIN.INVOICE.READ','invoices',UTC_TIMESTAMP(6)),
 ('JOB_CHANGE','Job workflow changed','JOB.READ','',UTC_TIMESTAMP(6));

CREATE TABLE job_alert (
 id BINARY(16) NOT NULL PRIMARY KEY,job_id BINARY(16) NOT NULL,rule_code VARCHAR(40) NOT NULL,
 source_key VARCHAR(100) NOT NULL,due_at DATETIME(6) NULL,created_at DATETIME(6) NOT NULL,seen_at DATETIME(6) NOT NULL,
 condition_active BOOLEAN NOT NULL DEFAULT TRUE,state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
 acknowledged_by BINARY(16) NULL,acknowledged_at DATETIME(6) NULL,
 resolved_by BINARY(16) NULL,resolved_at DATETIME(6) NULL,resolution_reason VARCHAR(500) NULL,
 escalated_at DATETIME(6) NULL,version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_alert_source(job_id,rule_code,source_key),KEY ix_alert_inbox(state,created_at,job_id),
 FOREIGN KEY(job_id) REFERENCES sts_job(id) ON DELETE RESTRICT,
 FOREIGN KEY(rule_code) REFERENCES alert_rule(code) ON DELETE RESTRICT,
 CHECK(state IN ('OPEN','ACKNOWLEDGED','RESOLVED'))
) ENGINE=InnoDB;
INSERT INTO permission(code,module,description) VALUES
 ('ALERT.ESCALATION.READ','JOBS','View overdue escalations for jobs within scope');
INSERT INTO role_permission(role_id,permission_code) SELECT id,'ALERT.ESCALATION.READ' FROM role WHERE code='MANAGEMENT_APPROVER';
