CREATE TABLE client_email (
    client_id BINARY(16) NOT NULL,
    position INT NOT NULL,
    email VARCHAR(254) NOT NULL,
    PRIMARY KEY (client_id, position),
    CONSTRAINT fk_client_email_client FOREIGN KEY (client_id) REFERENCES client (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE client_phone (
    client_id BINARY(16) NOT NULL,
    position INT NOT NULL,
    phone VARCHAR(40) NOT NULL,
    PRIMARY KEY (client_id, position),
    CONSTRAINT fk_client_phone_client FOREIGN KEY (client_id) REFERENCES client (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

INSERT INTO client_phone (client_id, position, phone)
SELECT id, 0, TRIM(phone) FROM client WHERE phone IS NOT NULL AND TRIM(phone) <> '';

INSERT INTO client_email (client_id, position, email)
SELECT c.id, 0, LOWER(TRIM(cc.email)) FROM client c
JOIN client_contact cc ON cc.client_id = c.id
WHERE c.code LIKE 'CL-%' AND cc.is_primary = TRUE AND cc.full_name = c.legal_name
  AND cc.email IS NOT NULL AND TRIM(cc.email) <> '';
