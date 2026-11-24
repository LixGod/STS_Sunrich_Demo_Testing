-- Phase 5a: client invoices and credit notes, receipts and their allocation (FR-J11, D06, D02, FR-UM-05). Numbers are
-- assigned at issue from the INVOICE / CREDIT_NOTE series (placeholder masks until Ship2Ship confirms them); an issued
-- document never changes; receipts are reversed, never deleted. Single currency per invoice and receipt.

CREATE TABLE invoice (
    id                  BINARY(16)     NOT NULL,
    legal_entity_id     BINARY(16)     NOT NULL,
    branch_id           BINARY(16)     NOT NULL,
    job_id              BINARY(16)     NOT NULL,
    client_id           BINARY(16)     NOT NULL,
    doc_type            VARCHAR(20)    NOT NULL COMMENT 'INVOICE | CREDIT_NOTE',
    credited_invoice_id BINARY(16)     NULL COMMENT 'The invoice a credit note corrects',
    number              VARCHAR(60)    NULL COMMENT 'Assigned at issue',
    status              VARCHAR(20)    NOT NULL COMMENT 'DRAFT | SUBMITTED | APPROVED | ISSUED',
    currency            CHAR(3)        NOT NULL,
    invoice_date        DATE           NOT NULL,
    due_date            DATE           NULL,
    billing_name        VARCHAR(200)   NOT NULL,
    billing_address     VARCHAR(1000)  NULL,
    payment_terms       VARCHAR(500)   NULL,
    notes               VARCHAR(2000)  NULL,
    net_amount          DECIMAL(19, 4) NOT NULL,
    tax_amount          DECIMAL(19, 4) NOT NULL,
    gross_amount        DECIMAL(19, 4) NOT NULL,
    submitted_by        BINARY(16)     NULL,
    submitted_at        DATETIME(6)    NULL,
    decided_by          BINARY(16)     NULL,
    decided_at          DATETIME(6)    NULL,
    returned_reason     VARCHAR(500)   NULL,
    issued_by           BINARY(16)     NULL,
    issued_at           DATETIME(6)    NULL,
    pdf                 MEDIUMBLOB     NULL,
    pdf_sha256          CHAR(64)       NULL,
    created_at          DATETIME(6)    NOT NULL,
    created_by          BINARY(16)     NULL,
    updated_at          DATETIME(6)    NOT NULL,
    updated_by          BINARY(16)     NULL,
    version             BIGINT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_number (legal_entity_id, number),
    KEY ix_invoice_job (job_id),
    KEY ix_invoice_client (client_id, status),
    KEY ix_invoice_scope (legal_entity_id, branch_id, status, invoice_date),
    CONSTRAINT fk_invoice_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_credited FOREIGN KEY (credited_invoice_id) REFERENCES invoice (id) ON DELETE RESTRICT,
    CONSTRAINT ck_invoice_no_self_approval CHECK (decided_by IS NULL OR submitted_by IS NULL OR decided_by <> submitted_by),
    CONSTRAINT ck_invoice_credit_note CHECK ((doc_type = 'CREDIT_NOTE') = (credited_invoice_id IS NOT NULL)),
    CONSTRAINT ck_invoice_amounts CHECK (net_amount >= 0 AND tax_amount >= 0 AND gross_amount >= 0)
) ENGINE = InnoDB;

CREATE TABLE invoice_line (
    id             BINARY(16)     NOT NULL,
    invoice_id     BINARY(16)     NOT NULL,
    line_no        INT            NOT NULL,
    source         VARCHAR(20)    NOT NULL COMMENT 'QUOTATION | MANUAL',
    cost_head_code VARCHAR(20)    NOT NULL,
    cost_head_name VARCHAR(100)   NOT NULL,
    description    VARCHAR(300)   NULL,
    quantity       DECIMAL(18, 3) NOT NULL,
    unit_price     DECIMAL(19, 4) NOT NULL,
    amount         DECIMAL(19, 4) NOT NULL,
    tax_code_id    BINARY(16)     NULL,
    tax_code       VARCHAR(20)    NULL,
    tax_rate       DECIMAL(7, 4)  NULL,
    tax_amount     DECIMAL(19, 4) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_invoice_line (invoice_id, line_no),
    CONSTRAINT fk_invoice_line_invoice FOREIGN KEY (invoice_id) REFERENCES invoice (id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoice_line_tax FOREIGN KEY (tax_code_id) REFERENCES tax_code (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE receipt (
    id              BINARY(16)     NOT NULL,
    legal_entity_id BINARY(16)     NOT NULL,
    client_id       BINARY(16)     NOT NULL,
    received_on     DATE           NOT NULL,
    amount          DECIMAL(19, 4) NOT NULL,
    currency        CHAR(3)        NOT NULL,
    method          VARCHAR(20)    NOT NULL COMMENT 'BANK_TRANSFER | OTHER',
    reference       VARCHAR(100)   NULL,
    status          VARCHAR(20)    NOT NULL COMMENT 'ACTIVE | REVERSED',
    reversal_reason VARCHAR(500)   NULL,
    reversed_by     BINARY(16)     NULL,
    reversed_at     DATETIME(6)    NULL,
    recorded_by     BINARY(16)     NOT NULL,
    recorded_at     DATETIME(6)    NOT NULL,
    version         BIGINT         NOT NULL,
    PRIMARY KEY (id),
    KEY ix_receipt_client (client_id, received_on),
    KEY ix_receipt_entity (legal_entity_id, received_on),
    CONSTRAINT ck_receipt_amount CHECK (amount > 0)
) ENGINE = InnoDB;

CREATE TABLE receipt_allocation (
    id           BINARY(16)     NOT NULL,
    receipt_id   BINARY(16)     NOT NULL,
    invoice_id   BINARY(16)     NOT NULL,
    amount       DECIMAL(19, 4) NOT NULL,
    allocated_by BINARY(16)     NOT NULL,
    allocated_at DATETIME(6)    NOT NULL,
    reversed_at  DATETIME(6)    NULL,
    PRIMARY KEY (id),
    KEY ix_receipt_allocation_invoice (invoice_id),
    KEY ix_receipt_allocation_receipt (receipt_id),
    CONSTRAINT fk_receipt_allocation_receipt FOREIGN KEY (receipt_id) REFERENCES receipt (id) ON DELETE RESTRICT,
    CONSTRAINT fk_receipt_allocation_invoice FOREIGN KEY (invoice_id) REFERENCES invoice (id) ON DELETE RESTRICT,
    CONSTRAINT ck_receipt_allocation_amount CHECK (amount > 0)
) ENGINE = InnoDB;

-- Placeholder numbering masks until Ship2Ship confirms them (owner question 1, D02).
INSERT INTO number_series_format (id, series_type, legal_entity_id, mask) VALUES
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000105'), 'INVOICE', NULL, 'INV/{ENTITY}/{YYYY}/{SEQ:5}'),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000106'), 'CREDIT_NOTE', NULL, 'CN/{ENTITY}/{YYYY}/{SEQ:5}');

INSERT INTO permission (code, module, description) VALUES
    ('FIN.INVOICE.READ',    'FINANCE', 'View invoices, credit notes and receivables within scope'),
    ('FIN.INVOICE.WRITE',   'FINANCE', 'Prepare, submit and issue invoices and credit notes within scope'),
    ('FIN.INVOICE.APPROVE', 'FINANCE', 'Approve invoices and credit notes prepared by someone else'),
    ('FIN.RECEIPT.READ',    'FINANCE', 'View receipts within scope'),
    ('FIN.RECEIPT.WRITE',   'FINANCE', 'Record, allocate and reverse receipts within scope');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('FIN.INVOICE.READ', 'FIN.INVOICE.WRITE', 'FIN.INVOICE.APPROVE', 'FIN.RECEIPT.READ',
    'FIN.RECEIPT.WRITE') WHERE r.code IN ('SYSTEM_ADMIN', 'FINANCE');

-- Baseline mapping, to be confirmed by Ship2Ship (SRS §27).
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('FIN.INVOICE.READ', 'FIN.INVOICE.APPROVE', 'FIN.RECEIPT.READ')
WHERE r.code = 'MANAGEMENT_APPROVER';

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'FIN.INVOICE.READ' FROM role r WHERE r.code IN ('COMMERCIAL', 'AUDITOR');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'FIN.RECEIPT.READ' FROM role r WHERE r.code = 'AUDITOR';
