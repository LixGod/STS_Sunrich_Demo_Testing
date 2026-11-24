-- Phase 3b: job-scoped request and collection. Candidate template rows remain unpublished
-- until Ship2Ship resolves the nine-versus-ten count and signed checklist content.
ALTER TABLE sts_location ADD COLUMN radius_nm DECIMAL(8,3) NULL;
ALTER TABLE sts_location ADD CONSTRAINT ck_sts_location_radius CHECK (radius_nm IS NULL OR radius_nm > 0);

CREATE TABLE job_request_template (
    id BINARY(16) NOT NULL PRIMARY KEY,
    version_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    name VARCHAR(150) NOT NULL,
    email_subject VARCHAR(300) NOT NULL,
    email_body TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL,
    UNIQUE KEY uk_request_template_version (version_no)
) ENGINE=InnoDB;

CREATE TABLE job_request_template_item (
    id BINARY(16) NOT NULL PRIMARY KEY,
    template_id BINARY(16) NOT NULL,
    sort_order INT NOT NULL,
    code VARCHAR(40) NOT NULL,
    label VARCHAR(200) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    applies_to VARCHAR(20) NOT NULL COMMENT 'JOB | EACH_VESSEL',
    document_type_id BINARY(16) NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE KEY uk_request_template_item (template_id, code),
    CONSTRAINT fk_request_template_item_template FOREIGN KEY (template_id) REFERENCES job_request_template(id) ON DELETE RESTRICT,
    CONSTRAINT fk_request_template_item_type FOREIGN KEY (document_type_id) REFERENCES document_type(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE job_document_request (
    id BINARY(16) NOT NULL PRIMARY KEY,
    job_id BINARY(16) NOT NULL,
    template_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL,
    UNIQUE KEY uk_job_document_request (job_id),
    CONSTRAINT fk_job_document_request_job FOREIGN KEY (job_id) REFERENCES sts_job(id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_document_request_template FOREIGN KEY (template_id) REFERENCES job_request_template(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE job_request_item (
    id BINARY(16) NOT NULL PRIMARY KEY,
    request_id BINARY(16) NOT NULL,
    job_vessel_id BINARY(16) NULL,
    code VARCHAR(40) NOT NULL,
    label VARCHAR(200) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    document_type_id BINARY(16) NULL,
    required BOOLEAN NOT NULL,
    sort_order INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    response_text VARCHAR(1000) NULL,
    review_comment VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL,
    KEY ix_job_request_item_request (request_id, job_vessel_id, status, sort_order),
    CONSTRAINT fk_job_request_item_request FOREIGN KEY (request_id) REFERENCES job_document_request(id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_request_item_vessel FOREIGN KEY (job_vessel_id) REFERENCES job_vessel(id) ON DELETE RESTRICT,
    CONSTRAINT fk_job_request_item_type FOREIGN KEY (document_type_id) REFERENCES document_type(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE job_request_evidence (
    id BINARY(16) NOT NULL PRIMARY KEY,
    item_id BINARY(16) NOT NULL,
    document_version_id BINARY(16) NOT NULL,
    linked_at DATETIME(6) NOT NULL,
    linked_by BINARY(16) NOT NULL,
    UNIQUE KEY uk_job_request_evidence_document (item_id, document_version_id),
    CONSTRAINT fk_request_evidence_item FOREIGN KEY (item_id) REFERENCES job_request_item(id) ON DELETE RESTRICT,
    CONSTRAINT fk_request_evidence_document FOREIGN KEY (document_version_id) REFERENCES document_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE job_request_event (
    id BINARY(16) NOT NULL PRIMARY KEY,
    item_id BINARY(16) NOT NULL,
    old_status VARCHAR(20) NULL,
    new_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NULL,
    actor_id BINARY(16) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    KEY ix_request_event_item (item_id, occurred_at),
    CONSTRAINT fk_request_event_item FOREIGN KEY (item_id) REFERENCES job_request_item(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE checklist_template (
    id BINARY(16) NOT NULL PRIMARY KEY,
    code VARCHAR(40) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    name VARCHAR(200) NOT NULL,
    applies_to VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL,
    UNIQUE KEY uk_checklist_template_version (code, version_no)
) ENGINE=InnoDB;

CREATE TABLE checklist_template_item (
    id BINARY(16) NOT NULL PRIMARY KEY,
    template_id BINARY(16) NOT NULL,
    sort_order INT NOT NULL,
    code VARCHAR(40) NOT NULL,
    label VARCHAR(300) NOT NULL,
    answer_type VARCHAR(20) NOT NULL,
    required BOOLEAN NOT NULL,
    UNIQUE KEY uk_checklist_template_item (template_id, code),
    CONSTRAINT fk_checklist_item_template FOREIGN KEY (template_id) REFERENCES checklist_template(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE job_checklist_response (
    id BINARY(16) NOT NULL PRIMARY KEY,
    job_id BINARY(16) NOT NULL,
    job_vessel_id BINARY(16) NULL,
    template_id BINARY(16) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    signed_document_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    created_by BINARY(16) NULL,
    updated_at DATETIME(6) NOT NULL,
    updated_by BINARY(16) NULL,
    version BIGINT NOT NULL,
    KEY ix_job_checklist_response (job_id, job_vessel_id),
    CONSTRAINT fk_checklist_response_job FOREIGN KEY (job_id) REFERENCES sts_job(id) ON DELETE RESTRICT,
    CONSTRAINT fk_checklist_response_vessel FOREIGN KEY (job_vessel_id) REFERENCES job_vessel(id) ON DELETE RESTRICT,
    CONSTRAINT fk_checklist_response_template FOREIGN KEY (template_id) REFERENCES checklist_template(id) ON DELETE RESTRICT,
    CONSTRAINT fk_checklist_response_document FOREIGN KEY (signed_document_id) REFERENCES document_version(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE job_checklist_answer (
    id BINARY(16) NOT NULL PRIMARY KEY,
    response_id BINARY(16) NOT NULL,
    item_code VARCHAR(40) NOT NULL,
    answer_text VARCHAR(1000) NULL,
    UNIQUE KEY uk_job_checklist_answer (response_id, item_code),
    CONSTRAINT fk_checklist_answer_response FOREIGN KEY (response_id) REFERENCES job_checklist_response(id) ON DELETE RESTRICT
) ENGINE=InnoDB;

INSERT INTO permission (code, module, description) VALUES
 ('TECH.JOB.READ','TECHNICAL','View job technical collection and review'),
 ('TECH.JOB.WRITE','TECHNICAL','Record job documents, checklist and voyage data'),
 ('TECH.JOB.REVIEW','TECHNICAL','Review job collection and verify voyage data');
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('TECH.JOB.READ','TECH.JOB.WRITE','TECH.JOB.REVIEW')
WHERE r.code IN ('SYSTEM_ADMIN','TECHNICAL');
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, p.code FROM role r JOIN permission p ON p.code IN ('TECH.JOB.READ','TECH.JOB.WRITE')
WHERE r.code = 'OPERATIONS';
INSERT INTO role_permission (role_id, permission_code)
SELECT r.id, 'TECH.JOB.READ' FROM role r WHERE r.code IN ('COMMERCIAL','HSE_REVIEWER','MANAGEMENT_APPROVER','AUDITOR');

-- Neutral candidate content only. A technical administrator must reconcile the source's
-- "nine items" statement with its ten listed categories, configure checklist content,
-- and explicitly publish a template before a job request can be created.
INSERT INTO document_type (id,code,name,applies_to,required,active,created_at,updated_at,version) VALUES
 (UUID_TO_BIN('01920000-0000-7000-8000-000000000907'),'IMO-CREW','IMO crew list','VESSEL',FALSE,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000000908'),'LAST-STS','Last STS drill record','VESSEL',FALSE,TRUE,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0);
INSERT INTO job_request_template (id,version_no,status,name,email_subject,email_body,created_at,updated_at,version)
VALUES (UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),1,'DRAFT','Step 4 candidate request',
 'Document request for {JOB_NUMBER}',
 'Please review the following requested information for {MOTHER_VESSEL} and {DAUGHTER_VESSEL} at {STS_LOCATION}.\n\n{REQUEST_ITEMS}\n\nLocation: {LOCATION_DMS}; radius: {RADIUS_NM} nautical miles.\n\n[Appointment wording pending owner approval]',
 UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),0);
INSERT INTO job_request_template_item (id,template_id,sort_order,code,label,kind,applies_to,document_type_id,required) VALUES
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001011'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),10,'Q88','Q88 questionnaire','DOCUMENT','EACH_VESSEL',UUID_TO_BIN('01920000-0000-7000-8000-000000000901'),TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001012'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),20,'REDUCER','10-inch reducer availability','DECLARATION','EACH_VESSEL',NULL,TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001013'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),30,'MOORING','Mooring arrangement plan','DOCUMENT','EACH_VESSEL',UUID_TO_BIN('01920000-0000-7000-8000-000000000903'),TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001014'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),40,'PARTICULARS','Ship particulars','DOCUMENT','EACH_VESSEL',UUID_TO_BIN('01920000-0000-7000-8000-000000000904'),TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001015'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),50,'GA','General arrangement plan','DOCUMENT','EACH_VESSEL',UUID_TO_BIN('01920000-0000-7000-8000-000000000902'),TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001016'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),60,'WHATSAPP','On-board WhatsApp contact','CONTACT','EACH_VESSEL',NULL,TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001017'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),70,'CREW','IMO crew list','DOCUMENT','EACH_VESSEL',UUID_TO_BIN('01920000-0000-7000-8000-000000000907'),TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001018'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),80,'MANIFOLD','Manifold details','DOCUMENT','EACH_VESSEL',UUID_TO_BIN('01920000-0000-7000-8000-000000000905'),TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001019'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),90,'VOYAGE','Arrival/departure voyage conditions','VOYAGE_DATA','EACH_VESSEL',NULL,TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001020'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),100,'DRILL','Last STS drill record','DOCUMENT','EACH_VESSEL',UUID_TO_BIN('01920000-0000-7000-8000-000000000908'),TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001021'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),110,'CHECKLIST-1','Signed checklist 1 — pending owner name and items','CHECKLIST','EACH_VESSEL',NULL,TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001022'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),120,'CHECKLIST-2','Signed checklist 2 — pending owner name and items','CHECKLIST','EACH_VESSEL',NULL,TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001023'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),130,'CHECKLIST-3','Signed checklist 3 — pending owner name and items','CHECKLIST','EACH_VESSEL',NULL,TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001024'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),140,'CHECKLIST-4','Signed checklist 4 — pending owner name and items','CHECKLIST','EACH_VESSEL',NULL,TRUE),
 (UUID_TO_BIN('01920000-0000-7000-8000-000000001025'),UUID_TO_BIN('01920000-0000-7000-8000-000000001001'),150,'CHECKLIST-5','Signed checklist 5 — pending owner name and items','CHECKLIST','EACH_VESSEL',NULL,TRUE);
