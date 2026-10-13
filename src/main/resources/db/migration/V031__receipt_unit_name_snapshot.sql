ALTER TABLE mvp_receipt_lines ADD COLUMN unit_name_snapshot VARCHAR(50) NULL;
UPDATE mvp_receipt_lines l JOIN product_units u ON u.id=l.unit_id SET l.unit_name_snapshot=u.name;
ALTER TABLE mvp_receipt_lines MODIFY unit_name_snapshot VARCHAR(50) NOT NULL;
