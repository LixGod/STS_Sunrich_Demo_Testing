CREATE TABLE user_permission_override (
    user_id BINARY(16) NOT NULL,
    permission_code VARCHAR(80) NOT NULL,
    allowed BOOLEAN NOT NULL,
    PRIMARY KEY (user_id, permission_code),
    CONSTRAINT fk_user_permission_override_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_user_permission_override_permission FOREIGN KEY (permission_code) REFERENCES permission(code)
) ENGINE = InnoDB;
