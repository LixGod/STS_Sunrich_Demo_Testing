-- Phase 4b: live operations and cargo (FR-J07, FR-J08, FR-J09, FR-OPS-01/02, D13, UAT-07/08). ETA revisions, the
-- operation event log and Statement of Facts, grade-wise cargo readings, delays and incidents. Nothing is deleted:
-- corrections keep the previous version with a reason (BR-02). Lists are candidates (labels only), editable.

CREATE TABLE eta_revision (
    id            BINARY(16)   NOT NULL,
    job_vessel_id BINARY(16)   NOT NULL,
    revision_no   INT          NOT NULL,
    eta_utc       DATETIME(6)  NOT NULL,
    timezone      VARCHAR(64)  NOT NULL,
    source        VARCHAR(20)  NOT NULL COMMENT 'VESSEL_MASTER | AGENT | OWNER_CHARTERER | OTHER',
    note          VARCHAR(500) NULL,
    recorded_by   BINARY(16)   NOT NULL,
    recorded_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_eta_revision (job_vessel_id, revision_no),
    CONSTRAINT fk_eta_revision_vessel FOREIGN KEY (job_vessel_id) REFERENCES job_vessel (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Configurable lists (code, label, order, active).
CREATE TABLE operation_event_type (
    code        VARCHAR(40)  NOT NULL,
    label       VARCHAR(100) NOT NULL,
    phase       VARCHAR(20)  NOT NULL COMMENT 'BERTHING | TRANSFER | COMPLETION | UNMOORING | OTHER',
    is_interval BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'Has a start and an end (e.g. hose connection)',
    sort_order  INT          NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  DATETIME(6)  NOT NULL,
    version     BIGINT       NOT NULL,
    PRIMARY KEY (code)
) ENGINE = InnoDB;

CREATE TABLE delay_category (
    code VARCHAR(40) NOT NULL, label VARCHAR(100) NOT NULL, sort_order INT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL, PRIMARY KEY (code)
) ENGINE = InnoDB;

CREATE TABLE incident_category (
    code VARCHAR(40) NOT NULL, label VARCHAR(100) NOT NULL, sort_order INT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL, PRIMARY KEY (code)
) ENGINE = InnoDB;

CREATE TABLE incident_severity (
    code VARCHAR(40) NOT NULL, label VARCHAR(100) NOT NULL, sort_order INT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at DATETIME(6) NOT NULL, version BIGINT NOT NULL, PRIMARY KEY (code)
) ENGINE = InnoDB;

-- Candidates taken from the section headings of the sample operation report; labels only, to be confirmed (owner Q1, Q5).
INSERT INTO operation_event_type (code, label, phase, is_interval, sort_order, updated_at, version) VALUES
    ('POAC_BOARDED',        'POAC boarded',               'BERTHING',   FALSE,  10, UTC_TIMESTAMP(6), 0),
    ('FIRST_LINE',          'First line ashore',          'BERTHING',   FALSE,  20, UTC_TIMESTAMP(6), 0),
    ('ALL_FAST',            'All fast',                   'BERTHING',   FALSE,  30, UTC_TIMESTAMP(6), 0),
    ('HOSE_CONNECTION',     'Hose connection',            'TRANSFER',   TRUE,   40, UTC_TIMESTAMP(6), 0),
    ('LEAK_TEST',           'Leak test',                  'TRANSFER',   FALSE,  50, UTC_TIMESTAMP(6), 0),
    ('CARGO_COMMENCED',     'Cargo transfer commenced',   'TRANSFER',   FALSE,  60, UTC_TIMESTAMP(6), 0),
    ('CARGO_STOPPED',       'Cargo transfer stopped',     'TRANSFER',   FALSE,  70, UTC_TIMESTAMP(6), 0),
    ('CARGO_RESUMED',       'Cargo transfer resumed',     'TRANSFER',   FALSE,  80, UTC_TIMESTAMP(6), 0),
    ('CARGO_COMPLETED',     'Cargo transfer completed',   'COMPLETION', FALSE,  90, UTC_TIMESTAMP(6), 0),
    ('HOSES_DISCONNECTED',  'Hoses disconnected',         'COMPLETION', FALSE, 100, UTC_TIMESTAMP(6), 0),
    ('UNMOORING_COMMENCED', 'Unmooring commenced',        'UNMOORING',  FALSE, 110, UTC_TIMESTAMP(6), 0),
    ('CAST_OFF',            'All lines cast off',         'UNMOORING',  FALSE, 120, UTC_TIMESTAMP(6), 0),
    ('TUGS_CAST_OFF',       'Tugs cast off',              'UNMOORING',  FALSE, 130, UTC_TIMESTAMP(6), 0),
    ('POAC_DISEMBARKED',    'POAC disembarked',           'UNMOORING',  FALSE, 140, UTC_TIMESTAMP(6), 0),
    ('OTHER',               'Other event',                'OTHER',      FALSE, 900, UTC_TIMESTAMP(6), 0);

INSERT INTO delay_category (code, label, sort_order, updated_at, version) VALUES
    ('WEATHER', 'Weather', 10, UTC_TIMESTAMP(6), 0), ('VESSEL', 'Vessel', 20, UTC_TIMESTAMP(6), 0),
    ('EQUIPMENT', 'Equipment', 30, UTC_TIMESTAMP(6), 0), ('TERMINAL', 'Terminal / authorities', 40, UTC_TIMESTAMP(6), 0),
    ('OTHER', 'Other', 900, UTC_TIMESTAMP(6), 0);

INSERT INTO incident_category (code, label, sort_order, updated_at, version) VALUES
    ('INJURY', 'Personnel injury', 10, UTC_TIMESTAMP(6), 0), ('SPILL', 'Spill / pollution', 20, UTC_TIMESTAMP(6), 0),
    ('EQUIPMENT_DAMAGE', 'Equipment damage', 30, UTC_TIMESTAMP(6), 0), ('NEAR_MISS', 'Near miss', 40, UTC_TIMESTAMP(6), 0),
    ('OTHER', 'Other', 900, UTC_TIMESTAMP(6), 0);

INSERT INTO incident_severity (code, label, sort_order, updated_at, version) VALUES
    ('LOW', 'Low', 10, UTC_TIMESTAMP(6), 0), ('MEDIUM', 'Medium', 20, UTC_TIMESTAMP(6), 0), ('HIGH', 'High', 30, UTC_TIMESTAMP(6), 0);

-- Operation events: an instant (occurred_at) or an interval (started_at, ended_at; open while ongoing).
CREATE TABLE operation_event (
    id            BINARY(16)    NOT NULL,
    job_id        BINARY(16)    NOT NULL,
    type_code     VARCHAR(40)   NOT NULL,
    job_vessel_id BINARY(16)    NULL,
    occurred_at   DATETIME(6)   NULL,
    started_at    DATETIME(6)   NULL,
    ended_at      DATETIME(6)   NULL,
    remarks       VARCHAR(1000) NULL,
    source        VARCHAR(20)   NOT NULL COMMENT 'LIVE | PAPER_LOG | RADIO | OTHER',
    status        VARCHAR(20)   NOT NULL COMMENT 'ACTIVE | VOIDED',
    void_reason   VARCHAR(500)  NULL,
    recorded_by   BINARY(16)    NOT NULL,
    recorded_at   DATETIME(6)   NOT NULL,
    updated_by    BINARY(16)    NULL,
    updated_at    DATETIME(6)   NOT NULL,
    version       BIGINT        NOT NULL,
    PRIMARY KEY (id),
    KEY ix_operation_event_job (job_id, status),
    CONSTRAINT fk_operation_event_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_operation_event_type FOREIGN KEY (type_code) REFERENCES operation_event_type (code) ON DELETE RESTRICT,
    CONSTRAINT fk_operation_event_vessel FOREIGN KEY (job_vessel_id) REFERENCES job_vessel (id) ON DELETE RESTRICT,
    CONSTRAINT ck_operation_event_time CHECK (occurred_at IS NOT NULL OR started_at IS NOT NULL),
    CONSTRAINT ck_operation_event_interval CHECK (ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at)
) ENGINE = InnoDB;

-- Statement of Facts: prepared from the events, issued (immutable, hashed) by someone other than the preparer.
CREATE TABLE sof_revision (
    id              BINARY(16)   NOT NULL,
    job_id          BINARY(16)   NOT NULL,
    revision_no     INT          NOT NULL,
    status          VARCHAR(20)  NOT NULL COMMENT 'PREPARED | ISSUED | SUPERSEDED | WITHDRAWN',
    events_json     MEDIUMTEXT   NOT NULL COMMENT 'The events as they stood when the revision was prepared',
    prepared_by     BINARY(16)   NOT NULL,
    prepared_at     DATETIME(6)  NOT NULL,
    issued_by       BINARY(16)   NULL,
    issued_at       DATETIME(6)  NULL,
    pdf             MEDIUMBLOB   NULL,
    pdf_sha256      CHAR(64)     NULL,
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sof_revision (job_id, revision_no),
    CONSTRAINT fk_sof_revision_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sof_second_person CHECK (issued_by IS NULL OR issued_by <> prepared_by)
) ENGINE = InnoDB;

-- Grade-wise cargo readings: cumulative quantity in the job cargo line's unit. A lower total than the previous reading
-- is only accepted as a CORRECTION with a reason.
CREATE TABLE cargo_reading (
    id                    BINARY(16)     NOT NULL,
    job_cargo_id          BINARY(16)     NOT NULL,
    read_at               DATETIME(6)    NOT NULL,
    cumulative            DECIMAL(18, 3) NOT NULL,
    unit_id               BINARY(16)     NOT NULL,
    kind                  VARCHAR(20)    NOT NULL COMMENT 'NORMAL | FINAL | CORRECTION',
    reason                VARCHAR(500)   NULL,
    certified_document_id BINARY(16)     NULL COMMENT 'Job document with the certified final figure',
    remarks               VARCHAR(500)   NULL,
    recorded_by           BINARY(16)     NOT NULL,
    recorded_at           DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    KEY ix_cargo_reading (job_cargo_id, read_at),
    CONSTRAINT fk_cargo_reading_cargo FOREIGN KEY (job_cargo_id) REFERENCES job_cargo (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cargo_reading_document FOREIGN KEY (certified_document_id) REFERENCES document_version (id) ON DELETE RESTRICT,
    CONSTRAINT ck_cargo_reading_positive CHECK (cumulative >= 0),
    CONSTRAINT ck_cargo_reading_reason CHECK (kind <> 'CORRECTION' OR reason IS NOT NULL)
) ENGINE = InnoDB;

CREATE TABLE job_delay (
    id            BINARY(16)    NOT NULL,
    job_id        BINARY(16)    NOT NULL,
    category_code VARCHAR(40)   NOT NULL,
    started_at    DATETIME(6)   NOT NULL,
    ended_at      DATETIME(6)   NULL,
    description   VARCHAR(2000) NOT NULL,
    status        VARCHAR(20)   NOT NULL COMMENT 'ACTIVE | VOIDED',
    void_reason   VARCHAR(500)  NULL,
    recorded_by   BINARY(16)    NOT NULL,
    recorded_at   DATETIME(6)   NOT NULL,
    updated_by    BINARY(16)    NULL,
    updated_at    DATETIME(6)   NOT NULL,
    version       BIGINT        NOT NULL,
    PRIMARY KEY (id),
    KEY ix_job_delay_job (job_id, started_at),
    CONSTRAINT fk_job_delay_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_delay_category FOREIGN KEY (category_code) REFERENCES delay_category (code) ON DELETE RESTRICT,
    CONSTRAINT ck_job_delay_interval CHECK (ended_at IS NULL OR ended_at >= started_at)
) ENGINE = InnoDB;

CREATE TABLE job_incident (
    id                BINARY(16)    NOT NULL,
    job_id            BINARY(16)    NOT NULL,
    category_code     VARCHAR(40)   NOT NULL,
    severity_code     VARCHAR(40)   NOT NULL,
    occurred_at       DATETIME(6)   NOT NULL,
    description       VARCHAR(2000) NOT NULL,
    immediate_actions VARCHAR(2000) NULL,
    status            VARCHAR(20)   NOT NULL COMMENT 'REPORTED | UNDER_REVIEW | CLOSED',
    reported_by       BINARY(16)    NOT NULL,
    reported_at       DATETIME(6)   NOT NULL,
    review_started_by BINARY(16)    NULL,
    review_started_at DATETIME(6)   NULL,
    closed_by         BINARY(16)    NULL,
    closed_at         DATETIME(6)   NULL,
    closing_note      VARCHAR(2000) NULL,
    updated_by        BINARY(16)    NULL,
    updated_at        DATETIME(6)   NOT NULL,
    version           BIGINT        NOT NULL,
    PRIMARY KEY (id),
    KEY ix_job_incident_job (job_id, status),
    CONSTRAINT fk_job_incident_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_incident_category FOREIGN KEY (category_code) REFERENCES incident_category (code) ON DELETE RESTRICT,
    CONSTRAINT fk_job_incident_severity FOREIGN KEY (severity_code) REFERENCES incident_severity (code) ON DELETE RESTRICT,
    CONSTRAINT ck_job_incident_second_person CHECK (closed_by IS NULL OR closed_by <> reported_by)
) ENGINE = InnoDB;

CREATE TABLE incident_action (
    id          BINARY(16)   NOT NULL,
    incident_id BINARY(16)   NOT NULL,
    sort_order  INT          NOT NULL,
    action_text VARCHAR(500) NOT NULL,
    owner_name  VARCHAR(150) NULL,
    due_date    DATE         NULL,
    done_at     DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY ix_incident_action (incident_id, sort_order),
    CONSTRAINT fk_incident_action_incident FOREIGN KEY (incident_id) REFERENCES job_incident (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- History of every correction to events, delays and incidents (the values before the change, with the reason).
CREATE TABLE operation_change (
    id          BINARY(16)   NOT NULL,
    record_type VARCHAR(20)  NOT NULL COMMENT 'EVENT | DELAY | INCIDENT',
    record_id   BINARY(16)   NOT NULL,
    version_no  BIGINT       NOT NULL,
    before_json MEDIUMTEXT   NOT NULL,
    reason      VARCHAR(500) NOT NULL,
    changed_by  BINARY(16)   NOT NULL,
    changed_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_operation_change_record (record_type, record_id, version_no)
) ENGINE = InnoDB;

-- Evidence files for delays and incidents (stored through the file service; NOT_SCANNED until ClamAV is approved).
CREATE TABLE operation_file (
    id          BINARY(16)  NOT NULL,
    job_id      BINARY(16)  NOT NULL,
    owner_type  VARCHAR(20) NOT NULL COMMENT 'DELAY | INCIDENT',
    owner_id    BINARY(16)  NOT NULL,
    file_id     BINARY(16)  NOT NULL,
    uploaded_by BINARY(16)  NOT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_operation_file_owner (owner_type, owner_id),
    CONSTRAINT fk_operation_file_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_operation_file_file FOREIGN KEY (file_id) REFERENCES stored_file (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

INSERT INTO permission (code, module, description) VALUES
    ('OPS.LIVE.READ',          'OPERATIONS', 'View live operations (ETA, events, SOF, cargo) within scope'),
    ('OPS.LIVE.WRITE',         'OPERATIONS', 'Record ETA revisions and operation events, prepare the SOF within scope'),
    ('OPS.LIVE.APPROVE',       'OPERATIONS', 'Issue a Statement of Facts prepared by someone else'),
    ('OPS.CARGO.WRITE',        'OPERATIONS', 'Record cargo readings within scope'),
    ('OPS.CARGO.CORRECT',      'OPERATIONS', 'Correct cargo readings (a lower cumulative total) with a reason'),
    ('OPS.INCIDENT.READ',      'OPERATIONS', 'View delays and incidents within scope'),
    ('OPS.INCIDENT.WRITE',     'OPERATIONS', 'Record delays and incidents within scope'),
    ('OPS.INCIDENT.REVIEW',    'OPERATIONS', 'Review and close incidents reported by someone else'),
    ('MASTER.OPERATIONS.WRITE','MASTERS',    'Manage operation event types, delay and incident lists');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('OPS.LIVE.READ', 'OPS.LIVE.WRITE', 'OPS.LIVE.APPROVE', 'OPS.CARGO.WRITE',
    'OPS.CARGO.CORRECT', 'OPS.INCIDENT.READ', 'OPS.INCIDENT.WRITE', 'OPS.INCIDENT.REVIEW', 'MASTER.OPERATIONS.WRITE')
WHERE r.code = 'SYSTEM_ADMIN';

-- Baseline mapping, to be confirmed by Ship2Ship (SRS §27).
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('OPS.LIVE.READ', 'OPS.LIVE.WRITE', 'OPS.LIVE.APPROVE', 'OPS.CARGO.WRITE',
    'OPS.CARGO.CORRECT', 'OPS.INCIDENT.READ', 'OPS.INCIDENT.WRITE', 'MASTER.OPERATIONS.WRITE')
WHERE r.code = 'OPERATIONS';

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('OPS.LIVE.READ', 'OPS.INCIDENT.READ', 'OPS.INCIDENT.WRITE', 'OPS.INCIDENT.REVIEW')
WHERE r.code = 'HSE_REVIEWER';

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('OPS.LIVE.READ', 'OPS.INCIDENT.READ')
WHERE r.code IN ('TECHNICAL', 'COMMERCIAL', 'MANAGEMENT_APPROVER', 'AUDITOR');
