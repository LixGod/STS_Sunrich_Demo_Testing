CREATE TABLE job_extra_invoice (
 job_id BINARY(16) NOT NULL, invoice_id BINARY(16) NOT NULL PRIMARY KEY,
 fingerprint CHAR(64) NOT NULL,clock_snapshot MEDIUMTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL,created_by BINARY(16) NOT NULL,
 UNIQUE KEY uk_extra_episode(job_id,fingerprint),
 FOREIGN KEY(job_id) REFERENCES sts_job(id) ON DELETE RESTRICT,
 FOREIGN KEY(invoice_id) REFERENCES invoice(id) ON DELETE RESTRICT
) ENGINE=InnoDB;
