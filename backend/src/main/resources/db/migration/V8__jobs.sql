-- Phase 2c: owner acceptance of an issued quotation creates exactly one STS job (FR-J03, UAT-03) with the quoted tariff
-- frozen on it (AR-02). Later phases attach documents, planning, operations and finance to the job.

CREATE TABLE sts_job (
    id                   BINARY(16)     NOT NULL,
    job_number           VARCHAR(40)    NOT NULL,
    legal_entity_id      BINARY(16)     NOT NULL,
    branch_id            BINARY(16)     NOT NULL,
    status               VARCHAR(30)    NOT NULL COMMENT 'OPEN; later phases add the full job lifecycle (SRS §6)',
    enquiry_id           BINARY(16)     NOT NULL,
    enquiry_reference    VARCHAR(40)    NOT NULL,
    quotation_version_id BINARY(16)     NOT NULL,
    quotation_reference  VARCHAR(60)    NOT NULL,
    client_id            BINARY(16)     NOT NULL,
    client_contact_id    BINARY(16)     NULL,
    sts_location_id      BINARY(16)     NULL,
    operation_type       VARCHAR(20)    NULL,
    currency             CHAR(3)        NOT NULL,
    quoted_amount        DECIMAL(19, 4) NOT NULL,
    created_at           DATETIME(6)    NOT NULL,
    created_by           BINARY(16)     NULL,
    updated_at           DATETIME(6)    NOT NULL,
    updated_by           BINARY(16)     NULL,
    version              BIGINT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sts_job_number (job_number),
    UNIQUE KEY uk_sts_job_enquiry (enquiry_id),
    UNIQUE KEY uk_sts_job_quotation (quotation_version_id),
    KEY ix_sts_job_scope_status (legal_entity_id, branch_id, status, created_at),
    CONSTRAINT fk_sts_job_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sts_job_branch FOREIGN KEY (branch_id) REFERENCES branch (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sts_job_enquiry FOREIGN KEY (enquiry_id) REFERENCES enquiry (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sts_job_quotation FOREIGN KEY (quotation_version_id) REFERENCES quotation_version (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sts_job_client FOREIGN KEY (client_id) REFERENCES client (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE job_vessel (
    id           BINARY(16)  NOT NULL,
    job_id       BINARY(16)  NOT NULL,
    role         VARCHAR(20) NOT NULL COMMENT 'MOTHER | DAUGHTER',
    vessel_id    BINARY(16)  NOT NULL,
    eta_utc      DATETIME(6) NULL,
    eta_timezone VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_vessel_role (job_id, role),
    KEY ix_job_vessel_vessel (vessel_id),
    CONSTRAINT fk_job_vessel_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_vessel_vessel FOREIGN KEY (vessel_id) REFERENCES vessel (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE job_cargo (
    id             BINARY(16)     NOT NULL,
    job_id         BINARY(16)     NOT NULL,
    line_no        INT            NOT NULL,
    cargo_grade_id BINARY(16)     NOT NULL,
    quantity       DECIMAL(18, 3) NOT NULL,
    unit_id        BINARY(16)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_cargo_line (job_id, line_no),
    CONSTRAINT fk_job_cargo_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_cargo_grade FOREIGN KEY (cargo_grade_id) REFERENCES cargo_grade (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_cargo_unit FOREIGN KEY (unit_id) REFERENCES unit_of_measure (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Copy of the accepted tariff terms. Never updated: later tariff changes do not alter accepted work (AR-02).
CREATE TABLE tariff_snapshot (
    id                        BINARY(16)     NOT NULL,
    job_id                    BINARY(16)     NOT NULL,
    tariff_id                 BINARY(16)     NOT NULL,
    tariff_code               VARCHAR(20)    NOT NULL,
    tariff_name               VARCHAR(150)   NOT NULL,
    currency                  CHAR(3)        NOT NULL,
    base_hours                INT            NOT NULL,
    base_charge               DECIMAL(19, 4) NOT NULL,
    extra_hour_rate           DECIMAL(19, 4) NOT NULL,
    billing_increment_minutes INT            NULL,
    grace_minutes             INT            NULL,
    rounding_rule             VARCHAR(30)    NULL,
    effective_from            DATE           NOT NULL,
    effective_to              DATE           NULL,
    captured_at               DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tariff_snapshot_job (job_id),
    CONSTRAINT fk_tariff_snapshot_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE owner_acceptance (
    id                   BINARY(16)   NOT NULL,
    quotation_version_id BINARY(16)   NOT NULL,
    job_id               BINARY(16)   NOT NULL,
    accepted_at          DATETIME(6)  NOT NULL,
    accepted_by_name     VARCHAR(150) NOT NULL COMMENT 'Person who accepted for the owner/charterer',
    work_order_reference VARCHAR(100) NULL,
    payment_terms        VARCHAR(500) NULL,
    special_conditions   TEXT         NULL,
    after_validity       BOOLEAN      NOT NULL COMMENT 'Accepted after the quotation valid-until date',
    recorded_by          BINARY(16)   NOT NULL,
    recorded_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner_acceptance_version (quotation_version_id),
    UNIQUE KEY uk_owner_acceptance_job (job_id),
    CONSTRAINT fk_owner_acceptance_version FOREIGN KEY (quotation_version_id) REFERENCES quotation_version (id) ON DELETE RESTRICT,
    CONSTRAINT fk_owner_acceptance_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Placeholder mask until Ship2Ship confirms its job numbering (e.g. the "STS/VADINAR LPO/26100122" pattern, SRS §27).
INSERT INTO number_series_format (id, series_type, legal_entity_id, mask)
VALUES (UUID_TO_BIN('01920000-0000-7000-8000-000000000103'), 'JOB', NULL, 'JOB/{ENTITY}/{YYYY}/{SEQ:5}');

INSERT INTO permission (code, module, description) VALUES
    ('JOB.READ', 'JOBS', 'View STS jobs within scope');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'JOB.READ' FROM role r;
