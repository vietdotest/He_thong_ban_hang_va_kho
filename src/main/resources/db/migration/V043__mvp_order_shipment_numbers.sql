ALTER TABLE orders ADD COLUMN number VARCHAR(40) NULL,ADD UNIQUE uq_order_number(number);
UPDATE orders SET number=CONCAT('DH-',YEAR(COALESCE(submitted_at,created_at)+INTERVAL 7 HOUR),'-',LPAD(id,6,'0')) WHERE status<>'DRAFT';
INSERT INTO mvp_sequences(kind,document_year,next_value)
 SELECT 'DH',YEAR(COALESCE(submitted_at,created_at)+INTERVAL 7 HOUR),MAX(id)+1 FROM orders WHERE number IS NOT NULL GROUP BY YEAR(COALESCE(submitted_at,created_at)+INTERVAL 7 HOUR)
 ON DUPLICATE KEY UPDATE next_value=GREATEST(next_value,VALUES(next_value));
ALTER TABLE mvp_shipments ADD COLUMN number VARCHAR(40) NULL,ADD UNIQUE uq_mvp_shipment_number(number);
UPDATE mvp_shipments SET number=CONCAT('PX-',YEAR(dispatched_at+INTERVAL 7 HOUR),'-',LPAD(id,6,'0'));
INSERT INTO mvp_sequences(kind,document_year,next_value)
 SELECT 'PX',YEAR(dispatched_at+INTERVAL 7 HOUR),MAX(id)+1 FROM mvp_shipments GROUP BY YEAR(dispatched_at+INTERVAL 7 HOUR)
 ON DUPLICATE KEY UPDATE next_value=GREATEST(next_value,VALUES(next_value));
