CREATE TABLE IF NOT EXISTS lh_codes (
    id INT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    batch VARCHAR(32) NOT NULL DEFAULT '',
    days INT UNSIGNED NOT NULL DEFAULT 30,
    status TINYINT UNSIGNED NOT NULL DEFAULT 1,
    device_id CHAR(64) NOT NULL DEFAULT '',
    device_fp CHAR(64) NOT NULL DEFAULT '',
    activated_at DATETIME NULL,
    expire_at DATETIME NULL,
    note VARCHAR(255) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code),
    KEY idx_device (device_id),
    KEY idx_expire (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lh_nonces (
    nonce CHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (nonce),
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS lh_events (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL DEFAULT '',
    device_id CHAR(64) NOT NULL DEFAULT '',
    action VARCHAR(24) NOT NULL,
    ip VARCHAR(45) NOT NULL DEFAULT '',
    detail VARCHAR(255) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_created (created_at),
    KEY idx_device (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
