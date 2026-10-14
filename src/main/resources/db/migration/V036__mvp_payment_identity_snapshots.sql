ALTER TABLE mvp_payments ADD COLUMN dealer_code_snapshot VARCHAR(64) NULL,
 ADD COLUMN dealer_name_snapshot VARCHAR(200) NULL,ADD COLUMN address_snapshot VARCHAR(500) NULL;
UPDATE mvp_payments p JOIN dealers d ON d.id=p.dealer_id LEFT JOIN dealer_addresses a ON a.dealer_id=d.id AND a.is_default=1
 SET p.dealer_code_snapshot=d.code,p.dealer_name_snapshot=d.name,p.address_snapshot=COALESCE(a.address,'');
ALTER TABLE mvp_payments MODIFY dealer_code_snapshot VARCHAR(64) NOT NULL,
 MODIFY dealer_name_snapshot VARCHAR(200) NOT NULL,MODIFY address_snapshot VARCHAR(500) NOT NULL;
