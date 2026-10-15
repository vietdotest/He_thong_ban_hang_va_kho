ALTER TABLE mvp_credits ADD COLUMN credit_date DATE NULL;
-- Runner dùng UTC; ngày nghiệp vụ Việt Nam được lưu riêng cho báo cáo.
UPDATE mvp_credits SET credit_date=DATE(CONVERT_TZ(issued_at,'+00:00','+07:00'));
ALTER TABLE mvp_credits MODIFY credit_date DATE NOT NULL,ADD INDEX ix_mvp_credit_date(credit_date,dealer_id,id);
