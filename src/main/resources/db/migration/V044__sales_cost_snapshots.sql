-- Chỉ bổ sung cột nullable: chứng từ cũ giữ trạng thái chưa có giá vốn lịch sử.
-- WAR trước nâng cấp tiếp tục ghi được và không làm mất dữ liệu mới khi rollback.
ALTER TABLE mvp_shipment_lines
 ADD COLUMN unit_cost_snapshot DECIMAL(19,4) NULL,
 ADD COLUMN cost_amount DECIMAL(38,4) NULL,
 ADD CONSTRAINT ck_shipment_cost_nonnegative CHECK (unit_cost_snapshot IS NULL OR unit_cost_snapshot>=0),
 ADD CONSTRAINT ck_shipment_cost_amount CHECK (cost_amount IS NULL OR cost_amount>=0);
ALTER TABLE mvp_invoice_lines
 ADD COLUMN cost_amount DECIMAL(38,4) NULL,
 ADD CONSTRAINT ck_invoice_cost_amount CHECK (cost_amount IS NULL OR cost_amount>=0);
ALTER TABLE mvp_credit_lines
 ADD COLUMN cost_amount DECIMAL(38,4) NULL,
 ADD CONSTRAINT ck_credit_cost_amount CHECK (cost_amount IS NULL OR cost_amount>=0);
