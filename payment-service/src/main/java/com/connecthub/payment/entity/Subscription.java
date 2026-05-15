package com.connecthub.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Represents a user's subscription plan record.
 * One row per user (enforced via unique constraint on userId).
 */
@Entity
@Table(name = "subscriptions",
        uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "user_email", length = 255)
    private String userEmail;

    /**
     * Plan tier: FREE | PRO | BUSINESS
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String plan = "FREE";

    /**
     * Lifecycle status: ACTIVE | CANCELLED | EXPIRED | PENDING
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    /** Razorpay subscription id for recurring billing management. */
    @Column(name = "razorpay_sub_id", length = 100)
    private String razorpaySubId;

    @Column(nullable = false)
    private LocalDateTime startDate;

    private LocalDateTime endDate;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
