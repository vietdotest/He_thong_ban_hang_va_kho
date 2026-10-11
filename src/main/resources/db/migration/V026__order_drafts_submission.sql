INSERT INTO permissions(code,name) VALUES
('ORDER_READ','Xem đơn trong phạm vi đại lý'),('ORDER_READ_ALL','Xem toàn bộ đơn'),('ORDER_WRITE','Tạo và gửi đơn nháp');
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE
(r.code IN ('SALES_MANAGER','ACCOUNTANT','DIRECTOR','SALES') AND p.code='ORDER_READ') OR
(r.code IN ('SALES_MANAGER','ACCOUNTANT','DIRECTOR') AND p.code='ORDER_READ_ALL') OR
(r.code IN ('SALES_MANAGER','SALES') AND p.code='ORDER_WRITE');

CREATE TABLE orders (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 dealer_id BIGINT UNSIGNED NOT NULL,address_id BIGINT UNSIGNED NOT NULL,
 desired_delivery DATE NOT NULL,status ENUM('DRAFT','SUBMITTED') NOT NULL DEFAULT 'DRAFT',
 created_by BIGINT UNSIGNED NOT NULL,owner_id BIGINT UNSIGNED NOT NULL,
 creation_key CHAR(36) NOT NULL,creation_hash CHAR(64) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,submit_key CHAR(36) NULL,submitted_at TIMESTAMP(6) NULL,
 pricing_date DATE NULL,group_id BIGINT UNSIGNED NULL,warehouse_id BIGINT UNSIGNED NULL,
 dealer_code_snapshot VARCHAR(50) NULL,dealer_name_snapshot VARCHAR(200) NULL,
 address_snapshot VARCHAR(500) NULL,recipient_snapshot VARCHAR(150) NULL,
 phone_snapshot VARCHAR(30) NULL,directions_snapshot VARCHAR(1000) NULL,
 group_name_snapshot VARCHAR(150) NULL,warehouse_name_snapshot VARCHAR(200) NULL,
 gross_total DECIMAL(19,4) NULL,discount_total DECIMAL(19,4) NULL,net_total DECIMAL(19,4) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE(created_by,creation_key),UNIQUE(owner_id,submit_key),
 FOREIGN KEY(dealer_id) REFERENCES dealers(id),FOREIGN KEY(address_id) REFERENCES dealer_addresses(id),
 FOREIGN KEY(created_by) REFERENCES users(id),FOREIGN KEY(owner_id) REFERENCES users(id),
 FOREIGN KEY(group_id) REFERENCES customer_groups(id),FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),
 INDEX(dealer_id,status,id),INDEX(status,id),CHECK(version>0)
);
CREATE TABLE order_lines (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,order_id BIGINT UNSIGNED NOT NULL,position INT NOT NULL,
 product_id BIGINT UNSIGNED NOT NULL,unit_id BIGINT UNSIGNED NOT NULL,quantity DECIMAL(19,6) NOT NULL,
 sku_snapshot VARCHAR(64) NULL,name_snapshot VARCHAR(200) NULL,unit_name_snapshot VARCHAR(50) NULL,
 factor_snapshot DECIMAL(19,6) NULL,unit_version BIGINT NULL,base_quantity DECIMAL(38,12) NULL,
 selling_price DECIMAL(19,4) NULL,gross DECIMAL(19,4) NULL,discount DECIMAL(19,4) NULL,net DECIMAL(19,4) NULL,
 policy_name_snapshot VARCHAR(150) NULL,
 UNIQUE(order_id,position),FOREIGN KEY(order_id) REFERENCES orders(id),
 FOREIGN KEY(product_id) REFERENCES products(id),FOREIGN KEY(unit_id) REFERENCES product_units(id),
 CHECK(quantity>0)
);
CREATE TABLE order_quote_tickets (
 token CHAR(36) PRIMARY KEY,order_id BIGINT UNSIGNED NOT NULL,actor_id BIGINT UNSIGNED NOT NULL,
 order_version BIGINT NOT NULL,digest CHAR(64) NOT NULL,requires_confirmation BOOLEAN NOT NULL DEFAULT FALSE,
 expires_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(order_id) REFERENCES orders(id),FOREIGN KEY(actor_id) REFERENCES users(id),
 INDEX(order_id,actor_id,expires_at)
);
