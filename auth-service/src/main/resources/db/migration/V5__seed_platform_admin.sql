-- Placeholder migration: admin user is seeded by AdminSeeder.java on startup.
-- This ensures the BCrypt password hash is always valid and
-- the admin password can be configured via PLATFORM_ADMIN_PASSWORD env var.
-- This migration is intentionally empty (Flyway requires the file to exist).
SELECT 1;
