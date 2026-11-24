-- An incomplete account is an inactive draft until an authentication subject is provided.
ALTER TABLE app_user
    MODIFY external_subject_id VARCHAR(128) NULL COMMENT 'Subject identifier issued by the existing authentication system',
    MODIFY full_name VARCHAR(150) NULL,
    MODIFY work_email VARCHAR(254) NULL;
