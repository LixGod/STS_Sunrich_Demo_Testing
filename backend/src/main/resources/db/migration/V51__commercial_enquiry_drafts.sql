-- Incomplete enquiries stay separate from numbered records and their branch-scoped workflow.
CREATE TABLE commercial_enquiry_draft (
    id BINARY(16) NOT NULL PRIMARY KEY,
    owner_id BINARY(16) NOT NULL,
    payload LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL,
    KEY ix_commercial_enquiry_draft_owner (owner_id, updated_at),
    CONSTRAINT fk_commercial_enquiry_draft_owner FOREIGN KEY (owner_id) REFERENCES app_user(id)
) ENGINE = InnoDB;
