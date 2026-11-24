CREATE TABLE branch_cost_default (
    id BINARY(16) NOT NULL PRIMARY KEY,
    branch_id BINARY(16) NOT NULL,
    cost_head_id BINARY(16) NOT NULL,
    description VARCHAR(300) NULL,
    quantity DECIMAL(18,3) NOT NULL,
    unit_price DECIMAL(19,4) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL,
    KEY ix_branch_cost_default_branch (branch_id, active),
    CONSTRAINT fk_branch_cost_default_branch FOREIGN KEY (branch_id) REFERENCES branch(id),
    CONSTRAINT fk_branch_cost_default_head FOREIGN KEY (cost_head_id) REFERENCES cost_head(id)
) ENGINE = InnoDB;
