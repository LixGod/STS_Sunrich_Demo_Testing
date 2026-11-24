-- Ship2Ship platform core: users, roles, permissions, scoped role assignment, audit.
-- Conventions (docs/architecture/api-conventions.md): BINARY(16) UUIDv7 keys, DATETIME(6) UTC,
-- optimistic-lock "version", no physical deletes of referenced records.

CREATE TABLE app_user (
    id                  BINARY(16)   NOT NULL,
    external_subject_id VARCHAR(128) NOT NULL COMMENT 'Subject identifier issued by the existing authentication system',
    full_name           VARCHAR(150) NOT NULL,
    work_email          VARCHAR(254) NOT NULL,
    department          VARCHAR(80)  NULL,
    position            VARCHAR(80)  NULL,
    status              VARCHAR(20)  NOT NULL COMMENT 'ACTIVE | SUSPENDED | DEACTIVATED',
    language            VARCHAR(10)  NOT NULL DEFAULT 'en',
    mfa_enabled         BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'Mirror of the authentication system MFA state',
    last_login_at       DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    created_by          BINARY(16)   NULL,
    updated_at          DATETIME(6)  NOT NULL,
    updated_by          BINARY(16)   NULL,
    version             BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_user_subject (external_subject_id),
    UNIQUE KEY uk_app_user_email (work_email),
    KEY ix_app_user_status_name (status, full_name)
) ENGINE = InnoDB;

CREATE TABLE role (
    id          BINARY(16)   NOT NULL,
    code        VARCHAR(40)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB;

CREATE TABLE permission (
    code        VARCHAR(80)  NOT NULL,
    module      VARCHAR(40)  NOT NULL,
    description VARCHAR(255) NOT NULL,
    PRIMARY KEY (code)
) ENGINE = InnoDB;

CREATE TABLE role_permission (
    role_id         BINARY(16)  NOT NULL,
    permission_code VARCHAR(80) NOT NULL,
    PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE RESTRICT,
    CONSTRAINT fk_role_permission_perm FOREIGN KEY (permission_code) REFERENCES permission (code) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- A role granted to a user within a scope. NULL legal_entity_id = all entities; NULL branch_id = whole entity.
-- Foreign keys to legal_entity / branch are added in V2 once those tables exist.
CREATE TABLE user_role_scope (
    id              BINARY(16)  NOT NULL,
    user_id         BINARY(16)  NOT NULL,
    role_id         BINARY(16)  NOT NULL,
    legal_entity_id BINARY(16)  NULL,
    branch_id       BINARY(16)  NULL,
    valid_from      DATE        NOT NULL,
    valid_to        DATE        NULL,
    created_at      DATETIME(6) NOT NULL,
    created_by      BINARY(16)  NULL,
    PRIMARY KEY (id),
    KEY ix_user_role_scope_user (user_id),
    CONSTRAINT fk_urs_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE RESTRICT,
    CONSTRAINT fk_urs_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE RESTRICT,
    CONSTRAINT ck_urs_branch_needs_entity CHECK (branch_id IS NULL OR legal_entity_id IS NOT NULL)
) ENGINE = InnoDB;

-- Append-only, hash-chained audit trail (SRS FR-UM-04, SR-03).
-- The production database account must hold only INSERT and SELECT on this table.
CREATE TABLE audit_event (
    seq             BIGINT       NOT NULL AUTO_INCREMENT,
    id              BINARY(16)   NOT NULL,
    occurred_at     DATETIME(6)  NOT NULL,
    actor_user_id   BINARY(16)   NULL,
    actor_name      VARCHAR(150) NULL,
    action          VARCHAR(40)  NOT NULL,
    entity_type     VARCHAR(60)  NULL,
    entity_id       BINARY(16)   NULL,
    legal_entity_id BINARY(16)   NULL,
    branch_id       BINARY(16)   NULL,
    -- Text (not the JSON type) so the stored bytes stay identical to the hashed content; MySQL re-orders JSON keys.
    details         MEDIUMTEXT   NULL,
    client_ip       VARCHAR(45)  NULL,
    prev_hash       CHAR(64)     NOT NULL,
    row_hash        CHAR(64)     NOT NULL,
    PRIMARY KEY (seq),
    UNIQUE KEY uk_audit_event_id (id),
    KEY ix_audit_event_entity (entity_type, entity_id, seq),
    KEY ix_audit_event_actor (actor_user_id, seq),
    KEY ix_audit_event_scope (legal_entity_id, branch_id, seq),
    CONSTRAINT ck_audit_event_details_json CHECK (details IS NULL OR JSON_VALID(details))
) ENGINE = InnoDB;

-- Single-row lock that serialises hash-chain appends.
CREATE TABLE audit_chain_head (
    id        TINYINT  NOT NULL,
    last_hash CHAR(64) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_audit_chain_head_single CHECK (id = 1)
) ENGINE = InnoDB;

INSERT INTO audit_chain_head (id, last_hash) VALUES (1, REPEAT('0', 64));
