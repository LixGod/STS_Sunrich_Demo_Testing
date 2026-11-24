CREATE TABLE quotation_advance_payment (
    id                   BINARY(16)     NOT NULL,
    quotation_version_id BINARY(16)     NOT NULL,
    amount               DECIMAL(19, 4) NOT NULL,
    note                 VARCHAR(500)   NULL,
    recorded_by          BINARY(16)     NOT NULL,
    recorded_at          DATETIME(6)    NOT NULL,
    PRIMARY KEY (id),
    KEY ix_quotation_advance_version (quotation_version_id, recorded_at),
    CONSTRAINT fk_quotation_advance_version FOREIGN KEY (quotation_version_id) REFERENCES quotation_version (id) ON DELETE RESTRICT,
    CONSTRAINT ck_quotation_advance_amount CHECK (amount > 0)
) ENGINE = InnoDB;
