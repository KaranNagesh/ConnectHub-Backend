-- ─────────────────────────────────────────────────────────────────────────────
-- Payment Service — Initial Schema
-- V1__init_payment_schema.sql
-- ─────────────────────────────────────────────────────────────────────────────

-- Subscription plans enum is stored as VARCHAR to allow flexible evolution

CREATE TABLE IF NOT EXISTS subscriptions (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         INT             NOT NULL,
    plan            VARCHAR(20)     NOT NULL DEFAULT 'FREE',   -- FREE | PRO | BUSINESS
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | CANCELLED | EXPIRED | PENDING
    razorpay_sub_id VARCHAR(100)    NULL,
    start_date      DATETIME        NOT NULL,
    end_date        DATETIME        NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_subscription_user (user_id),
    INDEX idx_subscription_status (status),
    INDEX idx_subscription_plan (plan)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payments (
    id                   BIGINT          NOT NULL AUTO_INCREMENT,
    subscription_id      BIGINT          NOT NULL,
    razorpay_payment_id  VARCHAR(100)    NULL,
    razorpay_order_id    VARCHAR(100)    NULL,
    amount               DECIMAL(10, 2)  NOT NULL,
    currency             VARCHAR(10)     NOT NULL DEFAULT 'INR',
    status               VARCHAR(20)     NOT NULL DEFAULT 'PENDING', -- PENDING | CAPTURED | FAILED | REFUNDED
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_payment_subscription (subscription_id),
    INDEX idx_payment_razorpay_id (razorpay_payment_id),
    CONSTRAINT fk_payment_subscription
        FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
