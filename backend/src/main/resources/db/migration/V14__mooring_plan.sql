-- Phase 3d: editable, job-scoped mooring plans. No vessel or marine values are seeded.
CREATE TABLE mooring_plan (
    id BINARY(16) NOT NULL,
    job_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mooring_plan_job (job_id),
    CONSTRAINT fk_mooring_plan_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE mooring_plan_revision (
    id BINARY(16) NOT NULL,
    plan_id BINARY(16) NOT NULL,
    revision_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    model_json MEDIUMTEXT NOT NULL,
    snapshot_json MEDIUMTEXT NULL,
    submitted_by BINARY(16) NULL,
    submitted_at DATETIME(6) NULL,
    decided_by BINARY(16) NULL,
    decided_at DATETIME(6) NULL,
    returned_reason VARCHAR(500) NULL,
    issued_by BINARY(16) NULL,
    issued_at DATETIME(6) NULL,
    pdf MEDIUMBLOB NULL,
    pdf_sha256 CHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mooring_revision_no (plan_id, revision_no),
    KEY ix_mooring_revision_status (plan_id, status),
    CONSTRAINT fk_mooring_revision_plan FOREIGN KEY (plan_id) REFERENCES mooring_plan (id) ON DELETE RESTRICT,
    CONSTRAINT ck_mooring_revision_status CHECK (status IN ('DRAFT','SUBMITTED','APPROVED','ISSUED','SUPERSEDED')),
    CONSTRAINT ck_mooring_second_person CHECK (decided_by IS NULL OR submitted_by IS NULL OR decided_by <> submitted_by)
) ENGINE = InnoDB;

CREATE TABLE mooring_capacity_column (
    code VARCHAR(40) NOT NULL,
    label VARCHAR(80) NOT NULL,
    definition VARCHAR(500) NULL,
    unit VARCHAR(30) NULL,
    evidence_requirement VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT ck_mooring_capacity_status CHECK (status IN ('PENDING_DEFINITION','ACTIVE')),
    CONSTRAINT ck_mooring_capacity_active CHECK (status <> 'ACTIVE' OR (definition IS NOT NULL AND unit IS NOT NULL AND evidence_requirement IS NOT NULL))
) ENGINE = InnoDB;

INSERT INTO mooring_capacity_column (code,label,status,updated_at,version) VALUES
 ('OPS_BHC','Ops BHC','PENDING_DEFINITION',UTC_TIMESTAMP(6),0),
 ('BIGS_MBL','BiGs MBL','PENDING_DEFINITION',UTC_TIMESTAMP(6),0);

CREATE TABLE mooring_plan_rule (
    code VARCHAR(40) NOT NULL,
    tolerance_m DECIMAL(12,3) NULL,
    active BOOLEAN NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (code),
    CONSTRAINT ck_mooring_rule_tolerance CHECK (tolerance_m IS NULL OR tolerance_m >= 0),
    CONSTRAINT ck_mooring_rule_active CHECK (active = FALSE OR tolerance_m IS NOT NULL)
) ENGINE = InnoDB;

INSERT INTO mooring_plan_rule (code,tolerance_m,active,updated_at,version)
VALUES ('MANIFOLD_LENGTH',NULL,FALSE,UTC_TIMESTAMP(6),0);

CREATE TABLE mooring_plan_verified_value (
    revision_id BINARY(16) NOT NULL,
    value_key VARCHAR(160) NOT NULL,
    value_sha256 CHAR(64) NOT NULL,
    verified_by BINARY(16) NOT NULL,
    verified_at DATETIME(6) NOT NULL,
    PRIMARY KEY (revision_id, value_key),
    CONSTRAINT fk_mooring_verified_revision FOREIGN KEY (revision_id) REFERENCES mooring_plan_revision (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

INSERT INTO permission (code, module, description) VALUES
 ('TECH.MOORING.READ','TECHNICAL','View mooring plans within scope'),
 ('TECH.MOORING.WRITE','TECHNICAL','Prepare, submit and issue mooring plans within scope'),
 ('TECH.MOORING.APPROVE','TECHNICAL','Verify values and approve another person\'s mooring plan');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('TECH.MOORING.READ','TECH.MOORING.WRITE','TECH.MOORING.APPROVE')
WHERE r.code IN ('SYSTEM_ADMIN','TECHNICAL');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'TECH.MOORING.READ' FROM role r
WHERE r.code IN ('OPERATIONS','COMMERCIAL','HSE_REVIEWER','MANAGEMENT_APPROVER','AUDITOR');
