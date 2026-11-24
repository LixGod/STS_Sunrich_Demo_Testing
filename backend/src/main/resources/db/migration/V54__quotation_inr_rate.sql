ALTER TABLE quotation_version
    ADD COLUMN inr_fx_rate DECIMAL(19,8) NULL,
    ADD COLUMN inr_fx_rate_date DATE NULL;
