-- SRS D06: supported actuals, commitments and a versioned, independently approved FDA.
INSERT INTO permission(code,module,description) VALUES
 ('FIN.COST.READ','FINANCE','View job budgets, commitments and actual costs within scope'),
 ('FIN.COST.WRITE','FINANCE','Prepare job purchase orders and vendor bills within scope'),
 ('FIN.COST.APPROVE','FINANCE','Approve another person''s costs within scope'),
 ('FIN.FDA.READ','FINANCE','View final disbursement accounts within scope'),
 ('FIN.FDA.WRITE','FINANCE','Prepare and issue final disbursement accounts within scope'),
 ('FIN.FDA.APPROVE','FINANCE','Approve another person''s final disbursement account within scope'),
 ('FIN.PROFIT.READ','FINANCE','View job income, cost and margin across jobs within scope');
INSERT INTO role_permission(role_id,permission_code)
 SELECT r.id,p.code FROM role r JOIN permission p ON p.code IN ('FIN.COST.READ','FIN.COST.WRITE','FIN.COST.APPROVE','FIN.FDA.READ','FIN.FDA.WRITE','FIN.FDA.APPROVE')
 WHERE r.code='FINANCE';
INSERT INTO role_permission(role_id,permission_code)
 SELECT r.id,'FIN.PROFIT.READ' FROM role r WHERE r.code IN ('FINANCE','SYSTEM_ADMIN','MANAGEMENT_APPROVER','AUDITOR');
INSERT INTO role_permission(role_id,permission_code)
 SELECT r.id,p.code FROM role r JOIN permission p ON p.code IN ('FIN.COST.READ','FIN.FDA.READ')
 WHERE r.code IN ('SYSTEM_ADMIN','COMMERCIAL','MANAGEMENT_APPROVER','AUDITOR');
INSERT INTO role_permission(role_id,permission_code)
 SELECT r.id,p.code FROM role r JOIN permission p ON p.code IN ('FIN.COST.APPROVE','FIN.FDA.APPROVE') WHERE r.code='MANAGEMENT_APPROVER';

CREATE TABLE job_cost_document (
 id BINARY(16) NOT NULL PRIMARY KEY,job_id BINARY(16) NOT NULL,vendor_id BINARY(16) NOT NULL,
 kind VARCHAR(10) NOT NULL,reference VARCHAR(100) NOT NULL,number VARCHAR(60) NULL COMMENT 'PO number, assigned at approval',document_date DATE NOT NULL,due_date DATE NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',purchase_order_id BINARY(16) NULL,file_id BINARY(16) NULL,
 currency CHAR(3) NOT NULL,base_currency CHAR(3) NOT NULL,job_currency CHAR(3) NOT NULL,
 base_fx_id BINARY(16) NULL,job_fx_id BINARY(16) NULL,base_rate DECIMAL(19,8) NOT NULL,job_rate DECIMAL(19,8) NOT NULL,
 base_rate_date DATE NULL,base_rate_source VARCHAR(100) NULL,job_rate_date DATE NULL,job_rate_source VARCHAR(100) NULL,
 net_amount DECIMAL(19,4) NOT NULL,tax_amount DECIMAL(19,4) NOT NULL,gross_amount DECIMAL(19,4) NOT NULL,
 base_amount DECIMAL(19,4) NOT NULL,job_amount DECIMAL(19,4) NOT NULL,notes VARCHAR(2000) NULL,
 submitted_by BINARY(16) NULL,submitted_at DATETIME(6) NULL,approved_by BINARY(16) NULL,approved_at DATETIME(6) NULL,
 decision_reason VARCHAR(500) NULL,reversed_by BINARY(16) NULL,reversed_at DATETIME(6) NULL,closed_by BINARY(16) NULL,closed_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL,created_by BINARY(16) NOT NULL,updated_at DATETIME(6) NOT NULL,version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_cost_vendor_reference(vendor_id,kind,reference,job_id), UNIQUE KEY uk_cost_number(number), KEY ix_cost_job(job_id,status),
 KEY ix_cost_kind(kind,status,document_date),
 FOREIGN KEY(job_id) REFERENCES sts_job(id) ON DELETE RESTRICT,
 FOREIGN KEY(vendor_id) REFERENCES resource_vendor(id) ON DELETE RESTRICT,
 FOREIGN KEY(purchase_order_id) REFERENCES job_cost_document(id) ON DELETE RESTRICT,
 FOREIGN KEY(file_id) REFERENCES stored_file(id) ON DELETE RESTRICT,
 FOREIGN KEY(base_fx_id) REFERENCES fx_rate(id) ON DELETE RESTRICT,
 FOREIGN KEY(job_fx_id) REFERENCES fx_rate(id) ON DELETE RESTRICT,
 CHECK(kind IN ('PO','BILL')),CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','CLOSED','REVERSED')),CHECK(status<>'CLOSED' OR kind='PO'),
 CHECK(approved_by IS NULL OR submitted_by<>approved_by),CHECK(net_amount>=0 AND tax_amount>=0)
) ENGINE=InnoDB;
CREATE TABLE job_cost_line (
 id BINARY(16) NOT NULL PRIMARY KEY,document_id BINARY(16) NOT NULL,line_no INT NOT NULL,
 cost_head_code VARCHAR(20) NOT NULL,cost_head_name VARCHAR(100) NOT NULL,description VARCHAR(300) NOT NULL,quantity DECIMAL(18,3) NOT NULL,
 unit_price DECIMAL(19,4) NOT NULL,net_amount DECIMAL(19,4) NOT NULL,tax_code_id BINARY(16) NULL,
 tax_rate DECIMAL(7,4) NOT NULL,tax_amount DECIMAL(19,4) NOT NULL,
 UNIQUE KEY uk_cost_line(document_id,line_no), FOREIGN KEY(document_id) REFERENCES job_cost_document(id) ON DELETE RESTRICT,
 FOREIGN KEY(tax_code_id) REFERENCES tax_code(id) ON DELETE RESTRICT,
 CHECK(quantity>0 AND unit_price>=0)
) ENGINE=InnoDB;
CREATE TABLE fda_revision (
 id BINARY(16) NOT NULL PRIMARY KEY,job_id BINARY(16) NOT NULL,revision_no INT NOT NULL,
 number VARCHAR(60) NULL,status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',template_version INT NOT NULL DEFAULT 1,
 content_json MEDIUMTEXT NOT NULL,snapshot_json MEDIUMTEXT NULL,
 submitted_by BINARY(16) NULL,submitted_at DATETIME(6) NULL,approved_by BINARY(16) NULL,approved_at DATETIME(6) NULL,
 issued_by BINARY(16) NULL,issued_at DATETIME(6) NULL,reason VARCHAR(500) NULL,
 pdf MEDIUMBLOB NULL,pdf_sha256 CHAR(64) NULL,created_at DATETIME(6) NOT NULL,created_by BINARY(16) NOT NULL,
 updated_at DATETIME(6) NOT NULL,version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_fda_revision(job_id,revision_no),KEY ix_fda_job(job_id,status),
 FOREIGN KEY(job_id) REFERENCES sts_job(id) ON DELETE RESTRICT,
 CHECK(status IN ('DRAFT','SUBMITTED','APPROVED','ISSUED','SUPERSEDED')),
 CHECK(approved_by IS NULL OR submitted_by<>approved_by)
) ENGINE=InnoDB;
-- Placeholder masks until Ship2Ship confirms them (Phase-5-Proposal §3.7 question 6).
INSERT INTO number_series_format(id,series_type,legal_entity_id,mask) VALUES
 (UUID_TO_BIN('01920000-0000-7000-8000-000000000107'),'FDA',NULL,'FDA/{ENTITY}/{YYYY}/{SEQ:5}'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000000108'),'PURCHASE_ORDER',NULL,'PO/{ENTITY}/{YYYY}/{SEQ:5}');
