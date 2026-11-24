-- SRS FR-OPS-01: preserve occurrence, device capture and server receipt independently.
CREATE TABLE operation_event_capture (
 event_id BINARY(16) NOT NULL PRIMARY KEY,recorded_by BINARY(16) NOT NULL,client_event_id BINARY(16) NOT NULL,
 device_recorded_at DATETIME(6) NOT NULL,payload_sha256 CHAR(64) NOT NULL,conflict_reason VARCHAR(500) NULL,
 UNIQUE KEY uk_device_event(recorded_by,client_event_id),
 FOREIGN KEY(event_id) REFERENCES operation_event(id) ON DELETE RESTRICT
) ENGINE=InnoDB;
