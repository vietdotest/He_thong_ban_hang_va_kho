INSERT INTO roles(code,name) VALUES
('SALES_MANAGER','Quản lý kinh doanh'),('WAREHOUSE_MANAGER','Trưởng kho'),('ACCOUNTANT','Kế toán'),('DIRECTOR','Ban giám đốc');
UPDATE roles SET name='Quản trị hệ thống' WHERE code='ADMIN';
UPDATE roles SET name='Nhân viên kinh doanh' WHERE code='SALES';
INSERT INTO permissions(code,name) VALUES
('PROFILE','Hồ sơ cá nhân'),('USER_MANAGE','Quản trị người dùng'),('ROLE_MANAGE','Quản trị phân quyền'),
('AUDIT_READ','Xem nhật ký thao tác'),('CATALOG_READ','Xem danh mục'),('PRODUCT_MANAGE','Quản lý sản phẩm và nhóm hàng'),
('WAREHOUSE_MANAGE','Quản lý đơn vị và nhà cung cấp'),('PRICE_READ','Xem giá bán'),('PRICE_MANAGE','Quản lý bảng giá'),
('COST_READ','Xem giá vốn'),('COST_WRITE','Sửa giá vốn');
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE p.code IN ('PROFILE','CATALOG_READ','PRICE_READ')
 OR (r.code='ADMIN' AND p.code IN ('USER_MANAGE','ROLE_MANAGE','AUDIT_READ'))
 OR (r.code='SALES_MANAGER' AND p.code IN ('PRODUCT_MANAGE','PRICE_MANAGE','COST_READ','COST_WRITE'))
 OR (r.code IN ('WAREHOUSE','WAREHOUSE_MANAGER') AND p.code='WAREHOUSE_MANAGE');
