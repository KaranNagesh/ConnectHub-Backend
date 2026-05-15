CREATE TABLE IF NOT EXISTS messages (
    message_id VARCHAR(36) PRIMARY KEY,
    room_id VARCHAR(36) NOT NULL, sender_id INT NOT NULL,
    content TEXT, type VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    media_url VARCHAR(500), thumbnail_url VARCHAR(500),
    reply_to_message_id VARCHAR(36),
    is_edited BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'SENT',
    sent_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    edited_at DATETIME(6),
    INDEX idx_msg_room_sent (room_id, sent_at),
    INDEX idx_msg_sender (sender_id),
    INDEX idx_msg_room_deleted (room_id, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS message_reactions (
    reaction_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id VARCHAR(36) NOT NULL, user_id INT NOT NULL,
    emoji VARCHAR(20) NOT NULL, created_at DATETIME(6),
    UNIQUE KEY uk_msg_user_emoji (message_id, user_id, emoji),
    INDEX idx_reaction_msg (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
