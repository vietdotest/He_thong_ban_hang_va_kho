INSERT INTO roles (code, name)
VALUES ('ADMIN', 'Quản trị viên');

INSERT INTO users (
    username,
    username_normalized,
    email,
    email_normalized,
    full_name,
    password_hash,
    status
) VALUES (
    'admin',
    'admin',
    'admin@local.test',
    'admin@local.test',
    'Quản trị viên local',
    '$2a$12$vclBSd7aqwjDAHl9M.mSwO8ND4cgnGAAAbbscqtVMio.tkwwhkirC',
    'ACTIVE'
);

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.code = 'ADMIN'
WHERE u.username_normalized = 'admin';
