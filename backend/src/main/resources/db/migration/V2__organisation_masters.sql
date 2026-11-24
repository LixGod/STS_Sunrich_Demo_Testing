-- Organisation masters (SRS D02, §21): legal entity, branch, STS location.

CREATE TABLE legal_entity (
    id                  BINARY(16)   NOT NULL,
    code                VARCHAR(20)  NOT NULL,
    legal_name          VARCHAR(200) NOT NULL,
    tax_registration_no VARCHAR(50)  NULL,
    billing_address     VARCHAR(500) NULL,
    base_currency       CHAR(3)      NOT NULL,
    default_timezone    VARCHAR(64)  NOT NULL,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          DATETIME(6)  NOT NULL,
    created_by          BINARY(16)   NULL,
    updated_at          DATETIME(6)  NOT NULL,
    updated_by          BINARY(16)   NULL,
    version             BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_legal_entity_code (code),
    KEY ix_legal_entity_active_name (active, legal_name)
) ENGINE = InnoDB;

CREATE TABLE branch (
    id              BINARY(16)   NOT NULL,
    legal_entity_id BINARY(16)   NOT NULL,
    code            VARCHAR(20)  NOT NULL,
    name            VARCHAR(150) NOT NULL,
    timezone        VARCHAR(64)  NOT NULL,
    address         VARCHAR(500) NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BINARY(16)   NULL,
    updated_at      DATETIME(6)  NOT NULL,
    updated_by      BINARY(16)   NULL,
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_branch_entity_code (legal_entity_id, code),
    KEY ix_branch_entity_active_name (legal_entity_id, active, name),
    CONSTRAINT fk_branch_legal_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE sts_location (
    id           BINARY(16)   NOT NULL,
    branch_id    BINARY(16)   NOT NULL,
    code         VARCHAR(20)  NOT NULL,
    name         VARCHAR(150) NOT NULL,
    latitude     DECIMAL(9,6) NULL,
    longitude    DECIMAL(9,6) NULL,
    timezone     VARCHAR(64)  NOT NULL,
    jurisdiction VARCHAR(150) NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   DATETIME(6)  NOT NULL,
    created_by   BINARY(16)   NULL,
    updated_at   DATETIME(6)  NOT NULL,
    updated_by   BINARY(16)   NULL,
    version      BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sts_location_branch_code (branch_id, code),
    KEY ix_sts_location_branch_active_name (branch_id, active, name),
    CONSTRAINT fk_sts_location_branch FOREIGN KEY (branch_id) REFERENCES branch (id) ON DELETE RESTRICT,
    CONSTRAINT ck_sts_location_lat CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_sts_location_lon CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
) ENGINE = InnoDB;

ALTER TABLE user_role_scope
    ADD CONSTRAINT fk_urs_legal_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_urs_branch FOREIGN KEY (branch_id) REFERENCES branch (id) ON DELETE RESTRICT;
