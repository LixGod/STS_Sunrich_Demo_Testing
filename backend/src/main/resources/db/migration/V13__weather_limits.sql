-- Phase 3e: job operating limits (approved per job by a second person) and weather reports (forecasts and observations,
-- uploaded or entered by hand) with warnings against the approved limits only (FR-TECH-04, D09, D11, UAT-06).
-- No limit values are seeded; StormGeo has no adapter until an entitlement exists.

-- Measurable weather quantities. The three candidates come from the JPO workbook's limits table (parameters only, no
-- values); technical administrators add or deactivate others.
CREATE TABLE weather_parameter (
    code       VARCHAR(40)  NOT NULL,
    label      VARCHAR(100) NOT NULL,
    unit       VARCHAR(20)  NOT NULL,
    sort_order INT          NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at DATETIME(6)  NOT NULL,
    version    BIGINT       NOT NULL,
    PRIMARY KEY (code)
) ENGINE = InnoDB;

INSERT INTO weather_parameter (code, label, unit, sort_order, updated_at, version) VALUES
    ('WIND_SPEED',  'Wind speed',             'kn', 10, UTC_TIMESTAMP(6), 0),
    ('WAVE_HEIGHT', 'Significant wave height', 'm', 20, UTC_TIMESTAMP(6), 0),
    ('WAVE_PERIOD', 'Wave period',            's',  30, UTC_TIMESTAMP(6), 0);

-- One revision of a job's operating limits. Only an APPROVED set is used for warnings and the JPO.
CREATE TABLE job_limit_set (
    id              BINARY(16)   NOT NULL,
    job_id          BINARY(16)   NOT NULL,
    revision_no     INT          NOT NULL,
    status          VARCHAR(20)  NOT NULL COMMENT 'DRAFT | SUBMITTED | APPROVED | SUPERSEDED',
    submitted_by    BINARY(16)   NULL,
    submitted_at    DATETIME(6)  NULL,
    decided_by      BINARY(16)   NULL,
    decided_at      DATETIME(6)  NULL,
    returned_reason VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BINARY(16)   NULL,
    updated_at      DATETIME(6)  NOT NULL,
    updated_by      BINARY(16)   NULL,
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_limit_set (job_id, revision_no),
    KEY ix_job_limit_set_status (job_id, status),
    CONSTRAINT fk_job_limit_set_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT,
    CONSTRAINT ck_job_limit_set_no_self_approval CHECK (decided_by IS NULL OR submitted_by IS NULL OR decided_by <> submitted_by)
) ENGINE = InnoDB;

CREATE TABLE job_limit (
    id             BINARY(16)    NOT NULL,
    set_id         BINARY(16)    NOT NULL,
    sort_order     INT           NOT NULL,
    parameter_code VARCHAR(40)   NOT NULL,
    operation      VARCHAR(100)  NOT NULL,
    comparison     VARCHAR(10)   NOT NULL COMMENT 'ABOVE: warn when a value is above the threshold | BELOW: warn when below',
    threshold      DECIMAL(10,2) NOT NULL,
    action         VARCHAR(300)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_job_limit_set (set_id, sort_order),
    CONSTRAINT fk_job_limit_set FOREIGN KEY (set_id) REFERENCES job_limit_set (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_limit_parameter FOREIGN KEY (parameter_code) REFERENCES weather_parameter (code) ON DELETE RESTRICT,
    CONSTRAINT ck_job_limit_comparison CHECK (comparison IN ('ABOVE', 'BELOW'))
) ENGINE = InnoDB;

-- A forecast (issue time and validity window) or an observation (observed time), with an optional file.
CREATE TABLE job_weather_report (
    id            BINARY(16)    NOT NULL,
    job_id        BINARY(16)    NOT NULL,
    kind          VARCHAR(20)   NOT NULL COMMENT 'FORECAST | OBSERVATION',
    source        VARCHAR(20)   NOT NULL COMMENT 'PROVIDER_REPORT | OTHER_PROVIDER | OBSERVATION',
    provider_name VARCHAR(100)  NULL,
    issued_at     DATETIME(6)   NULL,
    valid_from    DATETIME(6)   NULL,
    valid_to      DATETIME(6)   NULL,
    observed_at   DATETIME(6)   NULL,
    area          VARCHAR(200)  NULL,
    file_id       BINARY(16)    NULL,
    notes         VARCHAR(2000) NULL,
    status        VARCHAR(20)   NOT NULL COMMENT 'RECORDED | ACCEPTED | REJECTED',
    recorded_by   BINARY(16)    NOT NULL,
    recorded_at   DATETIME(6)   NOT NULL,
    reviewed_by   BINARY(16)    NULL,
    reviewed_at   DATETIME(6)   NULL,
    review_reason VARCHAR(500)  NULL,
    updated_at    DATETIME(6)   NOT NULL,
    updated_by    BINARY(16)    NULL,
    version       BIGINT        NOT NULL,
    PRIMARY KEY (id),
    KEY ix_job_weather_report_job (job_id, recorded_at),
    CONSTRAINT fk_job_weather_report_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_weather_report_file FOREIGN KEY (file_id) REFERENCES stored_file (id) ON DELETE RESTRICT,
    CONSTRAINT ck_job_weather_report_reviewer CHECK (reviewed_by IS NULL OR reviewed_by <> recorded_by),
    CONSTRAINT ck_job_weather_report_validity CHECK (valid_from IS NULL OR valid_to IS NULL OR valid_to > valid_from)
) ENGINE = InnoDB;

CREATE TABLE job_weather_value (
    id             BINARY(16)    NOT NULL,
    report_id      BINARY(16)    NOT NULL,
    value_at       DATETIME(6)   NOT NULL,
    parameter_code VARCHAR(40)   NOT NULL,
    value          DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_weather_value (report_id, value_at, parameter_code),
    CONSTRAINT fk_job_weather_value_report FOREIGN KEY (report_id) REFERENCES job_weather_report (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_weather_value_parameter FOREIGN KEY (parameter_code) REFERENCES weather_parameter (code) ON DELETE RESTRICT
) ENGINE = InnoDB;

INSERT INTO permission (code, module, description) VALUES
    ('TECH.WEATHER.READ',    'TECHNICAL', 'View job weather reports and operating limits within scope'),
    ('TECH.WEATHER.WRITE',   'TECHNICAL', 'Record weather reports and prepare job operating limits within scope'),
    ('TECH.WEATHER.APPROVE', 'TECHNICAL', 'Approve operating limits and accept weather reports recorded by someone else');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('TECH.WEATHER.READ', 'TECH.WEATHER.WRITE', 'TECH.WEATHER.APPROVE')
WHERE r.code IN ('SYSTEM_ADMIN', 'TECHNICAL');

-- Baseline mapping, to be confirmed by Ship2Ship (SRS §27).
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'TECH.WEATHER.READ' FROM role r WHERE r.code IN ('OPERATIONS', 'COMMERCIAL', 'HSE_REVIEWER', 'MANAGEMENT_APPROVER', 'AUDITOR');
