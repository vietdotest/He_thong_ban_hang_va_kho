INSERT INTO permissions(code,name) VALUES
('DEALER_READ','Xem đại lý trong phạm vi'),('DEALER_READ_ALL','Xem tất cả đại lý'),
('DEALER_MANAGE','Quản lý hồ sơ đại lý');
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE
(r.code IN ('SALES_MANAGER','ACCOUNTANT','DIRECTOR','SALES') AND p.code='DEALER_READ') OR
(r.code IN ('SALES_MANAGER','ACCOUNTANT','DIRECTOR') AND p.code='DEALER_READ_ALL') OR
(r.code IN ('SALES_MANAGER','ACCOUNTANT') AND p.code='DEALER_MANAGE');

CREATE TABLE dealers (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(50) NOT NULL UNIQUE,
 name VARCHAR(200) NOT NULL,
 tax_code VARCHAR(20) NOT NULL DEFAULT '',
 phone VARCHAR(30) NOT NULL DEFAULT '',
 group_id BIGINT UNSIGNED NOT NULL,
 territory_id BIGINT UNSIGNED NOT NULL,
 primary_staff_id BIGINT UNSIGNED NOT NULL,
 warehouse_id BIGINT UNSIGNED NULL,
 status ENUM('ACTIVE','DISCONTINUED') NOT NULL DEFAULT 'ACTIVE',
 version BIGINT NOT NULL DEFAULT 1,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY(group_id) REFERENCES customer_groups(id),
 FOREIGN KEY(territory_id) REFERENCES territories(id),
 FOREIGN KEY(primary_staff_id) REFERENCES users(id),
 FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),
 INDEX(primary_staff_id,status,id),INDEX(territory_id,group_id,status,id),INDEX(name,id)
);
CREATE TABLE dealer_transaction_references (
 dealer_id BIGINT UNSIGNED NOT NULL,
 reference_type VARCHAR(30) NOT NULL,
 reference_id VARCHAR(100) NOT NULL,
 PRIMARY KEY(dealer_id,reference_type,reference_id),
 FOREIGN KEY(dealer_id) REFERENCES dealers(id)
);
