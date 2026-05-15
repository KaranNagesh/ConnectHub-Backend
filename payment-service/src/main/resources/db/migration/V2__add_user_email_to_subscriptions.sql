ALTER TABLE subscriptions
    ADD COLUMN user_email VARCHAR(255) NULL AFTER user_id;
