INSERT INTO permissions(code,name) VALUES('DEALER_HANDOVER','Phân công và bàn giao đại lý');
INSERT INTO catalog_locks(name) VALUES('DEALER_ASSIGNMENTS');
INSERT INTO role_permissions(role_id,permission_id) SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.code='SALES_MANAGER' AND p.code='DEALER_HANDOVER';
CREATE TABLE dealer_handover_batches (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 batch_key CHAR(36) NOT NULL UNIQUE,
 actor_id BIGINT UNSIGNED NOT NULL,
 from_staff_id BIGINT UNSIGNED NOT NULL,
 to_staff_id BIGINT UNSIGNED NOT NULL,
 territory_id BIGINT UNSIGNED NULL,
 dealer_id BIGINT UNSIGNED NULL,
 reason VARCHAR(1000) NOT NULL,
 status ENUM('PREVIEW','CONFIRMED') NOT NULL DEFAULT 'PREVIEW',
 total_items INT NOT NULL DEFAULT 0,
 expires_at TIMESTAMP(6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 confirmed_at TIMESTAMP(6) NULL,
 FOREIGN KEY(actor_id) REFERENCES users(id),FOREIGN KEY(from_staff_id) REFERENCES users(id),FOREIGN KEY(to_staff_id) REFERENCES users(id),FOREIGN KEY(territory_id) REFERENCES territories(id),
 FOREIGN KEY(dealer_id) REFERENCES dealers(id),
 INDEX(actor_id,status,created_at,id)
);
CREATE TABLE dealer_handover_items (
 batch_id BIGINT UNSIGNED NOT NULL,
 dealer_id BIGINT UNSIGNED NOT NULL,
 before_version BIGINT NOT NULL,
 from_staff_id BIGINT UNSIGNED NOT NULL,
 territory_id BIGINT UNSIGNED NOT NULL,
 after_version BIGINT NULL,
 PRIMARY KEY(batch_id,dealer_id),
 FOREIGN KEY(batch_id) REFERENCES dealer_handover_batches(id),FOREIGN KEY(dealer_id) REFERENCES dealers(id),FOREIGN KEY(from_staff_id) REFERENCES users(id),FOREIGN KEY(territory_id) REFERENCES territories(id)
);
