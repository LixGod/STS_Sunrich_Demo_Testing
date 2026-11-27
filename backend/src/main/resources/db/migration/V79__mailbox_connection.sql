CREATE TABLE mailbox_connection (
    id TINYINT NOT NULL PRIMARY KEY,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    username VARCHAR(254) NOT NULL,
    folder VARCHAR(100) NOT NULL,
    password_cipher VARBINARY(4096) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_mailbox_connection_singleton CHECK (id = 1)
) ENGINE = InnoDB;
