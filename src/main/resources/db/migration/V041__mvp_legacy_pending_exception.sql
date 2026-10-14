-- Đơn chờ duyệt trước MVP là ngoại lệ; không giữ tồn khi nâng cấp.
UPDATE orders SET pending_reason='EXCEPTION'
 WHERE status='PENDING_APPROVAL' AND pending_reason IS NULL;
