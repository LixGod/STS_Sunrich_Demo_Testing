CREATE TABLE incoming_mail_attachment (
    id BINARY(16) NOT NULL PRIMARY KEY,
    mailbox_key CHAR(64) NOT NULL,
    message_uid BIGINT NOT NULL,
    attachment_no INT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    size_bytes INT NOT NULL,
    payload MEDIUMBLOB NOT NULL,
    sender VARCHAR(254) NULL,
    subject VARCHAR(500) NULL,
    received_at DATETIME(6) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    imported_document_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_incoming_mail_part (mailbox_key, message_uid, attachment_no),
    KEY ix_incoming_mail_status (status, created_at),
    CONSTRAINT fk_incoming_mail_document FOREIGN KEY (imported_document_id) REFERENCES document_version (id) ON DELETE RESTRICT
) ENGINE = InnoDB;
