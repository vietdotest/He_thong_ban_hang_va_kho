CREATE TABLE price_history (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 product_id BIGINT UNSIGNED NOT NULL,sku_snapshot VARCHAR(64) NOT NULL,product_name_snapshot VARCHAR(200) NOT NULL,
 version_id BIGINT UNSIGNED NOT NULL,price_item_id BIGINT UNSIGNED NULL,
 group_id BIGINT UNSIGNED NOT NULL,group_name_snapshot VARCHAR(150) NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL,actor_name_snapshot VARCHAR(150) NOT NULL,
 event_type ENUM('ITEM_CREATED','ITEM_UPDATED','ITEM_DELETED','VERSION_CHANGED','ITEM_INHERITED') NOT NULL,
 old_selling DECIMAL(19,4) NULL,new_selling DECIMAL(19,4) NULL,
 old_floor DECIMAL(19,4) NULL,new_floor DECIMAL(19,4) NULL,
 old_from DATE NULL,old_to DATE NULL,new_from DATE NULL,new_to DATE NULL,
 changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(product_id) REFERENCES products(id),FOREIGN KEY(version_id) REFERENCES price_versions(id),FOREIGN KEY(group_id) REFERENCES customer_groups(id),FOREIGN KEY(actor_id) REFERENCES users(id),
 INDEX(product_id,changed_at,id),INDEX(group_id,changed_at,id)
);
