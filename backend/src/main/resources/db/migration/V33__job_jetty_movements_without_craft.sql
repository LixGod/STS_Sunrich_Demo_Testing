ALTER TABLE craft_movement
    MODIFY craft_id BINARY(16) NULL,
    MODIFY vendor_id BINARY(16) NULL;

ALTER TABLE craft_movement
    ADD CONSTRAINT ck_movement_craft_vendor_pair CHECK
    ((craft_id IS NULL AND vendor_id IS NULL) OR (craft_id IS NOT NULL AND vendor_id IS NOT NULL));
