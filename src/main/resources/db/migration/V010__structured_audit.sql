ALTER TABLE audit_logs ADD COLUMN object_type VARCHAR(50),ADD COLUMN object_id BIGINT UNSIGNED,
 ADD COLUMN before_values JSON,ADD COLUMN after_values JSON,ADD COLUMN before_cost JSON,ADD COLUMN after_cost JSON,
 ADD INDEX idx_audit_object_time(object_type,object_id,occurred_at);
