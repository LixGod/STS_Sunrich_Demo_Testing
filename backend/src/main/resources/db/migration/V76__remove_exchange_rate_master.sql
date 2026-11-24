-- Document conversion rates and quotation INR rates retain their saved values.
ALTER TABLE job_cost_document
    DROP FOREIGN KEY job_cost_document_ibfk_5,
    DROP FOREIGN KEY job_cost_document_ibfk_6,
    DROP COLUMN base_fx_id,
    DROP COLUMN job_fx_id;

DROP TABLE fx_rate;

UPDATE permission SET description='View currencies, tax codes, cost heads and tariffs'
WHERE code='MASTER.FINANCE.READ';
UPDATE permission SET description='Manage currencies, tax codes, cost heads and tariffs'
WHERE code='MASTER.FINANCE.WRITE';
