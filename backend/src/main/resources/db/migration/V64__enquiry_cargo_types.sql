CREATE TABLE enquiry_cargo_type (
    enquiry_id BINARY(16) NOT NULL,
    cargo_type VARCHAR(20) NOT NULL,
    PRIMARY KEY (enquiry_id, cargo_type),
    CONSTRAINT fk_enquiry_cargo_type_enquiry FOREIGN KEY (enquiry_id) REFERENCES enquiry (id) ON DELETE CASCADE
) ENGINE = InnoDB;

INSERT INTO enquiry_cargo_type (enquiry_id, cargo_type)
SELECT id, operation_type FROM enquiry WHERE operation_type IS NOT NULL;
