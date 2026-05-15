-- V3: Safety migration — make phone columns/index resilient across MySQL variants.
-- NOTE: MySQL does not support "CREATE INDEX IF NOT EXISTS" consistently,
-- so we guard index creation through information_schema.

-- Ensure phone_number column exists
SET @has_phone_number := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND column_name = 'phone_number'
);
SET @sql_phone_number := IF(
    @has_phone_number = 0,
    'ALTER TABLE users ADD COLUMN phone_number VARCHAR(20) AFTER full_name',
    'SELECT 1'
);
PREPARE stmt_phone_number FROM @sql_phone_number;
EXECUTE stmt_phone_number;
DEALLOCATE PREPARE stmt_phone_number;

-- Ensure idx_user_phone index exists
SET @has_idx_user_phone := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND index_name = 'idx_user_phone'
);
SET @sql_idx_user_phone := IF(
    @has_idx_user_phone = 0,
    'CREATE INDEX idx_user_phone ON users (phone_number)',
    'SELECT 1'
);
PREPARE stmt_idx_user_phone FROM @sql_idx_user_phone;
EXECUTE stmt_idx_user_phone;
DEALLOCATE PREPARE stmt_idx_user_phone;

-- Ensure phone_verified column exists
SET @has_phone_verified := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND column_name = 'phone_verified'
);
SET @sql_phone_verified := IF(
    @has_phone_verified = 0,
    'ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE AFTER email_verified',
    'SELECT 1'
);
PREPARE stmt_phone_verified FROM @sql_phone_verified;
EXECUTE stmt_phone_verified;
DEALLOCATE PREPARE stmt_phone_verified;
