CREATE TABLE IF NOT EXISTS notifications (
    notification_id INT AUTO_INCREMENT PRIMARY KEY,
    recipient_id INT NOT NULL, actor_id INT,
    type VARCHAR(30) NOT NULL, title VARCHAR(200), message VARCHAR(500),
    room_id VARCHAR(36), message_id VARCHAR(36),
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_notif_recipient (recipient_id),
    INDEX idx_notif_read (recipient_id, is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
