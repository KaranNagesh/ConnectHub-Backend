CREATE TABLE IF NOT EXISTS media_files (
    media_id VARCHAR(36) PRIMARY KEY, uploader_id INT NOT NULL,
    room_id VARCHAR(36), message_id VARCHAR(36),
    filename VARCHAR(255) NOT NULL, original_name VARCHAR(255) NOT NULL,
    url VARCHAR(1000) NOT NULL, thumbnail_url VARCHAR(1000),
    mime_type VARCHAR(100) NOT NULL, size_kb BIGINT,
    uploaded_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_media_room (room_id), INDEX idx_media_uploader (uploader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
