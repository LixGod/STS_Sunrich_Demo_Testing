-- Existing invoice and job cost lines retain their saved tax snapshots; tax codes are no longer maintained.
ALTER TABLE invoice_line
    DROP FOREIGN KEY fk_invoice_line_tax,
    DROP COLUMN tax_code_id;

ALTER TABLE job_cost_line
    DROP FOREIGN KEY job_cost_line_ibfk_2,
    DROP COLUMN tax_code_id;

DROP TABLE tax_code;

UPDATE permission SET description='View currencies, cost heads and tariffs'
WHERE code='MASTER.FINANCE.READ';
UPDATE permission SET description='Manage currencies, cost heads and tariffs'
WHERE code='MASTER.FINANCE.WRITE';
