-- Phase 3c: Joint Plan of Operation (JPO, "Appendix C") per job, with versioned annex templates (D13), approval by a
-- second person (FR-TECH-02, FR-UM-05), an immutable issued PDF and an Excel export. Sections fed by later modules
-- (mooring plan 3d, operating limits 3e, equipment and support craft in Phase 4) are entered manually for now.

CREATE TABLE annex_template (
    id           BINARY(16)   NOT NULL,
    version_no   INT          NOT NULL,
    status       VARCHAR(20)  NOT NULL COMMENT 'DRAFT | PUBLISHED | RETIRED',
    name         VARCHAR(150) NOT NULL,
    owner_confirmation VARCHAR(500) NULL COMMENT 'Who confirmed the wording for Ship2Ship, recorded at publication',
    published_by BINARY(16)   NULL,
    published_at DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL,
    created_by   BINARY(16)   NULL,
    updated_at   DATETIME(6)  NOT NULL,
    updated_by   BINARY(16)   NULL,
    version      BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_annex_template_version (version_no),
    CONSTRAINT ck_annex_template_second_person CHECK (published_by IS NULL OR updated_by IS NULL OR published_by <> updated_by)
) ENGINE = InnoDB;

CREATE TABLE annex_template_section (
    id          BINARY(16)   NOT NULL,
    template_id BINARY(16)   NOT NULL,
    sort_order  INT          NOT NULL,
    code        VARCHAR(40)  NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        MEDIUMTEXT   NOT NULL,
    applies_when VARCHAR(30) NOT NULL COMMENT 'ALWAYS | REVERSE_LIGHTERING | SIMILAR_LOA (ticked on the JPO)',
    PRIMARY KEY (id),
    UNIQUE KEY uk_annex_section (template_id, code),
    CONSTRAINT fk_annex_section_template FOREIGN KEY (template_id) REFERENCES annex_template (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

-- Candidate structure only: the section headings of the JPO annex. The approved wording is pasted in by a technical
-- administrator and confirmed by Ship2Ship before publication (owner question 7); no text is assumed here.
INSERT INTO annex_template (id, version_no, status, name, created_at, updated_at, version)
VALUES (UUID_TO_BIN('01920000-0000-7000-8000-000000001101'), 1, 'DRAFT', 'JPO annex (wording pending)', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0);

INSERT INTO annex_template_section (id, template_id, sort_order, code, title, body, applies_when) VALUES
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001111'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'),  10, 'RISK',        'Operational risk assessment', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001112'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'),  20, 'REVERSE',     'Reverse lightering', '', 'REVERSE_LIGHTERING'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001113'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'),  30, 'SIMILAR-LOA', 'Berthing vessels of similar LOA', '', 'SIMILAR_LOA'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001114'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'),  40, 'MOORING-MASTER', 'Mooring Master roles and responsibility', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001115'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'),  50, 'VESSEL',      'Vessel responsibility', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001116'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'),  60, 'FENDERING',   'Fendering', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001117'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'),  70, 'WATCHKEEPING','Navigational watchkeeping during STS', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001118'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'),  80, 'CRITICAL',    'Supervision of critical stages', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001119'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'),  90, 'MEETING',     'Pre-transfer meeting', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001120'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'), 100, 'WEATHER',     'Weather guidelines', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001121'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'), 110, 'CRAFT',       'Service craft and launches', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001122'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'), 120, 'SECURITY',    'Security requirements', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001123'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'), 130, 'CHECKS',      'Checks during operations', '', 'ALWAYS'),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001124'), UUID_TO_BIN('01920000-0000-7000-8000-000000001101'), 140, 'EMERGENCIES', 'Emergencies, stop work and contingency plans', '', 'ALWAYS');

-- One JPO per job; each revision is a complete plan.
CREATE TABLE jpo (
    id         BINARY(16)  NOT NULL,
    job_id     BINARY(16)  NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16)  NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16)  NULL,
    version    BIGINT      NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jpo_job (job_id),
    CONSTRAINT fk_jpo_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE jpo_revision (
    id                BINARY(16)   NOT NULL,
    jpo_id            BINARY(16)   NOT NULL,
    revision_no       INT          NOT NULL,
    status            VARCHAR(20)  NOT NULL COMMENT 'DRAFT | SUBMITTED | APPROVED | ISSUED | SUPERSEDED',
    content_json      MEDIUMTEXT   NOT NULL COMMENT 'Structured, schema-checked JPO fields entered by users',
    snapshot_json     MEDIUMTEXT   NULL COMMENT 'Job data (vessels, cargo, verified voyage values, checklists) frozen at submission',
    annex_template_id BINARY(16)   NULL COMMENT 'Published annex version frozen at submission',
    submitted_by      BINARY(16)   NULL,
    submitted_at      DATETIME(6)  NULL,
    decided_by        BINARY(16)   NULL,
    decided_at        DATETIME(6)  NULL,
    returned_reason   VARCHAR(500) NULL,
    issued_by         BINARY(16)   NULL,
    issued_at         DATETIME(6)  NULL,
    pdf               MEDIUMBLOB   NULL COMMENT 'Issued PDF, immutable',
    pdf_sha256        CHAR(64)     NULL,
    created_at        DATETIME(6)  NOT NULL,
    created_by        BINARY(16)   NULL,
    updated_at        DATETIME(6)  NOT NULL,
    updated_by        BINARY(16)   NULL,
    version           BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jpo_revision (jpo_id, revision_no),
    KEY ix_jpo_revision_status (status, submitted_at),
    CONSTRAINT fk_jpo_revision_jpo FOREIGN KEY (jpo_id) REFERENCES jpo (id) ON DELETE RESTRICT,
    CONSTRAINT fk_jpo_revision_annex FOREIGN KEY (annex_template_id) REFERENCES annex_template (id) ON DELETE RESTRICT,
    CONSTRAINT ck_jpo_revision_no_self_approval CHECK (decided_by IS NULL OR submitted_by IS NULL OR decided_by <> submitted_by)
) ENGINE = InnoDB;

-- "Agreed and accepted" on an issued revision: recorded in the app (name, capacity, time). A scanned signed copy can be
-- attached as a job document; legal e-signature is pending (owner question 6).
CREATE TABLE jpo_acceptance (
    id          BINARY(16)   NOT NULL,
    revision_id BINARY(16)   NOT NULL,
    party       VARCHAR(30)  NOT NULL COMMENT 'DISCHARGING_MASTER | RECEIVING_MASTER | STS_SUPERINTENDENT',
    person_name VARCHAR(150) NOT NULL,
    accepted_at DATETIME(6)  NOT NULL,
    signed_document_id BINARY(16) NULL,
    recorded_by BINARY(16)   NOT NULL,
    recorded_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_jpo_acceptance (revision_id, party),
    CONSTRAINT fk_jpo_acceptance_revision FOREIGN KEY (revision_id) REFERENCES jpo_revision (id) ON DELETE RESTRICT,
    CONSTRAINT fk_jpo_acceptance_document FOREIGN KEY (signed_document_id) REFERENCES document_version (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

INSERT INTO permission (code, module, description) VALUES
    ('TECH.JPO.READ',    'TECHNICAL', 'View joint plans of operation within scope'),
    ('TECH.JPO.WRITE',   'TECHNICAL', 'Prepare, submit and issue joint plans of operation within scope'),
    ('TECH.JPO.APPROVE', 'TECHNICAL', 'Approve joint plans of operation prepared by someone else');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('TECH.JPO.READ', 'TECH.JPO.WRITE', 'TECH.JPO.APPROVE')
WHERE r.code IN ('SYSTEM_ADMIN', 'TECHNICAL');

-- Baseline mapping, to be confirmed by Ship2Ship (SRS §27).
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'TECH.JPO.READ' FROM role r WHERE r.code IN ('OPERATIONS', 'COMMERCIAL', 'HSE_REVIEWER', 'MANAGEMENT_APPROVER', 'AUDITOR');
