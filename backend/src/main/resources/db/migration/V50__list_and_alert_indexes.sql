-- Indexes measured with EXPLAIN ANALYZE on synthetic volume (6,000 jobs / 9,000 invoices, and 30,000 / 45,000):
-- each removes a full scan and sort for company-wide users; write cost is about 1-3 µs per inserted row.

-- Finance invoice list, newest first (ORDER BY invoice_date DESC, created_at DESC LIMIT n): 30 ms -> 0.3 ms.
ALTER TABLE invoice ADD KEY ix_invoice_date (invoice_date, created_at);

-- Job register (default ORDER BY created_at DESC) and job history page: 14 ms -> 0.2 ms.
ALTER TABLE sts_job ADD KEY ix_sts_job_created (created_at);

-- Alert refresh, once per rule each minute (rule_code = ? AND condition_active = TRUE): 19 ms -> 0.2 ms per rule.
-- It also serves the rule_code foreign key, so the implicit foreign-key index is dropped.
ALTER TABLE job_alert ADD KEY ix_alert_rule_active (rule_code, condition_active), DROP INDEX rule_code;
