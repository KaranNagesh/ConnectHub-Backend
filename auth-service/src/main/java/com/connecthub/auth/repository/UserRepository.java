package com.connecthub.auth.repository;

import com.connecthub.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {
    Optional<User> findByEmail(String email);
    Optional<User> findByUsername(String username);
    List<User> findAllByPhoneNumberOrderByUserIdDesc(String phoneNumber);
    Optional<User> findByProviderAndProviderId(String provider, String providerId);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByPhoneNumber(String phoneNumber);
    boolean existsByPhoneNumberAndUserIdNot(String phoneNumber, Integer userId);
    List<User> findByUserIdIn(List<Integer> ids);

    @Query("SELECT u FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%',:q,'%')) OR LOWER(u.fullName) LIKE LOWER(CONCAT('%',:q,'%'))")
    List<User> searchUsers(@Param("q") String query);

    void deleteByEmailVerifiedFalseAndCreatedAtBefore(LocalDateTime threshold);
    long countByActiveTrue();
    long countByActiveFalse();
}
