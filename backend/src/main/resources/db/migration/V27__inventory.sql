-- Inventory of fenders, hoses and other equipment: damaged and lost units leave the usable stock, and estimate lines
-- can pick an equipment model whose category cost head and optional standard rate fill the line.
ALTER TABLE equipment_asset DROP CHECK ck_asset_status;
ALTER TABLE equipment_asset ADD CONSTRAINT ck_asset_status
    CHECK (status IN ('AVAILABLE','MAINTENANCE','DAMAGED','LOST','RETIRED'));

ALTER TABLE resource_category ADD COLUMN cost_head_id BINARY(16) NULL,
    ADD CONSTRAINT fk_resource_category_cost_head FOREIGN KEY (cost_head_id) REFERENCES cost_head(id) ON DELETE RESTRICT;

ALTER TABLE equipment_model ADD COLUMN standard_rate DECIMAL(19,4) NULL, ADD COLUMN standard_rate_currency CHAR(3) NULL,
    ADD CONSTRAINT ck_equipment_model_rate CHECK ((standard_rate IS NULL AND standard_rate_currency IS NULL)
        OR (standard_rate >= 0 AND standard_rate_currency IS NOT NULL));

ALTER TABLE estimate_line ADD COLUMN equipment_model_id BINARY(16) NULL,
    ADD CONSTRAINT fk_estimate_line_equipment FOREIGN KEY (equipment_model_id) REFERENCES equipment_model(id) ON DELETE RESTRICT;
