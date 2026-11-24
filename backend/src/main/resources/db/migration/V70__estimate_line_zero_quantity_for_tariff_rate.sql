ALTER TABLE estimate_line
    DROP CHECK ck_estimate_line_quantity,
    ADD CONSTRAINT ck_estimate_line_quantity CHECK (quantity >= 0);
