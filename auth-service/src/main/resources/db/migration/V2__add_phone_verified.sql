ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE AFTER email_verified;
CREATE INDEX idx_user_phone ON users (phone_number);
