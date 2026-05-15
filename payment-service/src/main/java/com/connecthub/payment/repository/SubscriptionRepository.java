package com.connecthub.payment.repository;

import com.connecthub.payment.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findByUserId(Integer userId);

    Optional<Subscription> findByRazorpaySubId(String razorpaySubId);

    boolean existsByUserId(Integer userId);

    void deleteByUserId(Integer userId);
}
