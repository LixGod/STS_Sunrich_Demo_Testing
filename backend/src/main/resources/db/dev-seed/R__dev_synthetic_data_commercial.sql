-- LOCAL DEVELOPMENT / TEST ONLY. Synthetic commercial data (SRS D14). Names marked DEMO are fictitious;
-- the IMO numbers use the unassigned 1000xxx range with valid check digits. Rates are placeholders, not tariffs.

INSERT IGNORE INTO app_user (id, external_subject_id, full_name, work_email, department, position, status, language,
                             mfa_enabled, created_at, updated_at, version)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000404'), 'dev-commercial', 'Demo Commercial Executive',
        'commercial@demo.invalid', 'Commercial', 'Commercial Executive', 'ACTIVE', 'en', FALSE, UTC_TIMESTAMP(6),
        UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO user_role_scope (id, user_id, role_id, branch_id, valid_from, valid_to, created_at)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000504'), UUID_TO_BIN('01920000-0000-7000-9000-000000000404'),
        UUID_TO_BIN('01920000-0000-7000-8000-000000000003'),
        NULL, '2026-01-01', NULL, UTC_TIMESTAMP(6));

INSERT IGNORE INTO unit_of_measure (id, code, name, dimension, active, created_at, updated_at, version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000601'), 'MT', 'Metric tonne', 'MASS', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000602'), 'M3', 'Cubic metre', 'VOLUME', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO cargo_grade (id, code, name, category, default_unit_id, active, created_at, updated_at, version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000611'), 'PROPANE', 'Propane', 'LPG', UUID_TO_BIN('01920000-0000-7000-9000-000000000601'), TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000612'), 'BUTANE', 'Butane', 'LPG', UUID_TO_BIN('01920000-0000-7000-9000-000000000601'), TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000613'), 'NAPHTHA', 'Naphtha', 'PETROLEUM', UUID_TO_BIN('01920000-0000-7000-9000-000000000601'), TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO currency (id, code, name, minor_units, active, created_at, updated_at, version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000621'), 'USD', 'US dollar', 2, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000622'), 'INR', 'Indian rupee', 2, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO cost_head (id, code, name, kind, active, created_at, updated_at, version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000631'), 'FENDERS', 'Fenders', 'EXPENSE', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000632'), 'HOSES', 'Cargo hoses', 'EXPENSE', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000633'), 'TUGS', 'Tugboats', 'EXPENSE', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000634'), 'POAC', 'Mooring Master / POAC', 'EXPENSE', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000635'), 'STS-SERVICE', 'STS service charge', 'REVENUE', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

-- Synthetic local inventory gives estimate costing usable fender and hose options; rates are USD placeholders.
UPDATE resource_category SET cost_head_id=UUID_TO_BIN('01920000-0000-7000-9000-000000000631'), updated_at=UTC_TIMESTAMP(6)
WHERE code IN ('PRIMARY_FENDER','SECONDARY_FENDER') AND cost_head_id IS NULL;
UPDATE resource_category SET cost_head_id=UUID_TO_BIN('01920000-0000-7000-9000-000000000632'), updated_at=UTC_TIMESTAMP(6)
WHERE code IN ('CARGO_HOSE','VAPOUR_HOSE') AND cost_head_id IS NULL;

INSERT IGNORE INTO equipment_model (id,category_code,code,name,active,standard_rate,standard_rate_currency,
                                    created_at,updated_at,version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000801'), 'PRIMARY_FENDER', 'DEMO-PF-33', 'DEMO Pneumatic fender 3.3 m (synthetic)', TRUE, 1250.0000, 'USD', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000802'), 'SECONDARY_FENDER', 'DEMO-SF-15', 'DEMO Secondary fender 1.5 m (synthetic)', TRUE, 420.0000, 'USD', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000803'), 'CARGO_HOSE', 'DEMO-CH-10', 'DEMO Cargo hose 10 inch (synthetic)', TRUE, 340.0000, 'USD', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000804'), 'VAPOUR_HOSE', 'DEMO-VH-10', 'DEMO Vapour hose 10 inch (synthetic)', TRUE, 380.0000, 'USD', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO equipment_asset (id,model_id,asset_identifier,status,created_at,updated_at,version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000811'), UUID_TO_BIN('01920000-0000-7000-9000-000000000801'), 'DEMO-PF-33-01', 'AVAILABLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000812'), UUID_TO_BIN('01920000-0000-7000-9000-000000000801'), 'DEMO-PF-33-02', 'AVAILABLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000813'), UUID_TO_BIN('01920000-0000-7000-9000-000000000801'), 'DEMO-PF-33-03', 'AVAILABLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000814'), UUID_TO_BIN('01920000-0000-7000-9000-000000000802'), 'DEMO-SF-15-01', 'AVAILABLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000815'), UUID_TO_BIN('01920000-0000-7000-9000-000000000802'), 'DEMO-SF-15-02', 'AVAILABLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000816'), UUID_TO_BIN('01920000-0000-7000-9000-000000000803'), 'DEMO-CH-10-01', 'AVAILABLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000817'), UUID_TO_BIN('01920000-0000-7000-9000-000000000803'), 'DEMO-CH-10-02', 'AVAILABLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000818'), UUID_TO_BIN('01920000-0000-7000-9000-000000000804'), 'DEMO-VH-10-01', 'AVAILABLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000819'), UUID_TO_BIN('01920000-0000-7000-9000-000000000804'), 'DEMO-VH-10-02', 'AVAILABLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO client (id, code, legal_name, tax_registration_no, billing_address, country_code, default_currency,
                           payment_terms, active, created_at, updated_at, version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000701'), 'DEMO-CHART', 'DEMO Charterers Ltd (Synthetic)', NULL,
     'Synthetic address', 'IN', 'USD', 'Synthetic: 30 days from invoice', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000702'), 'DEMO-OWNER', 'DEMO Shipowners Inc (Synthetic)', NULL,
     'Synthetic address', 'AE', 'USD', NULL, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

UPDATE client SET default_currency='USD', updated_at=UTC_TIMESTAMP(6)
WHERE code IN ('DEMO-CHART','DEMO-OWNER');

INSERT IGNORE INTO client_role (client_id, role) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000701'), 'CHARTERER'),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000702'), 'OWNER');

INSERT IGNORE INTO client_contact (id, client_id, full_name, position, email, phone, is_primary, active, created_at,
                                   updated_at, version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000711'), UUID_TO_BIN('01920000-0000-7000-9000-000000000701'),
     'Demo Chartering Desk', 'Chartering', 'chartering@demo.invalid', NULL, TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO vessel (id, imo, name, former_names, vessel_type, flag_state, loa_m, beam_m, dwt_t,
                           particulars_status, active, created_at, updated_at, version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000721'), '1000007', 'DEMO MOTHER', NULL, 'LPG_CARRIER', NULL, 230.00, 36.60, NULL, 'UNVERIFIED', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000722'), '1000019', 'DEMO DAUGHTER', NULL, 'LPG_CARRIER', NULL, 180.00, 30.00, NULL, 'UNVERIFIED', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000723'), '1000021', 'DEMO TANKER', NULL, 'PRODUCT_TANKER', NULL, NULL, NULL, NULL, 'UNVERIFIED', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO tariff (id, code, name, currency, base_hours, base_charge, extra_hour_rate,
                           billing_increment_minutes, grace_minutes, rounding_rule, effective_from, effective_to, notes,
                           active, created_at, updated_at, version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000731'),
     'DEMO-STS', 'DEMO standard STS service (synthetic rates)', 'USD', 48, 10000.0000, 250.0000, NULL, NULL, NULL,
     '2026-01-01', NULL, 'Synthetic placeholder values for development only', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-9000-000000000732'),
     'DEMO-STS-INR', 'DEMO standard STS service INR (synthetic rates)', 'INR', 48, 850000.0000, 20000.0000, NULL, NULL, NULL,
     '2026-01-01', NULL, 'Synthetic placeholder values for development only', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

-- Management approver for quotations (no self-approval: someone other than the commercial user must decide).
INSERT IGNORE INTO app_user (id, external_subject_id, full_name, work_email, department, position, status, language,
                             mfa_enabled, created_at, updated_at, version)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000405'), 'dev-approver', 'Demo Management Approver',
        'approver@demo.invalid', 'Management', 'General Manager', 'ACTIVE', 'en', FALSE, UTC_TIMESTAMP(6),
        UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO user_role_scope (id, user_id, role_id, branch_id, valid_from, valid_to, created_at)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000505'), UUID_TO_BIN('01920000-0000-7000-9000-000000000405'),
        UUID_TO_BIN('01920000-0000-7000-8000-000000000002'),
        NULL, '2026-01-01', NULL, UTC_TIMESTAMP(6));

-- Technical officers: one enters vessel particulars, another verifies them (no self-verification).
INSERT IGNORE INTO app_user (id, external_subject_id, full_name, work_email, department, position, status, language,
                             mfa_enabled, created_at, updated_at, version)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000406'), 'dev-technical', 'Demo Technical Officer',
        'technical@demo.invalid', 'Technical', 'Technical Officer', 'ACTIVE', 'en', FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
       (UUID_TO_BIN('01920000-0000-7000-9000-000000000407'), 'dev-marine', 'Demo Marine Superintendent',
        'marine@demo.invalid', 'Technical', 'Marine Superintendent', 'ACTIVE', 'en', FALSE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT IGNORE INTO user_role_scope (id, user_id, role_id, branch_id, valid_from, valid_to, created_at)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000506'), UUID_TO_BIN('01920000-0000-7000-9000-000000000406'),
        UUID_TO_BIN('01920000-0000-7000-8000-000000000005'),
        NULL, '2026-01-01', NULL, UTC_TIMESTAMP(6)),
       (UUID_TO_BIN('01920000-0000-7000-9000-000000000507'), UUID_TO_BIN('01920000-0000-7000-9000-000000000407'),
        UUID_TO_BIN('01920000-0000-7000-8000-000000000005'),
        NULL, '2026-01-01', NULL, UTC_TIMESTAMP(6));
