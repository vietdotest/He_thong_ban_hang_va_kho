ALTER TABLE mvp_payments ADD COLUMN version BIGINT NOT NULL DEFAULT 1,ADD CONSTRAINT ck_mvp_payment_version CHECK(version>0);
ALTER TABLE mvp_payment_reversals ADD COLUMN number VARCHAR(40) NULL;
UPDATE mvp_payment_reversals SET number=CONCAT('DT-',YEAR(recorded_at),'-',LPAD(id,6,'0'));
INSERT INTO mvp_sequences(kind,document_year,next_value) SELECT 'DT',YEAR(recorded_at),MAX(id)+1 FROM mvp_payment_reversals GROUP BY YEAR(recorded_at)
 ON DUPLICATE KEY UPDATE next_value=GREATEST(next_value,VALUES(next_value));
ALTER TABLE mvp_payment_reversals MODIFY number VARCHAR(40) NOT NULL,ADD UNIQUE uq_mvp_reversal_number(number);
UPDATE mvp_payments p JOIN mvp_payment_reversals r ON r.payment_id=p.id SET p.version=2;
