INSERT INTO cost_head (id, code, name, kind, active, created_at, updated_at, version)
SELECT UUID_TO_BIN('01920000-0000-7000-9000-000000000636'), 'SPECIAL-REQ', 'Paid special requirement', 'REVENUE', TRUE,
       UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0
WHERE NOT EXISTS (SELECT 1 FROM cost_head WHERE code = 'SPECIAL-REQ');

CREATE TABLE enquiry_special_requirement (
    id BINARY(16) NOT NULL PRIMARY KEY,
    enquiry_id BINARY(16) NOT NULL,
    line_no INT NOT NULL,
    description VARCHAR(300) NOT NULL,
    paid BOOLEAN NOT NULL DEFAULT FALSE,
    sale_price_usd DECIMAL(19,4) NULL,
    UNIQUE KEY uk_enquiry_special_requirement_line (enquiry_id, line_no),
    CONSTRAINT fk_enquiry_special_requirement_enquiry FOREIGN KEY (enquiry_id) REFERENCES enquiry(id)
) ENGINE = InnoDB;
