-- Phase 3a: file storage, document types and vessel documents, and versioned vessel particulars entered by hand from
-- the source documents (manual Q88 entry, decision K1) with verification by a second person (FR-TECH-01, AR-03).

-- Uploaded files. Bytes live in the file store (local disk now, FTP later); this row is the index.
CREATE TABLE stored_file (
    id                BINARY(16)   NOT NULL,
    original_name     VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL COMMENT 'Checked against the file signature at upload',
    size_bytes        BIGINT       NOT NULL,
    sha256            CHAR(64)     NOT NULL,
    storage_key       VARCHAR(255) NOT NULL COMMENT 'Server-generated path in the file store; never user input',
    scan_state        VARCHAR(20)  NOT NULL COMMENT 'NOT_SCANNED until malware scanning (ClamAV, K7) is approved | CLEAN | INFECTED',
    access_permission VARCHAR(80)  NOT NULL COMMENT 'Permission needed to download',
    legal_entity_id   BINARY(16)   NULL COMMENT 'Scope of the download permission; NULL = group-wide record (e.g. a vessel)',
    branch_id         BINARY(16)   NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        BINARY(16)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stored_file_key (storage_key),
    KEY ix_stored_file_sha (sha256),
    CONSTRAINT ck_stored_file_size CHECK (size_bytes > 0)
) ENGINE = InnoDB;

-- Short-lived download tokens, so files open in the browser's own viewer without exposing a permanent URL.
CREATE TABLE file_download_token (
    token_hash CHAR(64)    NOT NULL COMMENT 'SHA-256 of the token; the token itself is never stored',
    file_id    BINARY(16)  NOT NULL,
    user_id    BINARY(16)  NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (token_hash),
    KEY ix_file_download_token_expiry (expires_at),
    CONSTRAINT fk_file_download_token_file FOREIGN KEY (file_id) REFERENCES stored_file (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE document_type (
    id          BINARY(16)   NOT NULL,
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    applies_to  VARCHAR(20)  NOT NULL COMMENT 'VESSEL | JOB | EQUIPMENT | PERSONNEL',
    required    BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'Requested by default (used by the Phase 3b document request)',
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)  NOT NULL,
    created_by  BINARY(16)   NULL,
    updated_at  DATETIME(6)  NOT NULL,
    updated_by  BINARY(16)   NULL,
    version     BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_type_code (code)
) ENGINE = InnoDB;

-- Vessel items from the document request in "Flow and requirement.docx" (step 4). A starting list; editable as a master.
INSERT INTO document_type (id, code, name, applies_to, required, active, created_at, updated_at, version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000901'), 'Q88',         'Q88 questionnaire',            'VESSEL', TRUE,  TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000902'), 'GA-PLAN',     'General arrangement (GA) plan', 'VESSEL', TRUE,  TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000903'), 'MOORING-ARR', 'Mooring arrangement plan',      'VESSEL', TRUE,  TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000904'), 'PARTICULARS', 'Ship particulars',              'VESSEL', TRUE,  TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000905'), 'MANIFOLD',    'Manifold details',              'VESSEL', TRUE,  TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000906'), 'OTHER-VSL',   'Other vessel document',         'VESSEL', FALSE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

-- A document attached to a record; each upload of the same type for the same record is a new version.
CREATE TABLE document_version (
    id               BINARY(16)   NOT NULL,
    owner_type       VARCHAR(20)  NOT NULL COMMENT 'VESSEL now; JOB, EQUIPMENT, PERSONNEL later',
    owner_id         BINARY(16)   NOT NULL,
    document_type_id BINARY(16)   NOT NULL,
    version_no       INT          NOT NULL,
    file_id          BINARY(16)   NOT NULL,
    title            VARCHAR(200) NULL,
    issued           BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'Issued documents need a CLEAN scan (later increments)',
    uploaded_by      BINARY(16)   NOT NULL,
    uploaded_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_document_version (owner_type, owner_id, document_type_id, version_no),
    UNIQUE KEY uk_document_version_file (file_id),
    KEY ix_document_version_owner (owner_type, owner_id, uploaded_at),
    CONSTRAINT fk_document_version_type FOREIGN KEY (document_type_id) REFERENCES document_type (id) ON DELETE RESTRICT,
    CONSTRAINT fk_document_version_file FOREIGN KEY (file_id) REFERENCES stored_file (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Catalogue of particulars captured per vessel. Seeded with the fixed vessel values the JPO and mooring plan templates
-- use; voyage-dependent values (drafts, freeboards, displacement, parallel body length, manifold heights) are
-- captured per job in Phase 3b. Units are fixed per field: no conversions without verified parameters.
CREATE TABLE particular_field (
    code                  VARCHAR(40)  NOT NULL,
    label                 VARCHAR(100) NOT NULL,
    field_group           VARCHAR(20)  NOT NULL COMMENT 'DIMENSIONS | MANIFOLD | OTHER',
    data_type             VARCHAR(10)  NOT NULL COMMENT 'NUMBER | TEXT',
    unit                  VARCHAR(10)  NULL,
    required_for_planning BOOLEAN      NOT NULL COMMENT 'Missing verified value will block JPO / mooring plan approval (3c/3d)',
    sort_order            INT          NOT NULL,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (code)
) ENGINE = InnoDB;

INSERT INTO particular_field (code, label, field_group, data_type, unit, required_for_planning, sort_order) VALUES
    ('LOA',                       'Length overall (LOA)',                   'DIMENSIONS', 'NUMBER', 'm', TRUE,  10),
    ('BEAM',                      'Beam',                                   'DIMENSIONS', 'NUMBER', 'm', TRUE,  20),
    ('DWT',                       'Deadweight (summer)',                    'DIMENSIONS', 'NUMBER', 't', FALSE, 30),
    ('BOW_TO_MANIFOLD',           'Bow to centre manifold',                 'MANIFOLD',   'NUMBER', 'm', TRUE,  40),
    ('STERN_TO_MANIFOLD',         'Stern to centre manifold',               'MANIFOLD',   'NUMBER', 'm', TRUE,  50),
    ('ACCOMMODATION_TO_MANIFOLD', 'Accommodation front to centre manifold', 'MANIFOLD',   'NUMBER', 'm', FALSE, 60),
    ('MANIFOLD_SIZE',             'Manifold size',                          'MANIFOLD',   'TEXT',   NULL, FALSE, 70);

CREATE TABLE vessel_particulars_version (
    id              BINARY(16)   NOT NULL,
    vessel_id       BINARY(16)   NOT NULL,
    version_no      INT          NOT NULL,
    status          VARCHAR(20)  NOT NULL COMMENT 'DRAFT | SUBMITTED | VERIFIED | SUPERSEDED',
    notes           VARCHAR(1000) NULL,
    submitted_by    BINARY(16)   NULL,
    submitted_at    DATETIME(6)  NULL,
    verified_by     BINARY(16)   NULL,
    verified_at     DATETIME(6)  NULL,
    returned_reason VARCHAR(500) NULL COMMENT 'Why the reviewer sent the last submission back',
    created_at      DATETIME(6)  NOT NULL,
    created_by      BINARY(16)   NULL,
    updated_at      DATETIME(6)  NOT NULL,
    updated_by      BINARY(16)   NULL,
    version         BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vessel_particulars_version (vessel_id, version_no),
    KEY ix_vessel_particulars_status (status, submitted_at),
    CONSTRAINT fk_vessel_particulars_vessel FOREIGN KEY (vessel_id) REFERENCES vessel (id) ON DELETE RESTRICT,
    CONSTRAINT ck_vessel_particulars_no_self_verification CHECK (verified_by IS NULL OR submitted_by IS NULL OR verified_by <> submitted_by)
) ENGINE = InnoDB;

-- One value per field and version, with where it was read from (document + page).
CREATE TABLE vessel_particular_value (
    id                 BINARY(16)     NOT NULL,
    version_id         BINARY(16)     NOT NULL,
    field_code         VARCHAR(40)    NOT NULL,
    value_text         VARCHAR(100)   NOT NULL COMMENT 'As entered (numbers normalised)',
    value_number       DECIMAL(12, 3) NULL,
    source_document_id BINARY(16)     NULL,
    source_page        INT            NULL,
    verified           BOOLEAN        NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_vessel_particular_value (version_id, field_code),
    CONSTRAINT fk_particular_value_version FOREIGN KEY (version_id) REFERENCES vessel_particulars_version (id) ON DELETE RESTRICT,
    CONSTRAINT fk_particular_value_field FOREIGN KEY (field_code) REFERENCES particular_field (code) ON DELETE RESTRICT,
    CONSTRAINT fk_particular_value_source FOREIGN KEY (source_document_id) REFERENCES document_version (id) ON DELETE RESTRICT,
    CONSTRAINT ck_particular_value_page CHECK (source_page IS NULL OR source_page > 0)
) ENGINE = InnoDB;

INSERT INTO permission (code, module, description) VALUES
    ('TECH.VESSEL.READ',      'TECHNICAL', 'View vessel particulars and their verification'),
    ('TECH.VESSEL.WRITE',     'TECHNICAL', 'Enter vessel particulars from the source documents and submit them'),
    ('TECH.VESSEL.VERIFY',    'TECHNICAL', 'Verify vessel particulars entered by someone else'),
    ('DOCUMENT.READ',         'DOCUMENTS', 'View and download documents'),
    ('DOCUMENT.WRITE',        'DOCUMENTS', 'Upload documents'),
    ('MASTER.TECHNICAL.READ', 'MASTERS',   'View document types and the particulars catalogue'),
    ('MASTER.TECHNICAL.WRITE','MASTERS',   'Manage document types');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r
JOIN permission p ON p.code IN ('TECH.VESSEL.READ', 'TECH.VESSEL.WRITE', 'TECH.VESSEL.VERIFY', 'DOCUMENT.READ', 'DOCUMENT.WRITE',
                                'MASTER.TECHNICAL.READ', 'MASTER.TECHNICAL.WRITE')
WHERE r.code = 'SYSTEM_ADMIN';

-- Baseline mapping, to be confirmed by Ship2Ship (SRS §27).
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, x.code FROM role r JOIN (
    SELECT 'TECHNICAL' AS role_code, 'TECH.VESSEL.READ' AS code UNION ALL
    SELECT 'TECHNICAL', 'TECH.VESSEL.WRITE' UNION ALL
    SELECT 'TECHNICAL', 'TECH.VESSEL.VERIFY' UNION ALL
    SELECT 'TECHNICAL', 'DOCUMENT.READ' UNION ALL
    SELECT 'TECHNICAL', 'DOCUMENT.WRITE' UNION ALL
    SELECT 'TECHNICAL', 'MASTER.TECHNICAL.READ' UNION ALL
    SELECT 'TECHNICAL', 'MASTER.TECHNICAL.WRITE' UNION ALL
    SELECT 'OPERATIONS', 'TECH.VESSEL.READ' UNION ALL
    SELECT 'OPERATIONS', 'DOCUMENT.READ' UNION ALL
    SELECT 'OPERATIONS', 'MASTER.TECHNICAL.READ' UNION ALL
    SELECT 'COMMERCIAL', 'TECH.VESSEL.READ' UNION ALL
    SELECT 'COMMERCIAL', 'DOCUMENT.READ' UNION ALL
    SELECT 'HSE_REVIEWER', 'TECH.VESSEL.READ' UNION ALL
    SELECT 'HSE_REVIEWER', 'DOCUMENT.READ' UNION ALL
    SELECT 'MANAGEMENT_APPROVER', 'TECH.VESSEL.READ' UNION ALL
    SELECT 'MANAGEMENT_APPROVER', 'DOCUMENT.READ' UNION ALL
    SELECT 'AUDITOR', 'TECH.VESSEL.READ' UNION ALL
    SELECT 'AUDITOR', 'DOCUMENT.READ' UNION ALL
    SELECT 'AUDITOR', 'MASTER.TECHNICAL.READ'
) x ON x.role_code = r.code;
