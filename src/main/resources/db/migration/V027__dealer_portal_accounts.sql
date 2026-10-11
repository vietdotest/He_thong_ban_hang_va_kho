ALTER TABLE users ADD COLUMN account_kind ENUM('INTERNAL','DEALER') NOT NULL DEFAULT 'INTERNAL';
INSERT INTO roles(code,name) VALUES('DEALER','Tài khoản đại lý');
INSERT INTO permissions(code,name) VALUES('PORTAL_ACCOUNT_MANAGE','Cấp và quản lý tài khoản cổng đại lý'),('PORTAL_ORDER_READ','Xem đơn thuộc đại lý liên kết'),('PORTAL_ORDER_WRITE','Tạo và gửi nháp của tài khoản đại lý');
INSERT INTO role_permissions(role_id,permission_id) SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE (r.code='SALES_MANAGER' AND p.code='PORTAL_ACCOUNT_MANAGE') OR (r.code='DEALER' AND p.code IN ('PROFILE','PORTAL_ORDER_READ','PORTAL_ORDER_WRITE'));
CREATE TABLE dealer_accounts (
 user_id BIGINT UNSIGNED PRIMARY KEY,dealer_id BIGINT UNSIGNED NOT NULL,
 issued_by BIGINT UNSIGNED NOT NULL,version BIGINT NOT NULL DEFAULT 1,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES users(id),FOREIGN KEY(dealer_id) REFERENCES dealers(id),FOREIGN KEY(issued_by) REFERENCES users(id),INDEX(dealer_id,user_id)
);
CREATE TABLE dealer_account_link_history (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,user_id BIGINT UNSIGNED NOT NULL,
 old_dealer_id BIGINT UNSIGNED NOT NULL,new_dealer_id BIGINT UNSIGNED NOT NULL,
 actor_id BIGINT UNSIGNED NOT NULL,reason VARCHAR(1000) NOT NULL,account_version BIGINT NOT NULL,
 changed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES users(id),FOREIGN KEY(old_dealer_id) REFERENCES dealers(id),FOREIGN KEY(new_dealer_id) REFERENCES dealers(id),FOREIGN KEY(actor_id) REFERENCES users(id),INDEX(user_id,id)
);
-- Persisted before SMTP. STARTED after a crash requires human review, never auto-resend.
CREATE TABLE portal_mail_attempts (
 id CHAR(36) PRIMARY KEY,actor_id BIGINT UNSIGNED NOT NULL,dealer_id BIGINT UNSIGNED NOT NULL,
 username_normalized VARCHAR(64) NOT NULL,operation ENUM('CREATE','RESEND') NOT NULL,
 state ENUM('STARTED','SUCCEEDED','FAILED','REVIEW') NOT NULL DEFAULT 'STARTED',user_id BIGINT UNSIGNED NULL,
 started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),finished_at TIMESTAMP(6) NULL,
 unresolved_username VARCHAR(64) GENERATED ALWAYS AS (IF(state IN ('STARTED','REVIEW'),username_normalized,NULL)) STORED,
 -- dealer_id is a journal snapshot: no FK lock against the business transaction's dealer lock.
 UNIQUE(unresolved_username),FOREIGN KEY(actor_id) REFERENCES users(id)
);

DELIMITER $$
CREATE TRIGGER dealer_role_insert BEFORE INSERT ON user_roles FOR EACH ROW
BEGIN
 DECLARE kind VARCHAR(20); DECLARE role_code VARCHAR(50);
 SELECT account_kind INTO kind FROM users WHERE id=NEW.user_id;
 SELECT code INTO role_code FROM roles WHERE id=NEW.role_id;
 IF (kind='DEALER' AND role_code<>'DEALER') OR (kind='INTERNAL' AND role_code='DEALER') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Portal and internal roles must be separated'; END IF;
END$$
CREATE TRIGGER dealer_role_update BEFORE UPDATE ON user_roles FOR EACH ROW
BEGIN
 DECLARE kind VARCHAR(20); DECLARE role_code VARCHAR(50);
 SELECT account_kind INTO kind FROM users WHERE id=NEW.user_id;
 SELECT code INTO role_code FROM roles WHERE id=NEW.role_id;
 IF (kind='DEALER' AND role_code<>'DEALER') OR (kind='INTERNAL' AND role_code='DEALER') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Portal and internal roles must be separated'; END IF;
END$$
CREATE TRIGGER dealer_kind_update BEFORE UPDATE ON users FOR EACH ROW
BEGIN
 IF NEW.account_kind<>OLD.account_kind AND (
 EXISTS(SELECT 1 FROM dealer_accounts WHERE user_id=OLD.id) OR
 EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=OLD.id AND ((NEW.account_kind='DEALER' AND r.code<>'DEALER') OR (NEW.account_kind='INTERNAL' AND r.code='DEALER')))
 ) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Existing account kind cannot cross role boundaries'; END IF;
END$$
CREATE TRIGGER dealer_permission_insert BEFORE INSERT ON role_permissions FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM roles r JOIN permissions p ON p.id=NEW.permission_id WHERE r.id=NEW.role_id AND r.code='DEALER' AND p.code NOT IN ('PROFILE','PORTAL_ORDER_READ','PORTAL_ORDER_WRITE')) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Portal role cannot receive internal permissions'; END IF;
END$$
CREATE TRIGGER dealer_permission_update BEFORE UPDATE ON role_permissions FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM roles r JOIN permissions p ON p.id=NEW.permission_id WHERE r.id=NEW.role_id AND r.code='DEALER' AND p.code NOT IN ('PROFILE','PORTAL_ORDER_READ','PORTAL_ORDER_WRITE')) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Portal role cannot receive internal permissions'; END IF;
END$$
CREATE TRIGGER dealer_link_insert BEFORE INSERT ON dealer_accounts FOR EACH ROW
BEGIN
 IF NOT EXISTS(SELECT 1 FROM users WHERE id=NEW.user_id AND account_kind='DEALER') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Only dealer accounts can be linked'; END IF;
END$$
CREATE TRIGGER dealer_link_user_update BEFORE UPDATE ON dealer_accounts FOR EACH ROW
BEGIN
 IF NEW.user_id<>OLD.user_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Linked account identity is immutable'; END IF;
END$$
DELIMITER ;
