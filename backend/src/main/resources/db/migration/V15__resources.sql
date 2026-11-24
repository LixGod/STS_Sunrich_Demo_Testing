-- Phase 4a: resource masters and collision-safe job allocations. Candidate labels only; no people or marine values.
CREATE TABLE resource_category (
    code VARCHAR(40) NOT NULL, label VARCHAR(100) NOT NULL, sort_order INT NOT NULL,
    active BOOLEAN NOT NULL, created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB;

INSERT INTO resource_category (code,label,sort_order,active,created_at,updated_at,version) VALUES
 ('PRIMARY_FENDER','Primary fender',10,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0),
 ('SECONDARY_FENDER','Secondary fender',20,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0),
 ('CARGO_HOSE','Cargo hose',30,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0),
 ('VAPOUR_HOSE','Vapour hose',40,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0),
 ('REDUCER','Reducer',50,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0),
 ('ACCESSORY','Accessory',60,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0);

CREATE TABLE resource_unit (
    code VARCHAR(30) NOT NULL, label VARCHAR(100) NOT NULL, dimension VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL, updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB;

CREATE TABLE equipment_model (
    id BINARY(16) NOT NULL, legal_entity_id BINARY(16) NOT NULL, category_code VARCHAR(40) NOT NULL,
    code VARCHAR(40) NOT NULL, name VARCHAR(150) NOT NULL, active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_equipment_model_entity_code (legal_entity_id,code),
    KEY ix_equipment_model_category (legal_entity_id,category_code,active),
    CONSTRAINT fk_equipment_model_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity(id) ON DELETE RESTRICT,
    CONSTRAINT fk_equipment_model_category FOREIGN KEY (category_code) REFERENCES resource_category(code) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE equipment_asset (
    id BINARY(16) NOT NULL, legal_entity_id BINARY(16) NOT NULL, branch_id BINARY(16) NULL,
    model_id BINARY(16) NOT NULL, asset_identifier VARCHAR(80) NOT NULL,
    dimensions_text VARCHAR(500) NULL, swl_value DECIMAL(18,3) NULL, swl_unit VARCHAR(30) NULL,
    swl_source VARCHAR(500) NULL, mbl_value DECIMAL(18,3) NULL, mbl_unit VARCHAR(30) NULL,
    mbl_source VARCHAR(500) NULL, home_location VARCHAR(150) NULL,
    status VARCHAR(20) NOT NULL, created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_asset_identifier_entity (legal_entity_id,asset_identifier),
    KEY ix_asset_scope_status (legal_entity_id,branch_id,status,asset_identifier),
    CONSTRAINT fk_asset_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity(id) ON DELETE RESTRICT,
    CONSTRAINT fk_asset_branch FOREIGN KEY (branch_id) REFERENCES branch(id) ON DELETE RESTRICT,
    CONSTRAINT fk_asset_model FOREIGN KEY (model_id) REFERENCES equipment_model(id) ON DELETE RESTRICT,
    CONSTRAINT fk_asset_swl_unit FOREIGN KEY (swl_unit) REFERENCES resource_unit(code) ON DELETE RESTRICT,
    CONSTRAINT fk_asset_mbl_unit FOREIGN KEY (mbl_unit) REFERENCES resource_unit(code) ON DELETE RESTRICT,
    CONSTRAINT ck_asset_status CHECK (status IN ('AVAILABLE','MAINTENANCE','RETIRED')),
    CONSTRAINT ck_asset_swl_source CHECK (swl_value IS NULL OR (swl_value>=0 AND swl_unit IS NOT NULL AND swl_source IS NOT NULL)),
    CONSTRAINT ck_asset_mbl_source CHECK (mbl_value IS NULL OR (mbl_value>=0 AND mbl_unit IS NOT NULL AND mbl_source IS NOT NULL))
) ENGINE=InnoDB;

CREATE TABLE resource_competency (
    code VARCHAR(40) NOT NULL, label VARCHAR(100) NOT NULL, active BOOLEAN NOT NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB;
INSERT INTO resource_competency (code,label,active,updated_at,version) VALUES
 ('POAC','POAC / mooring master',TRUE,UTC_TIMESTAMP(6),0),
 ('ASSISTANT_MOORING_MASTER','Assistant mooring master',TRUE,UTC_TIMESTAMP(6),0),
 ('STS_SUPERINTENDENT','STS superintendent',TRUE,UTC_TIMESTAMP(6),0);

CREATE TABLE personnel (
    id BINARY(16) NOT NULL, legal_entity_id BINARY(16) NOT NULL, personnel_identifier VARCHAR(80) NOT NULL,
    full_name VARCHAR(150) NOT NULL, app_user_id BINARY(16) NULL, availability_note VARCHAR(500) NULL,
    active BOOLEAN NOT NULL, created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_personnel_entity_identifier (legal_entity_id,personnel_identifier),
    UNIQUE KEY uk_personnel_user (app_user_id), KEY ix_personnel_scope (legal_entity_id,active,full_name),
    CONSTRAINT fk_personnel_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity(id) ON DELETE RESTRICT,
    CONSTRAINT fk_personnel_user FOREIGN KEY (app_user_id) REFERENCES app_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE personnel_competency (
    personnel_id BINARY(16) NOT NULL, competency_code VARCHAR(40) NOT NULL, evidence VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    PRIMARY KEY (personnel_id,competency_code),
    CONSTRAINT fk_pc_person FOREIGN KEY (personnel_id) REFERENCES personnel(id) ON DELETE RESTRICT,
    CONSTRAINT fk_pc_competency FOREIGN KEY (competency_code) REFERENCES resource_competency(code) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE resource_vendor (
    id BINARY(16) NOT NULL, legal_entity_id BINARY(16) NOT NULL, name VARCHAR(150) NOT NULL,
    contacts VARCHAR(1000) NULL, contract_from DATE NULL, contract_to DATE NULL, active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (id), KEY ix_vendor_scope (legal_entity_id,active,name),
    CONSTRAINT fk_vendor_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity(id) ON DELETE RESTRICT,
    CONSTRAINT ck_vendor_dates CHECK (contract_from IS NULL OR contract_to IS NULL OR contract_to>=contract_from)
) ENGINE=InnoDB;

CREATE TABLE resource_craft (
    id BINARY(16) NOT NULL, legal_entity_id BINARY(16) NOT NULL, vendor_id BINARY(16) NOT NULL,
    identifier VARCHAR(80) NOT NULL, name VARCHAR(150) NOT NULL, craft_type VARCHAR(20) NOT NULL,
    technical_data VARCHAR(1000) NULL, bollard_pull_value DECIMAL(18,3) NULL,
    bollard_pull_unit VARCHAR(30) NULL, bollard_pull_source VARCHAR(500) NULL,
    home_location VARCHAR(150) NULL, active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_craft_entity_identifier (legal_entity_id,identifier),
    KEY ix_craft_scope (legal_entity_id,active,name),
    CONSTRAINT fk_craft_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity(id) ON DELETE RESTRICT,
    CONSTRAINT fk_craft_vendor FOREIGN KEY (vendor_id) REFERENCES resource_vendor(id) ON DELETE RESTRICT,
    CONSTRAINT fk_craft_pull_unit FOREIGN KEY (bollard_pull_unit) REFERENCES resource_unit(code) ON DELETE RESTRICT,
    CONSTRAINT ck_craft_type CHECK (craft_type IN ('TUG','LAUNCH','WORKBOAT')),
    CONSTRAINT ck_craft_pull_source CHECK (bollard_pull_value IS NULL OR
      (bollard_pull_value>=0 AND bollard_pull_unit IS NOT NULL AND bollard_pull_source IS NOT NULL))
) ENGINE=InnoDB;

CREATE TABLE resource_certificate_type (
    code VARCHAR(40) NOT NULL, label VARCHAR(100) NOT NULL, active BOOLEAN NOT NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB;

CREATE TABLE resource_certificate_requirement (
    id BINARY(16) NOT NULL, legal_entity_id BINARY(16) NOT NULL,
    resource_kind VARCHAR(20) NOT NULL, scope_code VARCHAR(40) NOT NULL,
    certificate_type_code VARCHAR(40) NOT NULL, active BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (id), UNIQUE KEY uk_cert_requirement (legal_entity_id,resource_kind,scope_code,certificate_type_code),
    CONSTRAINT fk_req_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity(id) ON DELETE RESTRICT,
    CONSTRAINT fk_req_type FOREIGN KEY (certificate_type_code) REFERENCES resource_certificate_type(code) ON DELETE RESTRICT,
    CONSTRAINT ck_req_kind CHECK (resource_kind IN ('ASSET','PERSON','CRAFT'))
) ENGINE=InnoDB;

CREATE TABLE resource_certificate_policy (
    legal_entity_id BINARY(16) NOT NULL, mode VARCHAR(10) NOT NULL,
    warning_days INT NULL, requirements_configured BOOLEAN NOT NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (legal_entity_id),
    CONSTRAINT fk_cert_policy_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity(id) ON DELETE RESTRICT,
    CONSTRAINT ck_cert_policy_mode CHECK (mode IN ('BLOCK','WARN')),
    CONSTRAINT ck_cert_warning_days CHECK (warning_days IS NULL OR warning_days>=0)
) ENGINE=InnoDB;

CREATE TABLE resource_certificate (
    id BINARY(16) NOT NULL, asset_id BINARY(16) NULL, personnel_id BINARY(16) NULL, craft_id BINARY(16) NULL,
    type_code VARCHAR(40) NOT NULL, certificate_number VARCHAR(100) NOT NULL,
    issuer VARCHAR(150) NOT NULL, issue_date DATE NOT NULL, expiry_date DATE NULL, file_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (id), KEY ix_cert_asset (asset_id,type_code,expiry_date),
    KEY ix_cert_person (personnel_id,type_code,expiry_date), KEY ix_cert_craft (craft_id,type_code,expiry_date),
    CONSTRAINT fk_cert_asset FOREIGN KEY (asset_id) REFERENCES equipment_asset(id) ON DELETE RESTRICT,
    CONSTRAINT fk_cert_person FOREIGN KEY (personnel_id) REFERENCES personnel(id) ON DELETE RESTRICT,
    CONSTRAINT fk_cert_craft FOREIGN KEY (craft_id) REFERENCES resource_craft(id) ON DELETE RESTRICT,
    CONSTRAINT fk_cert_type FOREIGN KEY (type_code) REFERENCES resource_certificate_type(code) ON DELETE RESTRICT,
    CONSTRAINT fk_cert_file FOREIGN KEY (file_id) REFERENCES stored_file(id) ON DELETE RESTRICT,
    CONSTRAINT ck_cert_one_owner CHECK ((asset_id IS NOT NULL)+(personnel_id IS NOT NULL)+(craft_id IS NOT NULL)=1),
    CONSTRAINT ck_cert_dates CHECK (expiry_date IS NULL OR expiry_date>=issue_date)
) ENGINE=InnoDB;

CREATE TABLE equipment_inspection (
    id BINARY(16) NOT NULL, asset_id BINARY(16) NOT NULL, inspected_at DATE NOT NULL,
    result VARCHAR(150) NOT NULL, source VARCHAR(500) NOT NULL, next_due_at DATE NULL,
    created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (id), KEY ix_inspection_asset_date (asset_id,inspected_at),
    CONSTRAINT fk_inspection_asset FOREIGN KEY (asset_id) REFERENCES equipment_asset(id) ON DELETE RESTRICT,
    CONSTRAINT ck_inspection_due CHECK (next_due_at IS NULL OR next_due_at>=inspected_at)
) ENGINE=InnoDB;

CREATE TABLE job_resource_allocation (
    id BINARY(16) NOT NULL, job_id BINARY(16) NOT NULL, legal_entity_id BINARY(16) NOT NULL,
    asset_id BINARY(16) NULL, personnel_id BINARY(16) NULL, craft_id BINARY(16) NULL,
    start_at DATETIME(6) NOT NULL, end_at DATETIME(6) NOT NULL, time_zone VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL, exception_reason VARCHAR(500) NULL, requested_by BINARY(16) NULL,
    approved_by BINARY(16) NULL, decided_at DATETIME(6) NULL, decision_reason VARCHAR(500) NULL,
    collision_json TEXT NULL, eligibility_json TEXT NULL,
    created_at DATETIME(6) NOT NULL, created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL, updated_by BINARY(16) NULL, version BIGINT NOT NULL,
    PRIMARY KEY (id), KEY ix_allocation_job (job_id,status,start_at),
    KEY ix_allocation_asset (asset_id,status,start_at,end_at),
    KEY ix_allocation_person (personnel_id,status,start_at,end_at),
    KEY ix_allocation_craft (craft_id,status,start_at,end_at),
    CONSTRAINT fk_allocation_job FOREIGN KEY (job_id) REFERENCES sts_job(id) ON DELETE RESTRICT,
    CONSTRAINT fk_allocation_entity FOREIGN KEY (legal_entity_id) REFERENCES legal_entity(id) ON DELETE RESTRICT,
    CONSTRAINT fk_allocation_asset FOREIGN KEY (asset_id) REFERENCES equipment_asset(id) ON DELETE RESTRICT,
    CONSTRAINT fk_allocation_person FOREIGN KEY (personnel_id) REFERENCES personnel(id) ON DELETE RESTRICT,
    CONSTRAINT fk_allocation_craft FOREIGN KEY (craft_id) REFERENCES resource_craft(id) ON DELETE RESTRICT,
    CONSTRAINT ck_allocation_one_resource CHECK ((asset_id IS NOT NULL)+(personnel_id IS NOT NULL)+(craft_id IS NOT NULL)=1),
    CONSTRAINT ck_allocation_interval CHECK (end_at>start_at),
    CONSTRAINT ck_allocation_status CHECK (status IN ('ACTIVE','PENDING_EXCEPTION','REJECTED','CANCELLED')),
    CONSTRAINT ck_allocation_second_person CHECK (approved_by IS NULL OR requested_by IS NULL OR approved_by<>requested_by)
) ENGINE=InnoDB;

CREATE TABLE allocation_event (
    id BINARY(16) NOT NULL, allocation_id BINARY(16) NOT NULL, action VARCHAR(30) NOT NULL,
    details_json TEXT NOT NULL, occurred_at DATETIME(6) NOT NULL, actor_id BINARY(16) NOT NULL,
    PRIMARY KEY (id), KEY ix_allocation_event (allocation_id,occurred_at,id),
    CONSTRAINT fk_allocation_event FOREIGN KEY (allocation_id) REFERENCES job_resource_allocation(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

INSERT INTO permission (code,module,description) VALUES
 ('MASTER.RESOURCE.READ','MASTER','View resource masters within scope'),
 ('MASTER.RESOURCE.WRITE','MASTER','Manage resource masters within scope'),
 ('OPS.ALLOCATION.READ','OPERATIONS','View job resource allocations within scope'),
 ('OPS.ALLOCATION.WRITE','OPERATIONS','Create and amend job allocations within scope'),
 ('OPS.ALLOCATION.APPROVE','OPERATIONS','Decide another person''s allocation exception within scope');

INSERT INTO role_permission (role_id,permission_code)
SELECT r.id,p.code FROM role r JOIN permission p ON p.code IN
 ('MASTER.RESOURCE.READ','MASTER.RESOURCE.WRITE','OPS.ALLOCATION.READ','OPS.ALLOCATION.WRITE','OPS.ALLOCATION.APPROVE')
WHERE r.code='SYSTEM_ADMIN';

INSERT INTO role_permission (role_id,permission_code)
SELECT r.id,p.code FROM role r JOIN permission p ON p.code IN
 ('MASTER.RESOURCE.READ','OPS.ALLOCATION.READ','OPS.ALLOCATION.WRITE','OPS.ALLOCATION.APPROVE')
WHERE r.code='OPERATIONS';

INSERT INTO role_permission (role_id,permission_code)
SELECT r.id,p.code FROM role r JOIN permission p ON p.code IN
 ('MASTER.RESOURCE.READ','MASTER.RESOURCE.WRITE','OPS.ALLOCATION.READ')
WHERE r.code='TECHNICAL';

INSERT INTO role_permission (role_id,permission_code)
SELECT r.id,p.code FROM role r JOIN permission p ON p.code IN ('MASTER.RESOURCE.READ','OPS.ALLOCATION.READ')
WHERE r.code IN ('COMMERCIAL','HSE_REVIEWER','MANAGEMENT_APPROVER','AUDITOR');
