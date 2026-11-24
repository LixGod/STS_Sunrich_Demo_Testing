ALTER TABLE branch_cost_default
    ADD COLUMN equipment_model_id BINARY(16) NULL,
    ADD CONSTRAINT fk_branch_cost_default_equipment FOREIGN KEY (equipment_model_id) REFERENCES equipment_model (id) ON DELETE RESTRICT;
