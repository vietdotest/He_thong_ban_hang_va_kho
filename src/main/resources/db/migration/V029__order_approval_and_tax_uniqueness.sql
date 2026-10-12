-- An empty tax identifier is optional; populated identifiers are globally unique.
ALTER TABLE dealers
 ADD COLUMN tax_code_key VARCHAR(13) GENERATED ALWAYS AS (NULLIF(REPLACE(TRIM(tax_code),'-',''),'')) STORED,
 ADD CONSTRAINT uq_dealer_tax_code UNIQUE (tax_code_key);

ALTER TABLE orders MODIFY status ENUM('DRAFT','PENDING_APPROVAL','SUBMITTED','REJECTED') NOT NULL DEFAULT 'DRAFT';
ALTER TABLE order_quote_tickets ADD COLUMN purpose ENUM('SUBMIT','APPROVAL') NOT NULL DEFAULT 'SUBMIT';
INSERT INTO permissions(code,name) VALUES('ORDER_APPROVE','Duyệt giá bán dưới sàn');
INSERT INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
 WHERE r.code IN ('SALES_MANAGER','DIRECTOR') AND p.code='ORDER_APPROVE';
CREATE TABLE order_approval_requests (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 order_id BIGINT UNSIGNED NOT NULL UNIQUE,
 requested_by BIGINT UNSIGNED NOT NULL,
 request_key CHAR(36) NOT NULL UNIQUE,
 request_digest CHAR(64) NOT NULL,
 request_snapshot JSON NOT NULL,
 request_reason VARCHAR(1000) NOT NULL,
 status ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
 decision_key CHAR(36) NULL UNIQUE,
 decided_by BIGINT UNSIGNED NULL,
 decision_reason VARCHAR(1000) NULL,
 decision_snapshot JSON NULL,
 requested_at TIMESTAMP(6) NOT NULL,
 decided_at TIMESTAMP(6) NULL,
 FOREIGN KEY(order_id) REFERENCES orders(id),
 FOREIGN KEY(requested_by) REFERENCES users(id),FOREIGN KEY(decided_by) REFERENCES users(id),
 INDEX(status,requested_at,id)
);
