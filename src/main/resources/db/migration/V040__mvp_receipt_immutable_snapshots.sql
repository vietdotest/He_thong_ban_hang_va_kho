ALTER TABLE mvp_receipts ADD COLUMN number VARCHAR(40) NULL,ADD COLUMN warehouse_name_snapshot VARCHAR(200) NULL,
 ADD COLUMN warehouse_address_snapshot VARCHAR(500) NULL,ADD COLUMN supplier_name_snapshot VARCHAR(200) NULL;
UPDATE mvp_receipts r JOIN warehouses w ON w.id=r.warehouse_id JOIN suppliers s ON s.id=r.supplier_id
 SET r.number=CONCAT('PN-',YEAR(r.confirmed_at),'-',LPAD(r.id,6,'0')),r.warehouse_name_snapshot=w.name,
 r.warehouse_address_snapshot=COALESCE(w.address,''),r.supplier_name_snapshot=s.name WHERE r.status='CONFIRMED';
INSERT INTO mvp_sequences(kind,document_year,next_value) SELECT 'PN',YEAR(confirmed_at),MAX(id)+1 FROM mvp_receipts WHERE status='CONFIRMED' GROUP BY YEAR(confirmed_at)
 ON DUPLICATE KEY UPDATE next_value=GREATEST(next_value,VALUES(next_value));
ALTER TABLE mvp_receipts ADD UNIQUE uq_mvp_receipt_number(number);
ALTER TABLE mvp_receipt_lines ADD COLUMN sku_snapshot VARCHAR(64) NULL,ADD COLUMN name_snapshot VARCHAR(200) NULL;
UPDATE mvp_receipt_lines rl JOIN mvp_receipts r ON r.id=rl.receipt_id JOIN products p ON p.id=rl.product_id
 SET rl.sku_snapshot=p.sku,rl.name_snapshot=p.name WHERE r.status='CONFIRMED';
