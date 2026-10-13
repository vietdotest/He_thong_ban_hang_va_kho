CREATE TABLE mvp_order_history (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,order_id BIGINT UNSIGNED NOT NULL,event_type VARCHAR(50) NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL,reason VARCHAR(1000) NOT NULL,snapshot JSON NOT NULL,occurred_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(order_id) REFERENCES orders(id),FOREIGN KEY(actor_id) REFERENCES users(id),INDEX(order_id,id)
);
-- Lưu trạng thái yêu cầu cũ trước khi cho phép gửi lại một đơn được trả về sửa.
INSERT INTO mvp_order_history(order_id,event_type,actor_id,reason,snapshot,occurred_at)
 SELECT order_id,CONCAT('LEGACY_',status),COALESCE(decided_by,requested_by),COALESCE(decision_reason,request_reason),
 JSON_OBJECT('requested',request_snapshot,'decided',decision_snapshot,'status',status),COALESCE(decided_at,requested_at)
 FROM order_approval_requests;
