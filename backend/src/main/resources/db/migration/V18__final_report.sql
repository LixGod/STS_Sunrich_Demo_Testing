-- Phase 6a: final operation report (FR-REP-01, FR-DOC-01, D13, FR-UM-05). One report per job with revisions; the job
-- records (events, cargo, incidents, Q88 references) are frozen at submission; approval by someone other than the
-- submitter; the issued PDF is immutable with SHA-256. Equipment and attendance are entered on the report until 4a's
-- allocations are committed.

CREATE TABLE final_report (
    id         BINARY(16)  NOT NULL,
    job_id     BINARY(16)  NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_final_report_job (job_id),
    CONSTRAINT fk_final_report_job FOREIGN KEY (job_id) REFERENCES sts_job (id) ON DELETE RESTRICT
) ENGINE = InnoDB;

CREATE TABLE final_report_revision (
    id               BINARY(16)   NOT NULL,
    report_id        BINARY(16)   NOT NULL,
    revision_no      INT          NOT NULL,
    status           VARCHAR(20)  NOT NULL COMMENT 'DRAFT | SUBMITTED | APPROVED | ISSUED | SUPERSEDED',
    template_version INT          NOT NULL COMMENT 'Version of the report layout in code (D13)',
    content_json     MEDIUMTEXT   NOT NULL COMMENT 'Entered on the report: VHF channel, summary, remarks, equipment, attendance, attachments',
    snapshot_json    MEDIUMTEXT   NULL COMMENT 'Job records frozen at submission',
    submitted_by     BINARY(16)   NULL,
    submitted_at     DATETIME(6)  NULL,
    decided_by       BINARY(16)   NULL,
    decided_at       DATETIME(6)  NULL,
    returned_reason  VARCHAR(500) NULL,
    issued_by        BINARY(16)   NULL,
    issued_at        DATETIME(6)  NULL,
    pdf              MEDIUMBLOB   NULL,
    pdf_sha256       CHAR(64)     NULL,
    created_at       DATETIME(6)  NOT NULL,
    created_by       BINARY(16)   NULL,
    updated_at       DATETIME(6)  NOT NULL,
    updated_by       BINARY(16)   NULL,
    version          BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_final_report_revision (report_id, revision_no),
    CONSTRAINT fk_final_report_revision_report FOREIGN KEY (report_id) REFERENCES final_report (id) ON DELETE RESTRICT,
    CONSTRAINT ck_final_report_no_self_approval CHECK (decided_by IS NULL OR submitted_by IS NULL OR decided_by <> submitted_by)
) ENGINE = InnoDB;

INSERT INTO permission (code, module, description) VALUES
    ('REPORT.FINAL.READ',    'REPORTS', 'View final operation reports within scope'),
    ('REPORT.FINAL.WRITE',   'REPORTS', 'Prepare, submit and issue final operation reports within scope'),
    ('REPORT.FINAL.APPROVE', 'REPORTS', 'Approve final operation reports prepared by someone else');

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('REPORT.FINAL.READ', 'REPORT.FINAL.WRITE', 'REPORT.FINAL.APPROVE')
WHERE r.code = 'SYSTEM_ADMIN';

-- Baseline mapping, to be confirmed by Ship2Ship (SRS §27).
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('REPORT.FINAL.READ', 'REPORT.FINAL.WRITE') WHERE r.code = 'OPERATIONS';

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('REPORT.FINAL.READ', 'REPORT.FINAL.APPROVE') WHERE r.code = 'MANAGEMENT_APPROVER';

INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'REPORT.FINAL.READ' FROM role r WHERE r.code IN ('TECHNICAL', 'COMMERCIAL', 'HSE_REVIEWER', 'FINANCE', 'AUDITOR');
