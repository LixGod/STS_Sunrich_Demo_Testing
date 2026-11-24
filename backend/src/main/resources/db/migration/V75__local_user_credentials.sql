CREATE TABLE user_local_credential (
    user_id BINARY(16) NOT NULL PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    CONSTRAINT fk_user_local_credential_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
