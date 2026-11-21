CREATE TABLE ns_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(96) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role ENUM('app','admin') NOT NULL,
    UNIQUE KEY ns_role_username (role,username)
);
CREATE TABLE ns_session (
    token_hash CHAR(64) PRIMARY KEY,
    account_id BIGINT NOT NULL REFERENCES ns_account(id),
    expires_at DATETIME(6) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    INDEX (account_id, revoked)
);
CREATE TABLE ns_asset (
    sha256 VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(100) NOT NULL UNIQUE,
    filename VARCHAR(255) NOT NULL,
    sample_rate INT NOT NULL,
    frames BIGINT NOT NULL,
    metadata LONGTEXT NOT NULL,
    byte_size BIGINT NOT NULL
);
CREATE TABLE ns_theme (
    id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    name VARCHAR(160) NOT NULL
);
CREATE TABLE ns_preset (
    id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    scene_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    weather_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    function_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    current_revision BIGINT NULL,
    UNIQUE (scene_id,weather_id,function_id),
    FOREIGN KEY (scene_id) REFERENCES ns_theme(id)
);
CREATE TABLE ns_preset_revision (
    preset_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    document LONGTEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (preset_id,revision),
    FOREIGN KEY (preset_id) REFERENCES ns_preset(id)
);
CREATE TABLE ns_preset_asset (
    preset_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    PRIMARY KEY (preset_id,revision,sha256),
    FOREIGN KEY (preset_id,revision) REFERENCES ns_preset_revision(preset_id,revision),
    FOREIGN KEY (sha256) REFERENCES ns_asset(sha256)
);
