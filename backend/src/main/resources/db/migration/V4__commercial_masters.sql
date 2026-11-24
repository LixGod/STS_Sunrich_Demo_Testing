-- Commercial and finance masters for Phase 2 (SRS §4, §21, D03, D05).
-- Clients, vessels, cargo grades and units are shared group-wide reference data; tariffs belong to a legal entity.

CREATE TABLE client (
    id                  BINARY(16)   NOT NULL,
    code                VARCHAR(20)  NOT NULL,
    legal_name          VARCHAR(200) NOT NULL,
    tax_registration_no VARCHAR(50)  NULL,
    billing_address     VARCHAR(500) NULL,
    country_code        CHAR(2)      NULL COMMENT 'ISO 3166-1 alpha-2',
    default_currency    CHAR(3)      NULL,
    payment_terms       VARCHAR(500) NULL,
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          DATETIME(6)  NOT NULL,
    created_by          BINARY(16)   NULL,
    updated_at          DATETIME(6)  NOT NULL,
    updated_by          BINARY(16)   NULL,
    version             BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_client_code (code),
    KEY ix_client_active_name (active, legal_name)
) ENGINE = InnoDB;

-- A client may act in several commercial roles (owner, charterer, ...).
CREATE TABLE client_role (
    client_id BINARY(16)  NOT NULL,
    role      VARCHAR(20) NOT NULL COMMENT 'OWNER | CHARTERER | TRADER | AGENT | OTHER',
    PRIMARY KEY (client_id, role),
    CONSTRAINT fk_client_role_client FOREIGN KEY (client_id) REFERENCES client (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE client_contact (
    id         BINARY(16)   NOT NULL,
    client_id  BINARY(16)   NOT NULL,
    full_name  VARCHAR(150) NOT NULL,
    position   VARCHAR(100) NULL,
    email      VARCHAR(254) NULL,
    phone      VARCHAR(40)  NULL,
    is_primary BOOLEAN      NOT NULL DEFAULT FALSE,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at DATETIME(6)  NOT NULL,
    created_by BINARY(16)   NULL,
    updated_at DATETIME(6)  NOT NULL,
    updated_by BINARY(16)   NULL,
    version    BIGINT       NOT NULL,
    PRIMARY KEY (id),
    KEY ix_client_contact_client (client_id, active, full_name),
    CONSTRAINT fk_client_contact_client FOREIGN KEY (client_id) REFERENCES client (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Basic vessel identity. Verified technical particulars (Q88, GA, manifold) are versioned separately in Phase 3.
CREATE TABLE vessel (
    id                  BINARY(16)    NOT NULL,
    imo                 CHAR(7)       NOT NULL COMMENT 'IMO ship identification number, check digit validated',
    name                VARCHAR(150)  NOT NULL,
    former_names        VARCHAR(500)  NULL,
    vessel_type         VARCHAR(30)   NOT NULL,
    flag_state          CHAR(2)       NULL COMMENT 'ISO 3166-1 alpha-2',
    loa_m               DECIMAL(7, 2) NULL,
    beam_m              DECIMAL(6, 2) NULL,
    dwt_t               DECIMAL(10, 1) NULL,
    particulars_status  VARCHAR(20)   NOT NULL DEFAULT 'UNVERIFIED' COMMENT 'UNVERIFIED until marine review (FR-TECH-01)',
    active              BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          DATETIME(6)   NOT NULL,
    created_by          BINARY(16)    NULL,
    updated_at          DATETIME(6)   NOT NULL,
    updated_by          BINARY(16)    NULL,
    version             BIGINT        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vessel_imo (imo),
    KEY ix_vessel_active_name (active, name),
    CONSTRAINT ck_vessel_imo_digits CHECK (imo REGEXP '^[0-9]{7}$'),
    CONSTRAINT ck_vessel_loa CHECK (loa_m IS NULL OR loa_m > 0),
    CONSTRAINT ck_vessel_beam CHECK (beam_m IS NULL OR beam_m > 0)
) ENGINE = InnoDB;

CREATE TABLE unit_of_measure (
    id         BINARY(16)   NOT NULL,
    code       VARCHAR(20)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    dimension  VARCHAR(20)  NOT NULL COMMENT 'MASS | VOLUME | OTHER; no conversions without verified parameters',
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at DATETIME(6)  NOT NULL,
    created_by BINARY(16)   NULL,
    updated_at DATETIME(6)  NOT NULL,
    updated_by BINARY(16)   NULL,
    version    BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_unit_code (code)
) ENGINE = InnoDB;

CREATE TABLE cargo_grade (
    id              BINARY(16)   NOT NULL,
    code            VARCHAR(20)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    category        VARCHAR(20)  NOT NULL COMMENT 'PETROLEUM | LPG | LNG | CHEMICAL | OTHER (D08)',
    default_unit_id BINARY(16)   NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      DATETIME(6)  NOT NULL,
    created_by      BINARY(16)   NULL,
    updated_at      DATETIME(6)  NOT NULL,
    updated_by      BINARY(16)   NULL,
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cargo_grade_code (code),
    CONSTRAINT fk_cargo_grade_unit FOREIGN KEY (default_unit_id) REFERENCES unit_of_measure (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE currency (
    id          BINARY(16)   NOT NULL,
    code        VARCHAR(20)  NOT NULL COMMENT 'ISO 4217 alphabetic code',
    name        VARCHAR(100) NOT NULL,
    minor_units INT          NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)  NOT NULL,
    created_by  BINARY(16)   NULL,
    updated_at  DATETIME(6)  NOT NULL,
    updated_by  BINARY(16)   NULL,
    version     BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_currency_code (code)
) ENGINE = InnoDB;

CREATE TABLE cost_head (
    id         BINARY(16)   NOT NULL,
    code       VARCHAR(20)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    kind       VARCHAR(20)  NOT NULL COMMENT 'EXPENSE | REVENUE | BOTH',
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at DATETIME(6)  NOT NULL,
    created_by BINARY(16)   NULL,
    updated_at DATETIME(6)  NOT NULL,
    updated_by BINARY(16)   NULL,
    version    BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cost_head_code (code)
) ENGINE = InnoDB;

CREATE TABLE tax_code (
    id             BINARY(16)    NOT NULL,
    code           VARCHAR(20)   NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    jurisdiction   VARCHAR(100)  NOT NULL,
    rate_percent   DECIMAL(7, 4) NOT NULL,
    effective_from DATE          NOT NULL,
    effective_to   DATE          NULL,
    active         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6)   NOT NULL,
    created_by     BINARY(16)    NULL,
    updated_at     DATETIME(6)   NOT NULL,
    updated_by     BINARY(16)    NULL,
    version        BIGINT        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tax_code_code (code),
    CONSTRAINT ck_tax_code_rate CHECK (rate_percent >= 0 AND rate_percent <= 100),
    CONSTRAINT ck_tax_code_period CHECK (effective_to IS NULL OR effective_to >= effective_from)
) ENGINE = InnoDB;

-- Exchange rates are never edited: a correction is a new rate; a wrong one is withdrawn (active = FALSE).
CREATE TABLE fx_rate (
    id            BINARY(16)     NOT NULL,
    from_currency CHAR(3)        NOT NULL,
    to_currency   CHAR(3)        NOT NULL,
    rate          DECIMAL(19, 8) NOT NULL,
    rate_date     DATE           NOT NULL,
    source        VARCHAR(100)   NOT NULL,
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6)    NOT NULL,
    created_by    BINARY(16)     NULL,
    updated_at    DATETIME(6)    NOT NULL,
    updated_by    BINARY(16)     NULL,
    version       BIGINT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fx_rate (from_currency, to_currency, rate_date, source),
    KEY ix_fx_rate_pair_date (from_currency, to_currency, rate_date),
    CONSTRAINT ck_fx_rate_positive CHECK (rate > 0),
    CONSTRAINT ck_fx_rate_pair CHECK (from_currency <> to_currency)
) ENGINE = InnoDB;

-- STS charging tariff (D05). Base block defaults to 48 h; 24 h and 72 h are the approved alternatives.
-- Increment, grace and rounding are left NULL until Ship2Ship confirms the contract terms (SRS §27).
CREATE TABLE tariff (
    id                        BINARY(16)     NOT NULL,
    legal_entity_id           BINARY(16)     NOT NULL,
    code                      VARCHAR(20)    NOT NULL,
    name                      VARCHAR(150)   NOT NULL,
    currency                  CHAR(3)        NOT NULL,
    base_hours                INT            NOT NULL DEFAULT 48,
    base_charge               DECIMAL(19, 4) NOT NULL,
    extra_hour_rate           DECIMAL(19, 4) NOT NULL,
    billing_increment_minutes INT            NULL,
    grace_minutes             INT            NULL,
    rounding_rule             VARCHAR(30)    NULL COMMENT 'NONE | ROUND_UP_TO_INCREMENT; NULL = pending confirmation',
    effective_from            DATE           NOT NULL,
    effective_to              DATE           NULL,
    notes                     VARCHAR(500)   NULL,
    active                    BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at                DATETIME(6)    NOT NULL,
    created_by                BINARY(16)     NULL,
    updated_at                DATETIME(6)    NOT NULL,
    updated_by                BINARY(16)     NULL,
    version                   BIGINT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tariff_entity_code_from (legal_entity_id, code, effective_from),
    KEY ix_tariff_entity_active (legal_entity_id, active, code),
    CONSTRAINT fk_tariff_legal_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity (id) ON DELETE RESTRICT,
    CONSTRAINT ck_tariff_base_hours CHECK (base_hours IN (24, 48, 72)),
    CONSTRAINT ck_tariff_amounts CHECK (base_charge >= 0 AND extra_hour_rate >= 0),
    CONSTRAINT ck_tariff_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_tariff_increment CHECK (billing_increment_minutes IS NULL OR billing_increment_minutes > 0),
    CONSTRAINT ck_tariff_grace CHECK (grace_minutes IS NULL OR grace_minutes >= 0)
) ENGINE = InnoDB;
