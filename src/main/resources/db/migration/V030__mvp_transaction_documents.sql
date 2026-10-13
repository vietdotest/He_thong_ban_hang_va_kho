-- Các chứng từ MVP nối tiếp V029; không tạo tồn, giữ chỗ hay công nợ lịch sử.
ALTER TABLE orders ADD COLUMN source ENUM('INTERNAL','PORTAL') NOT NULL DEFAULT 'INTERNAL',
 ADD COLUMN pending_reason ENUM('STAFF_CONFIRMATION','EXCEPTION') NULL,
 ADD COLUMN fulfillment_state ENUM('NONE','RESERVED','IN_PROGRESS','COMPLETE','CANCELLED') NOT NULL DEFAULT 'NONE';
UPDATE orders o JOIN users u ON u.id=o.created_by SET o.source='PORTAL' WHERE u.account_kind='DEALER';
ALTER TABLE order_approval_requests MODIFY status ENUM('PENDING','APPROVED','REJECTED','RETURNED') NOT NULL DEFAULT 'PENDING';
CREATE TABLE mvp_commands (
 actor_id BIGINT UNSIGNED NOT NULL,operation VARCHAR(80) NOT NULL,request_key CHAR(36) NOT NULL,
 input_hash CHAR(64) NOT NULL,object_id BIGINT UNSIGNED NULL,created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(actor_id,operation,request_key),FOREIGN KEY(actor_id) REFERENCES users(id)
);
CREATE TABLE mvp_sequences (
 kind VARCHAR(20) NOT NULL,document_year INT NOT NULL,next_value BIGINT NOT NULL DEFAULT 1,
 PRIMARY KEY(kind,document_year),CHECK(next_value>0)
);
CREATE TABLE mvp_receipts (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,warehouse_id BIGINT UNSIGNED NOT NULL,supplier_id BIGINT UNSIGNED NOT NULL,
 document_ref VARCHAR(100) NOT NULL,receipt_date DATE NOT NULL,status ENUM('DRAFT','CONFIRMED','REVERSED') NOT NULL DEFAULT 'DRAFT',
 version BIGINT NOT NULL DEFAULT 1,created_by BIGINT UNSIGNED NOT NULL,confirmed_by BIGINT UNSIGNED NULL,confirmed_at TIMESTAMP(6) NULL,
 FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),FOREIGN KEY(supplier_id) REFERENCES suppliers(id),
 FOREIGN KEY(created_by) REFERENCES users(id),FOREIGN KEY(confirmed_by) REFERENCES users(id),INDEX(warehouse_id,status,id),CHECK(version>0)
);
CREATE TABLE mvp_receipt_lines (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,receipt_id BIGINT UNSIGNED NOT NULL,position INT NOT NULL,
 product_id BIGINT UNSIGNED NOT NULL,unit_id BIGINT UNSIGNED NOT NULL,quantity DECIMAL(19,6) NOT NULL,
 factor DECIMAL(19,6) NOT NULL,unit_version BIGINT NOT NULL,base_quantity DECIMAL(38,12) NOT NULL,
 lot_code VARCHAR(100) NOT NULL,expires_on DATE NULL,
 UNIQUE(receipt_id,position),FOREIGN KEY(receipt_id) REFERENCES mvp_receipts(id),
 FOREIGN KEY(product_id) REFERENCES products(id),FOREIGN KEY(unit_id) REFERENCES product_units(id),
 CHECK(quantity>0),CHECK(factor>0),CHECK(base_quantity>0)
);
CREATE TABLE mvp_lots (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,warehouse_id BIGINT UNSIGNED NOT NULL,product_id BIGINT UNSIGNED NOT NULL,
 receipt_line_id BIGINT UNSIGNED NOT NULL UNIQUE,lot_code VARCHAR(100) NOT NULL,received_date DATE NOT NULL,expires_on DATE NULL,
 on_hand DECIMAL(38,12) NOT NULL,
 FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),FOREIGN KEY(product_id) REFERENCES products(id),
 FOREIGN KEY(receipt_line_id) REFERENCES mvp_receipt_lines(id),INDEX(warehouse_id,product_id,received_date,id),CHECK(on_hand>=0)
);
CREATE TABLE mvp_holds (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,order_id BIGINT UNSIGNED NOT NULL,order_line_id BIGINT UNSIGNED NOT NULL,lot_id BIGINT UNSIGNED NOT NULL,
 quantity DECIMAL(38,12) NOT NULL,consumed DECIMAL(38,12) NOT NULL DEFAULT 0,released DECIMAL(38,12) NOT NULL DEFAULT 0,
 expires_at TIMESTAMP(6) NOT NULL,protected_at TIMESTAMP(6) NULL,created_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(order_id) REFERENCES orders(id),FOREIGN KEY(order_line_id) REFERENCES order_lines(id),FOREIGN KEY(lot_id) REFERENCES mvp_lots(id),
 INDEX(order_id,id),INDEX(lot_id,expires_at,id),CHECK(quantity>0),CHECK(consumed>=0),CHECK(released>=0),CHECK(consumed+released<=quantity)
);
CREATE TABLE mvp_shipments (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,order_id BIGINT UNSIGNED NOT NULL,warehouse_id BIGINT UNSIGNED NOT NULL,
 status ENUM('DISPATCHED','DELIVERED') NOT NULL DEFAULT 'DISPATCHED',version BIGINT NOT NULL DEFAULT 1,
 shortage_reason VARCHAR(1000) NULL,created_by BIGINT UNSIGNED NOT NULL,dispatched_at TIMESTAMP(6) NOT NULL,
 delivered_by BIGINT UNSIGNED NULL,recipient VARCHAR(200) NULL,delivered_at TIMESTAMP(6) NULL,
 FOREIGN KEY(order_id) REFERENCES orders(id),FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),
 FOREIGN KEY(created_by) REFERENCES users(id),FOREIGN KEY(delivered_by) REFERENCES users(id),INDEX(warehouse_id,status,id),CHECK(version>0)
);
CREATE TABLE mvp_shipment_lines (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,shipment_id BIGINT UNSIGNED NOT NULL,order_line_id BIGINT UNSIGNED NOT NULL,
 hold_id BIGINT UNSIGNED NOT NULL,lot_id BIGINT UNSIGNED NOT NULL,quantity DECIMAL(38,12) NOT NULL,
 net_amount DECIMAL(19,4) NOT NULL,received_quantity DECIMAL(38,12) NULL,received_amount DECIMAL(19,4) NULL,
 returned_quantity DECIMAL(38,12) NOT NULL DEFAULT 0,returned_amount DECIMAL(19,4) NOT NULL DEFAULT 0,delivery_reason VARCHAR(1000) NULL,
 FOREIGN KEY(shipment_id) REFERENCES mvp_shipments(id),FOREIGN KEY(order_line_id) REFERENCES order_lines(id),
 FOREIGN KEY(hold_id) REFERENCES mvp_holds(id),FOREIGN KEY(lot_id) REFERENCES mvp_lots(id),INDEX(order_line_id,id),
 CHECK(quantity>0),CHECK(net_amount>=0),CHECK(received_quantity IS NULL OR (received_quantity>=0 AND received_quantity<=quantity)),
 CHECK(returned_quantity>=0),CHECK(received_quantity IS NULL OR received_quantity+returned_quantity<=quantity),CHECK(returned_amount>=0)
);
CREATE TABLE mvp_stock_events (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,lot_id BIGINT UNSIGNED NOT NULL,event_type VARCHAR(40) NOT NULL,
 source_type VARCHAR(40) NOT NULL,source_id BIGINT UNSIGNED NOT NULL,quantity_delta DECIMAL(38,12) NOT NULL,on_hand_after DECIMAL(38,12) NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL,occurred_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(lot_id) REFERENCES mvp_lots(id),FOREIGN KEY(actor_id) REFERENCES users(id),INDEX(lot_id,occurred_at,id),CHECK(on_hand_after>=0)
);
CREATE TABLE mvp_invoices (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,shipment_id BIGINT UNSIGNED NOT NULL UNIQUE,dealer_id BIGINT UNSIGNED NOT NULL,
 number VARCHAR(40) NOT NULL UNIQUE,invoice_date DATE NOT NULL,due_date DATE NOT NULL,net_amount DECIMAL(19,4) NOT NULL,
 issued_by BIGINT UNSIGNED NOT NULL,issued_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(shipment_id) REFERENCES mvp_shipments(id),FOREIGN KEY(dealer_id) REFERENCES dealers(id),FOREIGN KEY(issued_by) REFERENCES users(id),
 INDEX(dealer_id,invoice_date,id),INDEX(due_date,id),CHECK(net_amount>=0)
);
CREATE TABLE mvp_invoice_lines (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,invoice_id BIGINT UNSIGNED NOT NULL,shipment_line_id BIGINT UNSIGNED NOT NULL UNIQUE,
 order_line_id BIGINT UNSIGNED NOT NULL,lot_id BIGINT UNSIGNED NOT NULL,product_id BIGINT UNSIGNED NOT NULL,
 sku_snapshot VARCHAR(64) NOT NULL,name_snapshot VARCHAR(200) NOT NULL,unit_snapshot VARCHAR(50) NOT NULL,
 quantity DECIMAL(38,12) NOT NULL,net_amount DECIMAL(19,4) NOT NULL,
 FOREIGN KEY(invoice_id) REFERENCES mvp_invoices(id),FOREIGN KEY(shipment_line_id) REFERENCES mvp_shipment_lines(id),
 FOREIGN KEY(order_line_id) REFERENCES order_lines(id),FOREIGN KEY(lot_id) REFERENCES mvp_lots(id),FOREIGN KEY(product_id) REFERENCES products(id),
 CHECK(quantity>0),CHECK(net_amount>=0)
);
CREATE TABLE mvp_payments (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,dealer_id BIGINT UNSIGNED NOT NULL,number VARCHAR(40) NOT NULL UNIQUE,
 amount DECIMAL(19,4) NOT NULL,paid_date DATE NOT NULL,method VARCHAR(50) NOT NULL,note VARCHAR(1000) NULL,
 recorded_by BIGINT UNSIGNED NOT NULL,recorded_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(dealer_id) REFERENCES dealers(id),FOREIGN KEY(recorded_by) REFERENCES users(id),INDEX(dealer_id,paid_date,id),CHECK(amount>0)
);
CREATE TABLE mvp_payment_reversals (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,payment_id BIGINT UNSIGNED NOT NULL UNIQUE,reason VARCHAR(1000) NOT NULL,
 recorded_by BIGINT UNSIGNED NOT NULL,recorded_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(payment_id) REFERENCES mvp_payments(id),FOREIGN KEY(recorded_by) REFERENCES users(id)
);
CREATE TABLE mvp_payment_allocations (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,payment_id BIGINT UNSIGNED NOT NULL,invoice_id BIGINT UNSIGNED NOT NULL,
 signed_amount DECIMAL(19,4) NOT NULL,trigger_type VARCHAR(40) NOT NULL,trigger_id BIGINT UNSIGNED NOT NULL,occurred_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(payment_id) REFERENCES mvp_payments(id),FOREIGN KEY(invoice_id) REFERENCES mvp_invoices(id),INDEX(invoice_id,id),INDEX(payment_id,id)
);
CREATE TABLE mvp_returns (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,invoice_id BIGINT UNSIGNED NOT NULL,warehouse_id BIGINT UNSIGNED NOT NULL,
 status ENUM('DRAFT','RECEIVED','CANCELLED') NOT NULL DEFAULT 'DRAFT',version BIGINT NOT NULL DEFAULT 1,reason VARCHAR(1000) NOT NULL,
 created_by BIGINT UNSIGNED NOT NULL,created_at TIMESTAMP(6) NOT NULL,received_by BIGINT UNSIGNED NULL,received_at TIMESTAMP(6) NULL,
 FOREIGN KEY(invoice_id) REFERENCES mvp_invoices(id),FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),
 FOREIGN KEY(created_by) REFERENCES users(id),FOREIGN KEY(received_by) REFERENCES users(id),INDEX(invoice_id,status,id),CHECK(version>0)
);
CREATE TABLE mvp_return_lines (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,return_id BIGINT UNSIGNED NOT NULL,invoice_line_id BIGINT UNSIGNED NOT NULL,
 condition_code ENUM('SELLABLE','DAMAGED') NOT NULL,quantity DECIMAL(38,12) NOT NULL,
 UNIQUE(return_id,invoice_line_id,condition_code),FOREIGN KEY(return_id) REFERENCES mvp_returns(id),
 FOREIGN KEY(invoice_line_id) REFERENCES mvp_invoice_lines(id),CHECK(quantity>0)
);
CREATE TABLE mvp_credits (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,return_id BIGINT UNSIGNED NOT NULL UNIQUE,invoice_id BIGINT UNSIGNED NOT NULL,
 dealer_id BIGINT UNSIGNED NOT NULL,number VARCHAR(40) NOT NULL UNIQUE,amount DECIMAL(19,4) NOT NULL,
 issued_by BIGINT UNSIGNED NOT NULL,issued_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(return_id) REFERENCES mvp_returns(id),FOREIGN KEY(invoice_id) REFERENCES mvp_invoices(id),
 FOREIGN KEY(dealer_id) REFERENCES dealers(id),FOREIGN KEY(issued_by) REFERENCES users(id),CHECK(amount>=0)
);
CREATE TABLE mvp_credit_lines (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,credit_id BIGINT UNSIGNED NOT NULL,return_line_id BIGINT UNSIGNED NOT NULL UNIQUE,
 invoice_line_id BIGINT UNSIGNED NOT NULL,quantity DECIMAL(38,12) NOT NULL,amount DECIMAL(19,4) NOT NULL,
 FOREIGN KEY(credit_id) REFERENCES mvp_credits(id),FOREIGN KEY(return_line_id) REFERENCES mvp_return_lines(id),
 FOREIGN KEY(invoice_line_id) REFERENCES mvp_invoice_lines(id),CHECK(quantity>0),CHECK(amount>=0)
);
CREATE TABLE mvp_recovered_goods (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,shipment_line_id BIGINT UNSIGNED NOT NULL,quantity DECIMAL(38,12) NOT NULL,
 condition_code ENUM('SELLABLE','DAMAGED') NOT NULL,reason VARCHAR(1000) NOT NULL,recorded_by BIGINT UNSIGNED NOT NULL,recorded_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(shipment_line_id) REFERENCES mvp_shipment_lines(id),FOREIGN KEY(recorded_by) REFERENCES users(id),CHECK(quantity>0)
);
INSERT INTO permissions(code,name) VALUES
 ('ORDER_CONFIRM_PORTAL','Xác nhận đơn cổng của đại lý phụ trách'),('ORDER_CANCEL','Hủy đơn trước xuất'),
 ('STOCK_READ','Xem tồn và thẻ kho trong phạm vi'),('STOCK_RECEIPT_WRITE','Lập và xác nhận nhập kho được giao'),
 ('FULFILLMENT_WRITE','Soạn, xuất và giao từ kho được giao'),('STOCK_RETURN_RECEIVE','Xác nhận hàng quay về và hàng trả'),
 ('INVOICE_READ','Xem hóa đơn trong phạm vi'),('INVOICE_ISSUE','Phát hành hóa đơn nội bộ'),
 ('PAYMENT_WRITE','Ghi và đảo phiếu thu'),('RETURN_WRITE','Lập phiếu trả của đại lý trong phạm vi'),
 ('CREDIT_ISSUE','Phát hành giảm trừ hàng trả'),('SALES_DEBT_REPORT_READ','Xem báo cáo doanh số và công nợ trong phạm vi');
INSERT INTO role_permissions(role_id,permission_id)
 SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE
 (r.code IN ('SALES','SALES_MANAGER') AND p.code IN ('ORDER_CONFIRM_PORTAL','ORDER_CANCEL','RETURN_WRITE')) OR
 (r.code IN ('WAREHOUSE','WAREHOUSE_MANAGER','SALES','SALES_MANAGER','ACCOUNTANT','DIRECTOR') AND p.code='STOCK_READ') OR
 (r.code IN ('WAREHOUSE','WAREHOUSE_MANAGER') AND p.code IN ('STOCK_RECEIPT_WRITE','FULFILLMENT_WRITE')) OR
 (r.code='WAREHOUSE_MANAGER' AND p.code='STOCK_RETURN_RECEIVE') OR
 (r.code IN ('SALES','SALES_MANAGER','ACCOUNTANT','DIRECTOR') AND p.code IN ('INVOICE_READ','SALES_DEBT_REPORT_READ')) OR
 (r.code='ACCOUNTANT' AND p.code IN ('INVOICE_ISSUE','PAYMENT_WRITE','CREDIT_ISSUE'));
