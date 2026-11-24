-- Signed checklists arrive after the operation; they are not part of the initial vessel document request.
DELETE i FROM job_request_template_item i
JOIN job_request_template t ON t.id=i.template_id
WHERE t.id=UUID_TO_BIN('01920000-0000-7000-8000-000000001001') AND t.status='DRAFT'
  AND i.code IN ('CHECKLIST-1','CHECKLIST-2','CHECKLIST-3','CHECKLIST-4','CHECKLIST-5');

UPDATE job_request_template
SET name='Step 4 vessel document request',
    email_subject='STS operation {JOB_NUMBER} - vessel documents required',
    email_body=CONCAT(
        'Dear Sir/Madam,\n\n',
        'We are pleased to inform you that we have been appointed as the STS Operator for the upcoming STS operation at {STS_LOCATION}.\n\n',
        'The operation will be performed for {MOTHER_VESSEL} and {DAUGHTER_VESSEL} within a radius of {RADIUS_NM} nautical miles from {LOCATION_DMS}.\n\n',
        'Please provide the following documents and information on priority:\n{REQUEST_ITEMS}\n\n',
        'Please share all five signed checklists after completion of the STS operation.\n\n',
        'Awaiting your response.\n\nRegards,\nShip2Ship STS Operations'),
    updated_at=UTC_TIMESTAMP(6), version=version+1
WHERE id=UUID_TO_BIN('01920000-0000-7000-8000-000000001001') AND status='DRAFT';
