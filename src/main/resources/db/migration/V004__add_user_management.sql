ALTER TABLE users
    ADD COLUMN phone VARCHAR(20) NULL AFTER full_name,
    ADD COLUMN phone_normalized VARCHAR(20) NULL AFTER phone,
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE AFTER password_hash,
    ADD CONSTRAINT uk_users_phone_normalized UNIQUE (phone_normalized),
    ADD INDEX idx_users_full_name (full_name);

INSERT INTO roles (code, name)
SELECT 'SALES', 'Nhân viên bán hàng'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'SALES');

INSERT INTO roles (code, name)
SELECT 'WAREHOUSE', 'Nhân viên kho'
WHERE NOT EXISTS (SELECT 1 FROM roles WHERE code = 'WAREHOUSE');
