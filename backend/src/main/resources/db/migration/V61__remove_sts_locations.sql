ALTER TABLE enquiry DROP FOREIGN KEY fk_enquiry_location;
ALTER TABLE enquiry DROP COLUMN sts_location_id;
ALTER TABLE sts_job DROP COLUMN sts_location_id;
DROP TABLE sts_location;

UPDATE permission SET description = 'View branches' WHERE code = 'MASTER.ORG.READ';
UPDATE permission SET description = 'Manage branches' WHERE code = 'MASTER.ORG.WRITE';

UPDATE job_request_template
SET email_body = REPLACE(REPLACE(REPLACE(email_body,
    ' at {STS_LOCATION}', ''),
    ' within a radius of {RADIUS_NM} nautical miles from {LOCATION_DMS}', ''),
    '\n\nLocation: {LOCATION_DMS}; radius: {RADIUS_NM} nautical miles.', ''),
    updated_at = UTC_TIMESTAMP(6), version = version + 1
WHERE email_body LIKE '%{STS_LOCATION}%'
   OR email_body LIKE '%{LOCATION_DMS}%'
   OR email_body LIKE '%{RADIUS_NM}%';
