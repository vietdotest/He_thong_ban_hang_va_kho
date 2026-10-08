INSERT INTO permissions(code,name) VALUES('DEALER_STATUS_MANAGE','Khóa và mở giao dịch đại lý');
INSERT INTO role_permissions(role_id,permission_id) SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.code IN ('SALES_MANAGER','ACCOUNTANT') AND p.code='DEALER_STATUS_MANAGE';
ALTER TABLE dealers ADD COLUMN transaction_locked BOOLEAN NOT NULL DEFAULT FALSE,ADD COLUMN transaction_lock_reason VARCHAR(1000) NULL,ADD COLUMN transaction_locked_by BIGINT UNSIGNED NULL,ADD COLUMN transaction_locked_at TIMESTAMP(6) NULL,ADD FOREIGN KEY(transaction_locked_by) REFERENCES users(id);
CREATE TABLE dealer_status_history (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 dealer_id BIGINT UNSIGNED NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL,
 old_locked BOOLEAN NOT NULL,new_locked BOOLEAN NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 dealer_version BIGINT NOT NULL,
 changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(dealer_id) REFERENCES dealers(id),FOREIGN KEY(actor_id) REFERENCES users(id),INDEX(dealer_id,changed_at,id)
);
