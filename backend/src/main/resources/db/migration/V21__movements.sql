-- Phase 4c: independent craft trips, designated charge clock and recovery.
CREATE TABLE craft_movement (
    id BINARY(16) NOT NULL PRIMARY KEY, job_id BINARY(16) NOT NULL,
    craft_id BINARY(16) NOT NULL, vendor_id BINARY(16) NOT NULL,
    departed_at DATETIME(6) NOT NULL, returned_at DATETIME(6) NULL, time_zone VARCHAR(64) NOT NULL,
    service_notes TEXT NULL, standby_notes TEXT NULL, delay_notes TEXT NULL, damage_notes TEXT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    pending_action VARCHAR(10) NULL, pending_json TEXT NULL, pending_reason VARCHAR(500) NULL,
    pending_by BINARY(16) NULL, approved_by BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL, created_by BINARY(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    KEY ix_movement_job (job_id, departed_at), KEY ix_movement_craft (craft_id, departed_at),
    CONSTRAINT fk_movement_job FOREIGN KEY (job_id) REFERENCES sts_job(id) ON DELETE RESTRICT,
    CONSTRAINT fk_movement_craft FOREIGN KEY (craft_id) REFERENCES resource_craft(id) ON DELETE RESTRICT,
    CONSTRAINT fk_movement_vendor FOREIGN KEY (vendor_id) REFERENCES resource_vendor(id) ON DELETE RESTRICT,
    CONSTRAINT ck_movement_time CHECK (returned_at IS NULL OR returned_at>departed_at),
    CONSTRAINT ck_movement_status CHECK (status IN ('ACTIVE','VOIDED')),
    CONSTRAINT ck_movement_pending CHECK (pending_action IS NULL OR pending_action IN ('CORRECT','VOID')),
    CONSTRAINT ck_movement_second_person CHECK (approved_by IS NULL OR pending_by IS NULL OR approved_by<>pending_by)
) ENGINE=InnoDB;

CREATE TABLE movement_asset (
    movement_id BINARY(16) NOT NULL, allocation_id BINARY(16) NOT NULL, asset_id BINARY(16) NOT NULL,
    identifier VARCHAR(100) NOT NULL, category_code VARCHAR(40) NOT NULL, status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (movement_id,allocation_id), KEY ix_movement_asset_allocation (allocation_id),
    CONSTRAINT fk_movement_asset_movement FOREIGN KEY (movement_id) REFERENCES craft_movement(id) ON DELETE RESTRICT,
    CONSTRAINT fk_movement_asset_allocation FOREIGN KEY (allocation_id) REFERENCES job_resource_allocation(id) ON DELETE RESTRICT,
    CONSTRAINT fk_movement_asset_asset FOREIGN KEY (asset_id) REFERENCES equipment_asset(id) ON DELETE RESTRICT,
    CONSTRAINT ck_movement_asset_status CHECK (status IN ('ACTIVE','REMOVED'))
) ENGINE=InnoDB;

CREATE TABLE movement_change (
    id BINARY(16) NOT NULL PRIMARY KEY, movement_id BINARY(16) NOT NULL, before_json TEXT NOT NULL,
    reason VARCHAR(500) NOT NULL, changed_at DATETIME(6) NOT NULL, changed_by BINARY(16) NOT NULL,
    KEY ix_movement_change (movement_id,changed_at),
    CONSTRAINT fk_movement_change FOREIGN KEY (movement_id) REFERENCES craft_movement(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE movement_file (
    movement_id BINARY(16) NOT NULL, file_id BINARY(16) NOT NULL,
    PRIMARY KEY (movement_id,file_id),
    CONSTRAINT fk_movement_file_movement FOREIGN KEY (movement_id) REFERENCES craft_movement(id) ON DELETE RESTRICT,
    CONSTRAINT fk_movement_file_file FOREIGN KEY (file_id) REFERENCES stored_file(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE job_charge_clock (
    job_id BINARY(16) NOT NULL PRIMARY KEY, start_movement_id BINARY(16) NOT NULL,
    stop_movement_id BINARY(16) NULL, basis_hours INT NOT NULL, override_reason VARCHAR(500) NULL,
    pending_start_id BINARY(16) NULL, pending_stop_id BINARY(16) NULL,
    pending_reason VARCHAR(500) NULL, requested_by BINARY(16) NULL, approved_by BINARY(16) NULL,
    decided_at DATETIME(6) NULL, version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL, created_by BINARY(16) NOT NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NOT NULL,
    CONSTRAINT fk_clock_job FOREIGN KEY (job_id) REFERENCES sts_job(id) ON DELETE RESTRICT,
    CONSTRAINT fk_clock_start FOREIGN KEY (start_movement_id) REFERENCES craft_movement(id) ON DELETE RESTRICT,
    CONSTRAINT fk_clock_stop FOREIGN KEY (stop_movement_id) REFERENCES craft_movement(id) ON DELETE RESTRICT,
    CONSTRAINT fk_clock_pending_start FOREIGN KEY (pending_start_id) REFERENCES craft_movement(id) ON DELETE RESTRICT,
    CONSTRAINT fk_clock_pending_stop FOREIGN KEY (pending_stop_id) REFERENCES craft_movement(id) ON DELETE RESTRICT,
    CONSTRAINT ck_clock_basis CHECK (basis_hours > 0),
    CONSTRAINT ck_clock_second_person CHECK (approved_by IS NULL OR requested_by IS NULL OR approved_by<>requested_by)
) ENGINE=InnoDB;

CREATE TABLE clock_selection_change (
    id BINARY(16) NOT NULL PRIMARY KEY, job_id BINARY(16) NOT NULL,
    before_json TEXT NOT NULL, after_json TEXT NOT NULL, reason VARCHAR(500) NOT NULL,
    decision VARCHAR(20) NOT NULL, changed_at DATETIME(6) NOT NULL, changed_by BINARY(16) NOT NULL,
    KEY ix_clock_change (job_id,changed_at),
    CONSTRAINT fk_clock_change FOREIGN KEY (job_id) REFERENCES job_charge_clock(job_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE clock_adjustment (
    id BINARY(16) NOT NULL PRIMARY KEY, job_id BINARY(16) NOT NULL,
    hours DECIMAL(12,4) NOT NULL, reason VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING', requested_by BINARY(16) NOT NULL,
    approved_by BINARY(16) NULL, decided_at DATETIME(6) NULL, version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    KEY ix_adjustment_job (job_id,status),
    CONSTRAINT fk_adjustment_clock FOREIGN KEY (job_id) REFERENCES job_charge_clock(job_id) ON DELETE RESTRICT,
    CONSTRAINT ck_adjustment_status CHECK (status IN ('PENDING','APPROVED','RETURNED')),
    CONSTRAINT ck_adjustment_second_person CHECK (approved_by IS NULL OR approved_by<>requested_by)
) ENGINE=InnoDB;

CREATE TABLE additional_hours_notice (
    id BINARY(16) NOT NULL PRIMARY KEY, job_id BINARY(16) NOT NULL,
    episode_key VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', snapshot_json TEXT NOT NULL,
    released_at DATETIME(6) NULL, released_by BINARY(16) NULL,
    delivery_outcome VARCHAR(500) NULL, delivered_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_notice_episode (job_id,episode_key), KEY ix_notice_job (job_id,created_at),
    CONSTRAINT fk_notice_clock FOREIGN KEY (job_id) REFERENCES job_charge_clock(job_id) ON DELETE RESTRICT,
    CONSTRAINT ck_notice_status CHECK (status IN ('DRAFT','RELEASED'))
) ENGINE=InnoDB;

CREATE TABLE job_recovery (
    job_id BINARY(16) NOT NULL PRIMARY KEY, disembarked_at DATETIME(6) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN', closed_at DATETIME(6) NULL,
    closed_by BINARY(16) NULL, version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NOT NULL,
    CONSTRAINT fk_recovery_job FOREIGN KEY (job_id) REFERENCES sts_job(id) ON DELETE RESTRICT,
    CONSTRAINT ck_recovery_status CHECK (status IN ('OPEN','CLOSED'))
) ENGINE=InnoDB;

CREATE TABLE recovery_asset (
    id BINARY(16) NOT NULL PRIMARY KEY, job_id BINARY(16) NOT NULL,
    allocation_id BINARY(16) NOT NULL, status VARCHAR(20) NOT NULL,
    notes VARCHAR(1000) NULL, file_id BINARY(16) NULL,
    resolution_reason VARCHAR(500) NULL, resolved_by BINARY(16) NULL, resolved_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0, updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NOT NULL,
    UNIQUE KEY uk_recovery_asset (job_id,allocation_id),
    CONSTRAINT fk_recovery_asset_job FOREIGN KEY (job_id) REFERENCES job_recovery(job_id) ON DELETE RESTRICT,
    CONSTRAINT fk_recovery_asset_allocation FOREIGN KEY (allocation_id) REFERENCES job_resource_allocation(id) ON DELETE RESTRICT,
    CONSTRAINT fk_recovery_asset_file FOREIGN KEY (file_id) REFERENCES stored_file(id) ON DELETE RESTRICT,
    CONSTRAINT ck_recovery_asset_status CHECK (status IN ('RETURNED','DAMAGED','SHORT'))
) ENGINE=InnoDB;

CREATE TABLE recovery_change (
    id BINARY(16) NOT NULL PRIMARY KEY, job_id BINARY(16) NOT NULL,
    allocation_id BINARY(16) NULL, before_json TEXT NOT NULL, reason VARCHAR(500) NOT NULL,
    changed_at DATETIME(6) NOT NULL, changed_by BINARY(16) NOT NULL,
    KEY ix_recovery_change (job_id,changed_at),
    CONSTRAINT fk_recovery_change FOREIGN KEY (job_id) REFERENCES job_recovery(job_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

INSERT INTO permission (code,module,description) VALUES
 ('OPS.MOVEMENT.READ','OPERATIONS','View job movements and clock'),
 ('OPS.MOVEMENT.WRITE','OPERATIONS','Record movements, clock and draft notice'),
 ('OPS.MOVEMENT.APPROVE','OPERATIONS','Approve clock changes and release notices'),
 ('OPS.RECOVERY.READ','OPERATIONS','View job recovery'),
 ('OPS.RECOVERY.WRITE','OPERATIONS','Record recovery and returns'),
 ('OPS.RECOVERY.APPROVE','OPERATIONS','Resolve discrepancies and close recovery');

INSERT INTO role_permission (role_id,permission_code)
SELECT r.id,p.code FROM role r JOIN permission p ON p.code LIKE 'OPS.MOVEMENT.%' OR p.code LIKE 'OPS.RECOVERY.%'
WHERE r.code IN ('SYSTEM_ADMIN','OPERATIONS');

INSERT INTO role_permission (role_id,permission_code)
SELECT r.id,p.code FROM role r JOIN permission p ON p.code IN ('OPS.MOVEMENT.READ','OPS.RECOVERY.READ')
WHERE r.code IN ('TECHNICAL','FINANCE','MANAGEMENT_APPROVER','AUDITOR');
