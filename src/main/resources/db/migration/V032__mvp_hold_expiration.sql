-- Hết hạn giữ chỗ không hủy chứng từ đã gửi hoặc hàng đã xuất.
ALTER TABLE orders MODIFY fulfillment_state ENUM('NONE','RESERVED','IN_PROGRESS','COMPLETE','CANCELLED','EXPIRED') NOT NULL DEFAULT 'NONE';
