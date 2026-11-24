-- Enquiry register (FR-J01) plus shared platform tables used from Phase 2 onwards:
-- numbering series (D02), status transition history (FR-WF-01) and idempotency keys (TR-API-01).

CREATE TABLE number_series (
    id              BINARY(16)  NOT NULL,
    series_type     VARCHAR(30) NOT NULL,
    legal_entity_id BINARY(16)  NOT NULL,
    period_year     SMALLINT    NOT NULL,
    next_value      BIGINT      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_number_series (series_type, legal_entity_id, period_year),
    CONSTRAINT fk_number_series_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Display format per series, optionally per legal entity. Tokens: {ENTITY} {BRANCH} {YYYY} {YY} {SEQ:n}.
-- The seeded formats are placeholders until Ship2Ship confirms its numbering masks (SRS §27).
CREATE TABLE number_series_format (
    id              BINARY(16)   NOT NULL,
    series_type     VARCHAR(30)  NOT NULL,
    legal_entity_id BINARY(16)   NULL COMMENT 'NULL = default for all entities',
    mask            VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_number_series_format (series_type, legal_entity_id),
    CONSTRAINT fk_number_format_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

INSERT INTO number_series_format (id, series_type, legal_entity_id, mask)
VALUES (UUID_TO_BIN('01920000-0000-7000-8000-000000000101'), 'ENQUIRY', NULL, 'ENQ/{ENTITY}/{YYYY}/{SEQ:5}');

CREATE TABLE workflow_transition (
    id            BINARY(16)   NOT NULL,
    entity_type   VARCHAR(40)  NOT NULL,
    entity_id     BINARY(16)   NOT NULL,
    from_status   VARCHAR(30)  NULL,
    to_status     VARCHAR(30)  NOT NULL,
    reason        VARCHAR(500) NULL,
    actor_user_id BINARY(16)   NULL,
    occurred_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY ix_workflow_transition_entity (entity_type, entity_id, occurred_at)
) ENGINE = InnoDB;

-- A repeated create with the same key by the same user returns the original record instead of a duplicate.
CREATE TABLE idempotency_key (
    id          BINARY(16)   NOT NULL,
    user_id     BINARY(16)   NOT NULL,
    idem_key    VARCHAR(100) NOT NULL,
    operation   VARCHAR(60)  NOT NULL,
    resource_id BINARY(16)   NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency (user_id, operation, idem_key)
) ENGINE = InnoDB;

CREATE TABLE enquiry (
    id                     BINARY(16)    NOT NULL,
    reference              VARCHAR(40)   NOT NULL,
    legal_entity_id        BINARY(16)    NOT NULL,
    branch_id              BINARY(16)    NOT NULL,
    status                 VARCHAR(30)   NOT NULL COMMENT 'DRAFT | COSTING | QUOTATION_APPROVAL | QUOTED | ACCEPTED | LOST | CANCELLED',
    received_at            DATETIME(6)   NOT NULL,
    client_id              BINARY(16)    NOT NULL,
    client_contact_id      BINARY(16)    NULL,
    sts_location_id        BINARY(16)    NULL,
    operation_type         VARCHAR(20)   NULL COMMENT 'PETROLEUM | LPG | LNG | CHEMICAL | OTHER',
    expected_duration_hrs  DECIMAL(6, 1) NULL,
    scope_of_services      TEXT          NULL,
    special_requirements   TEXT          NULL,
    source_reference       VARCHAR(255)  NULL COMMENT 'Client email subject / reference until attachments arrive (Phase 3)',
    created_at             DATETIME(6)   NOT NULL,
    created_by             BINARY(16)    NULL,
    updated_at             DATETIME(6)   NOT NULL,
    updated_by             BINARY(16)    NULL,
    version                BIGINT        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enquiry_reference (reference),
    KEY ix_enquiry_scope_status (legal_entity_id, branch_id, status, received_at),
    KEY ix_enquiry_client (client_id, received_at),
    CONSTRAINT fk_enquiry_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enquiry_branch FOREIGN KEY (branch_id) REFERENCES branch (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enquiry_client FOREIGN KEY (client_id) REFERENCES client (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enquiry_contact FOREIGN KEY (client_contact_id) REFERENCES client_contact (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enquiry_location FOREIGN KEY (sts_location_id) REFERENCES sts_location (id) ON DELETE RESTRICT,
    CONSTRAINT ck_enquiry_duration CHECK (expected_duration_hrs IS NULL OR expected_duration_hrs > 0)
) ENGINE = InnoDB;

-- Mother (discharging) and daughter (receiving) vessel with their tentative ETAs: UTC plus the zone it was entered in.
CREATE TABLE enquiry_vessel (
    id           BINARY(16)  NOT NULL,
    enquiry_id   BINARY(16)  NOT NULL,
    role         VARCHAR(20) NOT NULL COMMENT 'MOTHER | DAUGHTER',
    vessel_id    BINARY(16)  NOT NULL,
    eta_utc      DATETIME(6) NULL,
    eta_timezone VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enquiry_vessel_role (enquiry_id, role),
    KEY ix_enquiry_vessel_vessel (vessel_id),
    CONSTRAINT fk_enquiry_vessel_enquiry FOREIGN KEY (enquiry_id) REFERENCES enquiry (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enquiry_vessel_vessel FOREIGN KEY (vessel_id) REFERENCES vessel (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- One row per cargo grade; several grades may transfer simultaneously (e.g. propane and butane).
CREATE TABLE enquiry_cargo (
    id             BINARY(16)     NOT NULL,
    enquiry_id     BINARY(16)     NOT NULL,
    line_no        INT            NOT NULL,
    cargo_grade_id BINARY(16)     NOT NULL,
    quantity       DECIMAL(18, 3) NOT NULL,
    unit_id        BINARY(16)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enquiry_cargo_line (enquiry_id, line_no),
    CONSTRAINT fk_enquiry_cargo_enquiry FOREIGN KEY (enquiry_id) REFERENCES enquiry (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enquiry_cargo_grade FOREIGN KEY (cargo_grade_id) REFERENCES cargo_grade (id) ON DELETE RESTRICT,
    CONSTRAINT fk_enquiry_cargo_unit FOREIGN KEY (unit_id) REFERENCES unit_of_measure (id) ON DELETE RESTRICT,
    CONSTRAINT ck_enquiry_cargo_quantity CHECK (quantity > 0)
) ENGINE = InnoDB;
