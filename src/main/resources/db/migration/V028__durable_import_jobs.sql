CREATE TABLE import_jobs (
 id CHAR(36) PRIMARY KEY,kind ENUM('USER','PRODUCT') NOT NULL,actor_id BIGINT UNSIGNED NOT NULL,
 filename VARCHAR(180) NOT NULL,has_cost BOOLEAN NOT NULL DEFAULT FALSE,
 status ENUM('PREVIEW','QUEUED','RUNNING','COMPLETED','STOPPED','EXPIRED') NOT NULL DEFAULT 'PREVIEW',
 version BIGINT NOT NULL DEFAULT 1,confirm_key CHAR(36) NOT NULL,
 total_rows INT NOT NULL,valid_rows INT NOT NULL,reason VARCHAR(500) NOT NULL DEFAULT '',
 preview_expires_at TIMESTAMP(6) NOT NULL,report_expires_at TIMESTAMP(6) NULL,
 lease_owner CHAR(36) NULL,lease_until TIMESTAMP(6) NULL,heartbeat_at TIMESTAMP(6) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),finished_at TIMESTAMP(6) NULL,
 FOREIGN KEY(actor_id) REFERENCES users(id),INDEX(status,lease_until,created_at,id),INDEX(actor_id,created_at,id),
 CHECK(total_rows BETWEEN 1 AND 10000),CHECK(valid_rows BETWEEN 0 AND total_rows)
);
CREATE TABLE import_job_rows (
 job_id CHAR(36) NOT NULL,source_row INT NOT NULL,
 c0 TEXT NOT NULL,c1 TEXT NOT NULL,c2 TEXT NOT NULL,c3 TEXT NOT NULL,c4 TEXT NOT NULL,c5 TEXT NOT NULL,c6 TEXT NOT NULL,
 operation VARCHAR(20) NOT NULL,validation_error VARCHAR(1000) NOT NULL DEFAULT '',
 expected_id BIGINT UNSIGNED NOT NULL DEFAULT 0,expected_version BIGINT NOT NULL DEFAULT 0,
 state ENUM('INVALID','READY','CLAIMED','EMAIL_ATTEMPT','SUCCESS','FAILED','REVIEW','SKIPPED') NOT NULL,
 row_token CHAR(36) NULL,mail_attempt_id CHAR(36) NULL,mail_attempt_at TIMESTAMP(6) NULL,
 message VARCHAR(1000) NOT NULL DEFAULT '',entity_id BIGINT UNSIGNED NULL,committed_at TIMESTAMP(6) NULL,
 PRIMARY KEY(job_id,source_row),FOREIGN KEY(job_id) REFERENCES import_jobs(id) ON DELETE CASCADE,
 INDEX(job_id,state,source_row),CHECK(source_row BETWEEN 2 AND 10001)
);

-- Scalar job/row references deliberately survive report expiry. Uncertain SMTP
-- attempts must still block another upload; cleanup is not permission to resend.
CREATE TABLE import_mail_attempts (
 id CHAR(36) PRIMARY KEY,job_id CHAR(36) NOT NULL,source_row INT NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL,username_normalized VARCHAR(64) NOT NULL,
 state ENUM('STARTED','SUCCEEDED','FAILED','REVIEW') NOT NULL DEFAULT 'STARTED',
 unresolved_username VARCHAR(64) GENERATED ALWAYS AS
  (CASE WHEN state IN ('STARTED','REVIEW') THEN username_normalized ELSE NULL END) STORED,
 started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),finished_at TIMESTAMP(6) NULL,
 FOREIGN KEY(actor_id) REFERENCES users(id),UNIQUE KEY one_unresolved_email(unresolved_username),
 INDEX(job_id,source_row)
);
