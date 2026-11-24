-- Phase classification for STS document collection:
-- PRE_ARRIVAL (Before arrival - needed for mooring plan & JPO issue)
-- ON_ARRIVAL (On arrival & during transfer)
-- POST_DEPARTURE (After departure / completion)

ALTER TABLE job_request_template_item
    ADD COLUMN phase VARCHAR(20) NOT NULL DEFAULT 'PRE_ARRIVAL';

ALTER TABLE job_request_item
    ADD COLUMN phase VARCHAR(20) NOT NULL DEFAULT 'PRE_ARRIVAL';

CREATE INDEX ix_job_request_item_phase ON job_request_item (request_id, phase, status);

-- Document types for signed checklists and Statement of Facts so files can be uploaded directly
INSERT INTO document_type (id, code, name, applies_to, required, active, created_at, updated_at, version) VALUES
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000912'), 'CHECKLIST-2', 'Signed Checklist 2 (Safety before ops)', 'VESSEL', TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000913'), 'CHECKLIST-3', 'Signed Checklist 3 (Before mooring)', 'VESSEL', TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000914'), 'CHECKLIST-4', 'Signed Checklist 4 (Before cargo transfer)', 'VESSEL', TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000915'), 'CHECKLIST-5', 'Signed Checklist 5 (During cargo transfer)', 'VESSEL', TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000916'), 'CHECKLIST-6', 'Signed Checklist 6 (Before unmooring)', 'VESSEL', TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0),
    (UUID_TO_BIN('01920000-0000-7000-8000-000000000917'), 'SOF-SIGNED',   'Signed Statement of Facts (SOF)', 'JOB', TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0)
ON DUPLICATE KEY UPDATE name=VALUES(name), active=VALUES(active);

-- Insert operational checklist & closing items into published templates only (keeps Step 4 candidate draft untouched)
INSERT INTO job_request_template_item (id, template_id, sort_order, code, label, kind, applies_to, document_type_id, required, phase)
SELECT UUID_TO_BIN(UUID()), t.id, 110, 'CHECKLIST-2', 'Signed Checklist 2 (Safety before ops)', 'DOCUMENT', 'EACH_VESSEL', UUID_TO_BIN('01920000-0000-7000-8000-000000000912'), TRUE, 'PRE_ARRIVAL'
FROM job_request_template t
WHERE t.status = 'PUBLISHED'
  AND NOT EXISTS (SELECT 1 FROM job_request_template_item i WHERE i.template_id = t.id AND i.code = 'CHECKLIST-2');

INSERT INTO job_request_template_item (id, template_id, sort_order, code, label, kind, applies_to, document_type_id, required, phase)
SELECT UUID_TO_BIN(UUID()), t.id, 210, 'CHECKLIST-3', 'Signed Checklist 3 (Before mooring)', 'DOCUMENT', 'EACH_VESSEL', UUID_TO_BIN('01920000-0000-7000-8000-000000000913'), TRUE, 'ON_ARRIVAL'
FROM job_request_template t
WHERE t.status = 'PUBLISHED'
  AND NOT EXISTS (SELECT 1 FROM job_request_template_item i WHERE i.template_id = t.id AND i.code = 'CHECKLIST-3');

INSERT INTO job_request_template_item (id, template_id, sort_order, code, label, kind, applies_to, document_type_id, required, phase)
SELECT UUID_TO_BIN(UUID()), t.id, 220, 'CHECKLIST-4', 'Signed Checklist 4 (Before cargo transfer)', 'DOCUMENT', 'EACH_VESSEL', UUID_TO_BIN('01920000-0000-7000-8000-000000000914'), TRUE, 'ON_ARRIVAL'
FROM job_request_template t
WHERE t.status = 'PUBLISHED'
  AND NOT EXISTS (SELECT 1 FROM job_request_template_item i WHERE i.template_id = t.id AND i.code = 'CHECKLIST-4');

INSERT INTO job_request_template_item (id, template_id, sort_order, code, label, kind, applies_to, document_type_id, required, phase)
SELECT UUID_TO_BIN(UUID()), t.id, 230, 'CHECKLIST-5', 'Signed Checklist 5 (During cargo transfer)', 'DOCUMENT', 'EACH_VESSEL', UUID_TO_BIN('01920000-0000-7000-8000-000000000915'), TRUE, 'ON_ARRIVAL'
FROM job_request_template t
WHERE t.status = 'PUBLISHED'
  AND NOT EXISTS (SELECT 1 FROM job_request_template_item i WHERE i.template_id = t.id AND i.code = 'CHECKLIST-5');

INSERT INTO job_request_template_item (id, template_id, sort_order, code, label, kind, applies_to, document_type_id, required, phase)
SELECT UUID_TO_BIN(UUID()), t.id, 310, 'CHECKLIST-6', 'Signed Checklist 6 (Before unmooring)', 'DOCUMENT', 'EACH_VESSEL', UUID_TO_BIN('01920000-0000-7000-8000-000000000916'), TRUE, 'POST_DEPARTURE'
FROM job_request_template t
WHERE t.status = 'PUBLISHED'
  AND NOT EXISTS (SELECT 1 FROM job_request_template_item i WHERE i.template_id = t.id AND i.code = 'CHECKLIST-6');

INSERT INTO job_request_template_item (id, template_id, sort_order, code, label, kind, applies_to, document_type_id, required, phase)
SELECT UUID_TO_BIN(UUID()), t.id, 320, 'SOF-SIGNED', 'Signed Statement of Facts (SOF)', 'DOCUMENT', 'JOB', UUID_TO_BIN('01920000-0000-7000-8000-000000000917'), TRUE, 'POST_DEPARTURE'
FROM job_request_template t
WHERE t.status = 'PUBLISHED'
  AND NOT EXISTS (SELECT 1 FROM job_request_template_item i WHERE i.template_id = t.id AND i.code = 'SOF-SIGNED');

-- Also add to existing active job_request_item records
INSERT INTO job_request_item (id, request_id, job_vessel_id, code, label, kind, document_type_id, required, sort_order, status, created_at, created_by, updated_at, updated_by, version, phase)
SELECT UUID_TO_BIN(UUID()), r.id, v.id, t.code, t.label, t.kind, t.document_type_id, t.required, t.sort_order, 'PENDING', UTC_TIMESTAMP(6), NULL, UTC_TIMESTAMP(6), NULL, 0, t.phase
FROM job_document_request r
JOIN sts_job j ON j.id = r.job_id
JOIN job_vessel v ON v.job_id = j.id
JOIN job_request_template_item t ON t.template_id = r.template_id AND t.applies_to = 'EACH_VESSEL' AND t.code IN ('CHECKLIST-2', 'CHECKLIST-3', 'CHECKLIST-4', 'CHECKLIST-5', 'CHECKLIST-6')
WHERE NOT EXISTS (
    SELECT 1 FROM job_request_item existing WHERE existing.request_id = r.id AND existing.job_vessel_id = v.id AND existing.code = t.code
);

INSERT INTO job_request_item (id, request_id, job_vessel_id, code, label, kind, document_type_id, required, sort_order, status, created_at, created_by, updated_at, updated_by, version, phase)
SELECT UUID_TO_BIN(UUID()), r.id, NULL, t.code, t.label, t.kind, t.document_type_id, t.required, t.sort_order, 'PENDING', UTC_TIMESTAMP(6), NULL, UTC_TIMESTAMP(6), NULL, 0, t.phase
FROM job_document_request r
JOIN job_request_template_item t ON t.template_id = r.template_id AND t.applies_to = 'JOB' AND t.code = 'SOF-SIGNED'
WHERE NOT EXISTS (
    SELECT 1 FROM job_request_item existing WHERE existing.request_id = r.id AND existing.job_vessel_id IS NULL AND existing.code = t.code
);
