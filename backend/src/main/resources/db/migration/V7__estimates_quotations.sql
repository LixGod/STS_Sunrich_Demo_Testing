-- Phase 2b: versioned cost estimates (FR-J02) and quotations with approval, issue and PDF (FR-J03).
-- Money is DECIMAL(19, 4) in the estimate's currency; totals are always recalculated by the server.

CREATE TABLE estimate (
    id            BINARY(16)     NOT NULL,
    enquiry_id    BINARY(16)     NOT NULL,
    version_no    INT            NOT NULL,
    currency      CHAR(3)        NOT NULL,
    notes         VARCHAR(2000)  NULL,
    total_cost    DECIMAL(19, 4) NOT NULL,
    total_revenue DECIMAL(19, 4) NOT NULL,
    locked        BOOLEAN        NOT NULL DEFAULT FALSE COMMENT 'TRUE once a quotation version was made from it',
    created_at    DATETIME(6)    NOT NULL,
    created_by    BINARY(16)     NULL,
    updated_at    DATETIME(6)    NOT NULL,
    updated_by    BINARY(16)     NULL,
    version       BIGINT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_estimate_version (enquiry_id, version_no),
    CONSTRAINT fk_estimate_enquiry FOREIGN KEY (enquiry_id) REFERENCES enquiry (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE estimate_line (
    id           BINARY(16)     NOT NULL,
    estimate_id  BINARY(16)     NOT NULL,
    line_no      INT            NOT NULL,
    kind         VARCHAR(10)    NOT NULL COMMENT 'COST | REVENUE',
    cost_head_id BINARY(16)     NOT NULL,
    description  VARCHAR(300)   NULL,
    quantity     DECIMAL(18, 3) NOT NULL,
    unit_price   DECIMAL(19, 4) NOT NULL,
    amount       DECIMAL(19, 4) NOT NULL COMMENT 'quantity x unit_price, rounded half-up to 4 decimals',
    PRIMARY KEY (id),
    UNIQUE KEY uk_estimate_line (estimate_id, line_no),
    CONSTRAINT fk_estimate_line_estimate FOREIGN KEY (estimate_id) REFERENCES estimate (id) ON DELETE RESTRICT,
    CONSTRAINT fk_estimate_line_cost_head FOREIGN KEY (cost_head_id) REFERENCES cost_head (id) ON DELETE RESTRICT,
    CONSTRAINT ck_estimate_line_quantity CHECK (quantity > 0),
    CONSTRAINT ck_estimate_line_price CHECK (unit_price >= 0)
) ENGINE = InnoDB;

-- One quotation per enquiry; each revision is a version with its own approval and issue.
CREATE TABLE quotation (
    id              BINARY(16)  NOT NULL,
    enquiry_id      BINARY(16)  NOT NULL,
    reference       VARCHAR(40) NOT NULL,
    legal_entity_id BINARY(16)  NOT NULL,
    branch_id       BINARY(16)  NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    created_by      BINARY(16)  NULL,
    updated_at      DATETIME(6) NOT NULL,
    updated_by      BINARY(16)  NULL,
    version         BIGINT      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quotation_enquiry (enquiry_id),
    UNIQUE KEY uk_quotation_reference (reference),
    CONSTRAINT fk_quotation_enquiry FOREIGN KEY (enquiry_id) REFERENCES enquiry (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE quotation_version (
    id                  BINARY(16)     NOT NULL,
    quotation_id        BINARY(16)     NOT NULL,
    version_no          INT            NOT NULL,
    status              VARCHAR(20)    NOT NULL COMMENT 'DRAFT | PENDING_APPROVAL | APPROVED | REJECTED | ISSUED | SUPERSEDED | ACCEPTED',
    estimate_id         BINARY(16)     NOT NULL,
    currency            CHAR(3)        NOT NULL,
    total_amount        DECIMAL(19, 4) NOT NULL,
    valid_until         DATE           NOT NULL,
    payment_terms       VARCHAR(500)   NULL,
    terms_text          TEXT           NULL,
    tariff_id           BINARY(16)     NULL COMMENT 'Charging basis quoted (BR-48H); frozen on the job at acceptance (AR-02)',
    submitted_by        BINARY(16)     NULL,
    submitted_at        DATETIME(6)    NULL,
    decided_by          BINARY(16)     NULL,
    decided_at          DATETIME(6)    NULL,
    decision_reason     VARCHAR(500)   NULL,
    issued_by           BINARY(16)     NULL,
    issued_at           DATETIME(6)    NULL,
    issued_to           VARCHAR(300)   NULL,
    issue_channel       VARCHAR(20)    NULL COMMENT 'EMAIL | OTHER (recorded manually until the Gmail adapter exists)',
    issue_note          VARCHAR(500)   NULL,
    pdf                 MEDIUMBLOB     NULL COMMENT 'Issued PDF, immutable; moves to document storage in Phase 3',
    pdf_sha256          CHAR(64)       NULL,
    created_at          DATETIME(6)    NOT NULL,
    created_by          BINARY(16)     NULL,
    updated_at          DATETIME(6)    NOT NULL,
    updated_by          BINARY(16)     NULL,
    version             BIGINT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quotation_version (quotation_id, version_no),
    KEY ix_quotation_version_status (status, submitted_at),
    CONSTRAINT fk_quotation_version_quotation FOREIGN KEY (quotation_id) REFERENCES quotation (id) ON DELETE RESTRICT,
    CONSTRAINT fk_quotation_version_estimate FOREIGN KEY (estimate_id) REFERENCES estimate (id) ON DELETE RESTRICT,
    CONSTRAINT fk_quotation_version_tariff FOREIGN KEY (tariff_id) REFERENCES tariff (id) ON DELETE RESTRICT,
    CONSTRAINT ck_quotation_version_no_self_approval CHECK (decided_by IS NULL OR submitted_by IS NULL OR decided_by <> submitted_by)
) ENGINE = InnoDB;

-- The revenue lines quoted to the client, copied from the estimate when the version was created.
CREATE TABLE quotation_line (
    id                   BINARY(16)     NOT NULL,
    quotation_version_id BINARY(16)     NOT NULL,
    line_no              INT            NOT NULL,
    cost_head_code       VARCHAR(20)    NOT NULL,
    cost_head_name       VARCHAR(100)   NOT NULL,
    description          VARCHAR(300)   NULL,
    quantity             DECIMAL(18, 3) NOT NULL,
    unit_price           DECIMAL(19, 4) NOT NULL,
    amount               DECIMAL(19, 4) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_quotation_line (quotation_version_id, line_no),
    CONSTRAINT fk_quotation_line_version FOREIGN KEY (quotation_version_id) REFERENCES quotation_version (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

INSERT INTO number_series_format (id, series_type, legal_entity_id, mask)
VALUES (UUID_TO_BIN('01920000-0000-7000-8000-000000000102'), 'QUOTATION', NULL, 'QUO/{ENTITY}/{YYYY}/{SEQ:5}');

INSERT INTO permission (code, module, description) VALUES
    ('COMMERCIAL.ESTIMATE.READ',    'COMMERCIAL', 'View cost estimates, including costs and margin, within scope'),
    ('COMMERCIAL.ESTIMATE.WRITE',   'COMMERCIAL', 'Prepare cost estimates within scope'),
    ('COMMERCIAL.QUOTATION.READ',   'COMMERCIAL', 'View quotations within scope'),
    ('COMMERCIAL.QUOTATION.WRITE',  'COMMERCIAL', 'Prepare, submit, issue quotations and record owner acceptance within scope'),
    ('COMMERCIAL.QUOTATION.APPROVE','COMMERCIAL', 'Approve or reject quotations prepared by someone else');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r
JOIN permission p ON p.code IN ('COMMERCIAL.ESTIMATE.READ', 'COMMERCIAL.ESTIMATE.WRITE', 'COMMERCIAL.QUOTATION.READ',
                                'COMMERCIAL.QUOTATION.WRITE', 'COMMERCIAL.QUOTATION.APPROVE')
WHERE r.code = 'SYSTEM_ADMIN';

-- Baseline mapping, to be confirmed by Ship2Ship (SRS §27). Authority limits by amount are pending.
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, x.code FROM role r JOIN (
    SELECT 'COMMERCIAL' AS role_code, 'COMMERCIAL.ESTIMATE.READ' AS code UNION ALL
    SELECT 'COMMERCIAL', 'COMMERCIAL.ESTIMATE.WRITE' UNION ALL
    SELECT 'COMMERCIAL', 'COMMERCIAL.QUOTATION.READ' UNION ALL
    SELECT 'COMMERCIAL', 'COMMERCIAL.QUOTATION.WRITE' UNION ALL
    SELECT 'MANAGEMENT_APPROVER', 'COMMERCIAL.ESTIMATE.READ' UNION ALL
    SELECT 'MANAGEMENT_APPROVER', 'COMMERCIAL.QUOTATION.READ' UNION ALL
    SELECT 'MANAGEMENT_APPROVER', 'COMMERCIAL.QUOTATION.APPROVE' UNION ALL
    SELECT 'FINANCE', 'COMMERCIAL.ESTIMATE.READ' UNION ALL
    SELECT 'FINANCE', 'COMMERCIAL.QUOTATION.READ' UNION ALL
    SELECT 'AUDITOR', 'COMMERCIAL.ESTIMATE.READ' UNION ALL
    SELECT 'AUDITOR', 'COMMERCIAL.QUOTATION.READ'
) x ON x.role_code = r.code;
