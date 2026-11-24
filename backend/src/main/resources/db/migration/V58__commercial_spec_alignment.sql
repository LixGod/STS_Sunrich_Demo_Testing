-- Align commercial schema with AGS Shipping V1 spec:
-- 1. Optional received date and client on enquiry (can be added later).
-- 2. Optional tariff link on estimate (24/48/72h charging basis carried into quotation).
-- 3. Standard revenue cost heads for STS Service and Additional Tariff.

ALTER TABLE enquiry
    MODIFY COLUMN received_at DATETIME(6) NULL,
    MODIFY COLUMN client_id BINARY(16) NULL;

ALTER TABLE estimate
    ADD COLUMN tariff_id BINARY(16) NULL,
    ADD CONSTRAINT fk_estimate_tariff FOREIGN KEY (tariff_id) REFERENCES tariff (id) ON DELETE RESTRICT;

INSERT INTO cost_head (id, code, name, kind, active, created_at, updated_at, version)
SELECT UUID_TO_BIN('01920000-0000-7000-9000-000000000635'), 'STS-SERVICE', 'STS service charge', 'REVENUE', TRUE,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0
WHERE NOT EXISTS (SELECT 1 FROM cost_head WHERE code = 'STS-SERVICE');

INSERT INTO cost_head (id, code, name, kind, active, created_at, updated_at, version)
SELECT UUID_TO_BIN('01920000-0000-7000-9000-000000000637'), 'ADD-TARIFF', 'Additional tariff (24 / 48 / 72 hrs)', 'REVENUE', TRUE,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0
WHERE NOT EXISTS (SELECT 1 FROM cost_head WHERE code = 'ADD-TARIFF');
