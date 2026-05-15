-- Normalize existing phone data before enforcing uniqueness.
UPDATE users
SET phone_number = NULL, phone_verified = FALSE
WHERE phone_number = '';

-- Keep the newest account for each duplicated phone number and clear older copies.
UPDATE users u
JOIN (
    SELECT phone_number, MAX(user_id) AS keep_user_id
    FROM users
    WHERE phone_number IS NOT NULL
    GROUP BY phone_number
    HAVING COUNT(*) > 1
) duplicates
  ON u.phone_number = duplicates.phone_number
 AND u.user_id <> duplicates.keep_user_id
SET u.phone_number = NULL,
    u.phone_verified = FALSE;

-- MySQL lacks consistent CREATE INDEX IF NOT EXISTS support, so guard it.
SET @has_unique_phone := (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND index_name = 'uk_user_phone'
);
SET @sql_unique_phone := IF(
    @has_unique_phone = 0,
    'CREATE UNIQUE INDEX uk_user_phone ON users (phone_number)',
    'SELECT 1'
);
PREPARE stmt_unique_phone FROM @sql_unique_phone;
EXECUTE stmt_unique_phone;
DEALLOCATE PREPARE stmt_unique_phone;
