INSERT INTO permissions(code,name) VALUES('FULFILLMENT_READ','Xem đơn soạn, xuất và giao tại kho được giao');
INSERT INTO role_permissions(role_id,permission_id) SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
 WHERE r.code IN ('WAREHOUSE','WAREHOUSE_MANAGER') AND p.code='FULFILLMENT_READ';
