package com.connecthub.payment.repository;

import com.connecthub.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findBySubscriptionIdOrderByCreatedAtDesc(Long subscriptionId);

    Optional<Payment> findByRazorpayPaymentId(String razorpayPaymentId);

    void deleteBySubscriptionId(Long subscriptionId);
}
