ALTER TABLE quotation_version
    ADD COLUMN advance_received DECIMAL(19,4) NULL,
    ADD COLUMN advance_given DECIMAL(19,4) NULL;
