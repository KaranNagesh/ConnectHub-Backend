CREATE TABLE IF NOT EXISTS rooms (
    room_id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(100), description VARCHAR(500), type VARCHAR(10) NOT NULL,
    created_by_id INT NOT NULL, avatar_url VARCHAR(500),
    is_private BOOLEAN NOT NULL DEFAULT FALSE, max_members INT DEFAULT 500,
    last_message_at DATETIME(6), pinned_message_id VARCHAR(36),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_room_type (type), INDEX idx_room_creator (created_by_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS room_members (
    member_id INT AUTO_INCREMENT PRIMARY KEY,
    room_id VARCHAR(36) NOT NULL, user_id INT NOT NULL,
    role VARCHAR(10) NOT NULL DEFAULT 'MEMBER',
    joined_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_read_at DATETIME(6), is_muted BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE KEY uk_room_user (room_id, user_id),
    INDEX idx_rm_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
