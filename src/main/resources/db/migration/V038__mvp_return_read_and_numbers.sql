INSERT INTO permissions(code,name) VALUES('RETURN_READ','Xem phiếu trả trong phạm vi đại lý hoặc kho được giao');
INSERT INTO role_permissions(role_id,permission_id) SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
 WHERE r.code IN ('SALES','SALES_MANAGER','ACCOUNTANT','DIRECTOR','WAREHOUSE','WAREHOUSE_MANAGER') AND p.code='RETURN_READ';
ALTER TABLE mvp_returns ADD COLUMN number VARCHAR(40) NULL;
UPDATE mvp_returns SET number=CONCAT('TH-',YEAR(created_at),'-',LPAD(id,6,'0'));
INSERT INTO mvp_sequences(kind,document_year,next_value) SELECT 'TH',YEAR(created_at),MAX(id)+1 FROM mvp_returns GROUP BY YEAR(created_at)
 ON DUPLICATE KEY UPDATE next_value=GREATEST(next_value,VALUES(next_value));
ALTER TABLE mvp_returns MODIFY number VARCHAR(40) NOT NULL,ADD UNIQUE uq_mvp_return_number(number);
