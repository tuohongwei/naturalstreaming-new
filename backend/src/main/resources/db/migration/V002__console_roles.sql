CREATE TABLE ns_console_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(96) NOT NULL UNIQUE,
    permissions JSON NOT NULL,
    builtin BOOLEAN NOT NULL DEFAULT FALSE
);

INSERT INTO ns_console_role(id,name,permissions,builtin) VALUES
    (1, '超级管理员', JSON_ARRAY('assets.read','assets.write','themes.read','themes.write',
        'presets.read','presets.write','presets.publish','accounts.manage','roles.manage','members.manage'), TRUE),
    (2, '内容编辑', JSON_ARRAY('assets.read','assets.write','themes.read','themes.write',
        'presets.read','presets.write'), TRUE),
    (3, '发布审核', JSON_ARRAY('assets.read','themes.read','presets.read','presets.publish'), TRUE);

ALTER TABLE ns_account
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN console_role_id BIGINT NULL,
    ADD CONSTRAINT ns_account_console_role_fk FOREIGN KEY (console_role_id) REFERENCES ns_console_role(id);

UPDATE ns_account SET console_role_id = 1 WHERE role = 'admin';
