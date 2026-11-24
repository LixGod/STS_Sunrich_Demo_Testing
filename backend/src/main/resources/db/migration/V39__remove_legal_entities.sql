-- Ship-2-Ship is one company: legal entities are removed. Branches are the only organisation unit below the company,
-- a grant without a branch covers the whole company, and the company's name, address, tax number, base currency and
-- time zone come from configuration (ship2ship.company). V40 re-seals the audit chain without the dropped column.

-- Role grants: an entity-wide grant becomes company-wide.
ALTER TABLE user_role_scope DROP CHECK ck_urs_branch_needs_entity, DROP FOREIGN KEY fk_urs_legal_entity;
ALTER TABLE user_role_scope DROP INDEX fk_urs_legal_entity, DROP COLUMN legal_entity_id;

-- Numbering: one counter per series and year, one mask per series.
UPDATE number_series s
JOIN (SELECT series_type, period_year, MAX(next_value) next_value, MIN(id) keep_id FROM number_series GROUP BY series_type, period_year) m
  ON m.series_type = s.series_type AND m.period_year = s.period_year
SET s.next_value = m.next_value;
DELETE s FROM number_series s
JOIN (SELECT series_type, period_year, MIN(id) keep_id FROM number_series GROUP BY series_type, period_year) m
  ON m.series_type = s.series_type AND m.period_year = s.period_year
WHERE s.id <> m.keep_id;
ALTER TABLE number_series DROP FOREIGN KEY fk_number_series_entity;
ALTER TABLE number_series DROP INDEX uk_number_series, DROP INDEX fk_number_series_entity, DROP COLUMN legal_entity_id,
    ADD UNIQUE KEY uk_number_series (series_type, period_year);

DELETE FROM number_series_format WHERE legal_entity_id IS NOT NULL;
ALTER TABLE number_series_format DROP FOREIGN KEY fk_number_format_entity;
ALTER TABLE number_series_format DROP INDEX uk_number_series_format, DROP INDEX fk_number_format_entity,
    DROP COLUMN legal_entity_id, ADD UNIQUE KEY uk_number_series_format (series_type);
UPDATE number_series_format SET mask = REPLACE(mask, '{ENTITY}/', '');

-- Organisation.
ALTER TABLE branch DROP FOREIGN KEY fk_branch_legal_entity;
ALTER TABLE branch DROP INDEX uk_branch_entity_code, DROP INDEX ix_branch_entity_active_name, DROP COLUMN legal_entity_id,
    ADD UNIQUE KEY uk_branch_code (code), ADD KEY ix_branch_active_name (active, name);

-- Commercial and jobs.
ALTER TABLE enquiry DROP FOREIGN KEY fk_enquiry_entity;
ALTER TABLE enquiry DROP INDEX ix_enquiry_scope_status, DROP COLUMN legal_entity_id,
    ADD KEY ix_enquiry_scope_status (branch_id, status, received_at);
ALTER TABLE quotation DROP COLUMN legal_entity_id;
ALTER TABLE sts_job DROP FOREIGN KEY fk_sts_job_entity;
ALTER TABLE sts_job DROP INDEX ix_sts_job_scope_status, DROP COLUMN legal_entity_id,
    ADD KEY ix_sts_job_scope_status (branch_id, status, created_at);
ALTER TABLE tariff DROP FOREIGN KEY fk_tariff_legal_entity;
ALTER TABLE tariff DROP INDEX uk_tariff_entity_code_from, DROP INDEX ix_tariff_entity_active, DROP COLUMN legal_entity_id,
    ADD UNIQUE KEY uk_tariff_code_from (code, effective_from), ADD KEY ix_tariff_active (active, code);

-- Finance.
ALTER TABLE invoice DROP INDEX ix_invoice_scope, DROP INDEX uk_invoice_number, DROP COLUMN legal_entity_id,
    ADD KEY ix_invoice_scope (branch_id, status, invoice_date), ADD UNIQUE KEY uk_invoice_number (number);
ALTER TABLE receipt DROP INDEX ix_receipt_entity, DROP COLUMN legal_entity_id, ADD KEY ix_receipt_received (received_on);

-- Resources.
ALTER TABLE equipment_model DROP FOREIGN KEY fk_equipment_model_entity;
ALTER TABLE equipment_model DROP INDEX uk_equipment_model_entity_code, DROP INDEX ix_equipment_model_category,
    DROP COLUMN legal_entity_id, ADD UNIQUE KEY uk_equipment_model_code (code),
    ADD KEY ix_equipment_model_category (category_code, active);
ALTER TABLE equipment_asset DROP FOREIGN KEY fk_asset_entity;
ALTER TABLE equipment_asset DROP INDEX uk_asset_identifier_entity, DROP INDEX ix_asset_scope_status, DROP COLUMN legal_entity_id,
    ADD UNIQUE KEY uk_asset_identifier (asset_identifier), ADD KEY ix_asset_scope_status (branch_id, status, asset_identifier);
ALTER TABLE personnel DROP FOREIGN KEY fk_personnel_entity;
ALTER TABLE personnel DROP INDEX uk_personnel_entity_identifier, DROP INDEX ix_personnel_scope, DROP COLUMN legal_entity_id,
    ADD UNIQUE KEY uk_personnel_identifier (personnel_identifier), ADD KEY ix_personnel_active_name (active, full_name);
ALTER TABLE resource_vendor DROP FOREIGN KEY fk_vendor_entity;
ALTER TABLE resource_vendor DROP INDEX ix_vendor_scope, DROP COLUMN legal_entity_id, ADD KEY ix_vendor_active_name (active, name);
ALTER TABLE resource_craft DROP FOREIGN KEY fk_craft_entity;
ALTER TABLE resource_craft DROP INDEX uk_craft_entity_identifier, DROP INDEX ix_craft_scope, DROP COLUMN legal_entity_id,
    ADD UNIQUE KEY uk_craft_identifier (identifier), ADD KEY ix_craft_active_name (active, name);
ALTER TABLE job_resource_allocation DROP FOREIGN KEY fk_allocation_entity;
ALTER TABLE job_resource_allocation DROP INDEX fk_allocation_entity, DROP COLUMN legal_entity_id;
ALTER TABLE resource_certificate_requirement DROP FOREIGN KEY fk_req_entity;
ALTER TABLE resource_certificate_requirement DROP INDEX uk_cert_requirement, DROP COLUMN legal_entity_id,
    ADD UNIQUE KEY uk_cert_requirement (resource_kind, scope_code, certificate_type_code);

-- The certificate policy becomes a single company-wide row.
DELETE FROM resource_certificate_policy WHERE legal_entity_id <> (SELECT id FROM (
    SELECT legal_entity_id id FROM resource_certificate_policy ORDER BY updated_at DESC LIMIT 1) latest);
ALTER TABLE resource_certificate_policy DROP FOREIGN KEY fk_cert_policy_entity;
ALTER TABLE resource_certificate_policy DROP PRIMARY KEY, DROP COLUMN legal_entity_id,
    ADD COLUMN id TINYINT NOT NULL DEFAULT 1 FIRST, ADD PRIMARY KEY (id),
    ADD CONSTRAINT ck_cert_policy_single CHECK (id = 1);

-- Files and audit.
ALTER TABLE stored_file DROP COLUMN legal_entity_id;
ALTER TABLE audit_event DROP INDEX ix_audit_event_scope, DROP COLUMN legal_entity_id,
    ADD KEY ix_audit_event_scope (branch_id, seq);

DROP TABLE legal_entity;

UPDATE permission SET description = 'View branches and STS locations' WHERE code = 'MASTER.ORG.READ';
UPDATE permission SET description = 'Create and update branches and STS locations' WHERE code = 'MASTER.ORG.WRITE';
