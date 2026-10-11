-- Không tự suy đoán địa chỉ cho dữ liệu cũ; quản trị viên bổ sung khi sửa.
ALTER TABLE warehouses ADD COLUMN address VARCHAR(500) NULL, ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE territories ADD COLUMN address VARCHAR(500) NULL, ADD COLUMN version BIGINT NOT NULL DEFAULT 1;
