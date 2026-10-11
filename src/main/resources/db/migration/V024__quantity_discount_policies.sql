INSERT INTO permissions(code,name) VALUES('DISCOUNT_READ','Xem chính sách chiết khấu'),('DISCOUNT_MANAGE','Quản lý chính sách chiết khấu');
INSERT INTO role_permissions(role_id,permission_id) SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE (r.code IN ('SALES_MANAGER','SALES','ACCOUNTANT','DIRECTOR') AND p.code='DISCOUNT_READ') OR (r.code='SALES_MANAGER' AND p.code='DISCOUNT_MANAGE');
INSERT INTO catalog_locks(name) VALUES('DISCOUNT_POLICIES');
CREATE TABLE discount_policies (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(150) NOT NULL,
 product_id BIGINT UNSIGNED NULL,category_id BIGINT UNSIGNED NULL,
 customer_group_id BIGINT UNSIGNED NULL,
 mode ENUM('PERCENT','FIXED') NOT NULL,
 valid_from DATE NOT NULL,valid_to DATE NOT NULL,
 status ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE',
 revision BIGINT NOT NULL DEFAULT 1,
 FOREIGN KEY(product_id) REFERENCES products(id),FOREIGN KEY(category_id) REFERENCES categories(id),FOREIGN KEY(customer_group_id) REFERENCES customer_groups(id),
 CHECK((product_id IS NULL)<>(category_id IS NULL)),CHECK(valid_to>=valid_from),INDEX(status,valid_from,valid_to,id)
);
CREATE TABLE discount_tiers (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 policy_id BIGINT UNSIGNED NOT NULL,
 policy_revision BIGINT NOT NULL,
 minimum_quantity DECIMAL(19,6) NOT NULL,
 discount_value DECIMAL(19,6) NOT NULL,
 FOREIGN KEY(policy_id) REFERENCES discount_policies(id),
 UNIQUE(policy_id,policy_revision,minimum_quantity),
 CHECK(minimum_quantity>=0 AND discount_value>=0)
);
CREATE TABLE discount_order_references (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 order_reference VARCHAR(100) NOT NULL,
 product_id BIGINT UNSIGNED NOT NULL,
 policy_id BIGINT UNSIGNED NOT NULL,policy_revision BIGINT NOT NULL,tier_id BIGINT UNSIGNED NOT NULL,
 policy_name VARCHAR(150) NOT NULL,mode VARCHAR(10) NOT NULL,discount_value DECIMAL(19,6) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(product_id) REFERENCES products(id),FOREIGN KEY(policy_id) REFERENCES discount_policies(id),FOREIGN KEY(tier_id) REFERENCES discount_tiers(id),
 UNIQUE(order_reference,product_id)
);
