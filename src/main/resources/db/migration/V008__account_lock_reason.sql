ALTER TABLE users ADD COLUMN lock_reason VARCHAR(1000),ADD COLUMN status_before_lock VARCHAR(32),ADD COLUMN admin_locked_at DATETIME(6),ADD COLUMN locked_by BIGINT UNSIGNED;
-- Điểm tiếp nhận liên kết đại lý từ module quản lý khách hàng ở sprint sau.
CREATE TABLE dealer_staff_references(dealer_reference VARCHAR(100) NOT NULL,user_id BIGINT UNSIGNED NOT NULL,
 PRIMARY KEY(dealer_reference,user_id),FOREIGN KEY(user_id) REFERENCES users(id));
CREATE TABLE handover_warnings(id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNSIGNED NOT NULL,
 dealer_reference VARCHAR(100) NOT NULL,reason VARCHAR(1000) NOT NULL,created_at DATETIME(6) NOT NULL,
 resolved_at DATETIME(6),FOREIGN KEY(user_id) REFERENCES users(id));
