-- LOCAL DEVELOPMENT ONLY. Fictitious craft and certificate data for testing job movements.
INSERT IGNORE INTO resource_vendor
    (id,name,contacts,contract_from,contract_to,active,created_at,updated_at,version)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000901'),
        'DEMO Marine Services (synthetic)',NULL,NULL,NULL,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0);

INSERT IGNORE INTO resource_craft
    (id,vendor_id,identifier,name,craft_type,home_location,active,created_at,updated_at,version)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000902'),
        UUID_TO_BIN('01920000-0000-7000-9000-000000000901'),
        'DEMO-TUG-01','DEMO Tug 01 (synthetic)','TUG','Demo Mumbai',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0);

INSERT IGNORE INTO resource_certificate_type (code,label,active,updated_at,version)
VALUES ('DEMO_CRAFT_CERT','DEMO craft certificate (synthetic)',TRUE,UTC_TIMESTAMP(6),0);

INSERT IGNORE INTO resource_certificate_requirement
    (id,resource_kind,scope_code,certificate_type_code,active,created_at,updated_at,version)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000903'),
        'CRAFT','TUG','DEMO_CRAFT_CERT',TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0);

INSERT IGNORE INTO resource_certificate_policy
    (mode,warning_days,requirements_configured,updated_at,version)
VALUES ('BLOCK',30,TRUE,UTC_TIMESTAMP(6),0);

INSERT IGNORE INTO resource_certificate
    (id,craft_id,type_code,certificate_number,issuer,issue_date,expiry_date,created_at,updated_at,version)
VALUES (UUID_TO_BIN('01920000-0000-7000-9000-000000000904'),
        UUID_TO_BIN('01920000-0000-7000-9000-000000000902'),
        'DEMO_CRAFT_CERT','DEMO-ONLY-001','Synthetic test issuer','2026-01-01','2030-12-31',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0);
