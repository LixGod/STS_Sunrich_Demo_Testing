CREATE TABLE quotation_pdf_revision (
    id BINARY(16) NOT NULL PRIMARY KEY,
    quotation_version_id BINARY(16) NOT NULL,
    pdf MEDIUMBLOB NOT NULL,
    pdf_inr MEDIUMBLOB NULL,
    pdf_sha256 CHAR(64) NOT NULL,
    replaced_at DATETIME(6) NOT NULL,
    replaced_by BINARY(16) NOT NULL,
    CONSTRAINT fk_quotation_pdf_revision_version FOREIGN KEY (quotation_version_id) REFERENCES quotation_version (id) ON DELETE RESTRICT,
    KEY ix_quotation_pdf_revision_version (quotation_version_id, replaced_at)
) ENGINE = InnoDB;
