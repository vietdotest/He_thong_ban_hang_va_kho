INSERT INTO permissions(code,name) VALUES('DEALER_CREDIT_MANAGE','Quản lý cấu hình tín dụng đại lý');
INSERT INTO role_permissions(role_id,permission_id) SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.code IN ('SALES_MANAGER','ACCOUNTANT') AND p.code='DEALER_CREDIT_MANAGE';
ALTER TABLE dealers ADD COLUMN credit_limit DECIMAL(19,4) NOT NULL DEFAULT 0,ADD COLUMN debt_days INT NOT NULL DEFAULT 0,ADD CHECK(credit_limit>=0 AND debt_days>=0);
CREATE TABLE dealer_credit_history (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 dealer_id BIGINT UNSIGNED NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL,
 old_limit DECIMAL(19,4) NOT NULL,new_limit DECIMAL(19,4) NOT NULL,
 old_days INT NOT NULL,new_days INT NOT NULL,
 reason VARCHAR(1000) NOT NULL,
 dealer_version BIGINT NOT NULL,
 changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(dealer_id) REFERENCES dealers(id),FOREIGN KEY(actor_id) REFERENCES users(id),INDEX(dealer_id,changed_at,id),
 CHECK(old_limit>=0 AND new_limit>=0 AND old_days>=0 AND new_days>=0)
);
