-- Per-job/per-vessel voyage values. Similar-LOA calculation remains disabled pending owner rules.
CREATE TABLE voyage_field (
    code VARCHAR(40) NOT NULL PRIMARY KEY,
    label VARCHAR(150) NOT NULL,
    unit VARCHAR(20) NOT NULL,
    required_for_review BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB;
INSERT INTO voyage_field (code,label,unit,sort_order) VALUES
 ('ARRIVAL_DRAFT_FWD','Arrival draft forward','m',10),('ARRIVAL_DRAFT_AFT','Arrival draft aft','m',20),
 ('DEPARTURE_DRAFT_FWD','Departure draft forward','m',30),('DEPARTURE_DRAFT_AFT','Departure draft aft','m',40),
 ('ARRIVAL_FREEBOARD_FWD','Arrival freeboard forward','m',50),('ARRIVAL_FREEBOARD_AFT','Arrival freeboard aft','m',60),
 ('DEPARTURE_FREEBOARD_FWD','Departure freeboard forward','m',70),('DEPARTURE_FREEBOARD_AFT','Departure freeboard aft','m',80),
 ('ARRIVAL_DISPLACEMENT','Arrival displacement','t',90),('DEPARTURE_DISPLACEMENT','Departure displacement','t',100),
 ('ARRIVAL_PARALLEL_BODY_LENGTH','Arrival parallel body length','m',110),
 ('DEPARTURE_PARALLEL_BODY_LENGTH','Departure parallel body length','m',120),
 ('ARRIVAL_MANIFOLD_HEIGHT','Arrival manifold height','m',130),('DEPARTURE_MANIFOLD_HEIGHT','Departure manifold height','m',140);

CREATE TABLE job_voyage_version (
    id BINARY(16) NOT NULL PRIMARY KEY,
    job_vessel_id BINARY(16) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    notes VARCHAR(1000) NULL,
    submitted_by BINARY(16) NULL,
    submitted_at DATETIME(6) NULL,
    verified_by BINARY(16) NULL,
    verified_at DATETIME(6) NULL,
    returned_reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL,
    UNIQUE KEY uk_job_voyage_version (job_vessel_id,version_no),
    KEY ix_job_voyage_status (job_vessel_id,status,submitted_at),
    CONSTRAINT fk_job_voyage_vessel FOREIGN KEY (job_vessel_id) REFERENCES job_vessel(id) ON DELETE RESTRICT,
    CONSTRAINT ck_job_voyage_no_self_verify CHECK (verified_by IS NULL OR submitted_by IS NULL OR verified_by <> submitted_by)
) ENGINE=InnoDB;

CREATE TABLE job_voyage_value (
    id BINARY(16) NOT NULL PRIMARY KEY,
    version_id BINARY(16) NOT NULL,
    field_code VARCHAR(40) NOT NULL,
    value_text VARCHAR(100) NOT NULL,
    value_number DECIMAL(12,3) NOT NULL,
    source_document_id BINARY(16) NULL,
    source_page INT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uk_job_voyage_value (version_id,field_code),
    CONSTRAINT fk_job_voyage_value_version FOREIGN KEY (version_id) REFERENCES job_voyage_version(id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_voyage_value_field FOREIGN KEY (field_code) REFERENCES voyage_field(code) ON DELETE RESTRICT,
    CONSTRAINT fk_job_voyage_value_document FOREIGN KEY (source_document_id) REFERENCES document_version(id) ON DELETE RESTRICT,
    CONSTRAINT ck_job_voyage_source_page CHECK (source_page IS NULL OR source_page > 0)
) ENGINE=InnoDB;

CREATE TABLE compatibility_rule (
    code VARCHAR(40) NOT NULL PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    threshold_percent DECIMAL(6,3) NULL,
    comparison_method VARCHAR(30) NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL
) ENGINE=InnoDB;
INSERT INTO compatibility_rule (code,enabled,threshold_percent,comparison_method,updated_at,version)
VALUES ('SIMILAR_LOA',FALSE,10.000,NULL,UTC_TIMESTAMP(6),0);
