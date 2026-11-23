package com.ats.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationRepository extends JpaRepository<EmailVerification, UUID> {

    /** The account's code that is still unused (at most one: sending a new code ends the others). */
    Optional<EmailVerification> findFirstByUserIdAndUsedAtIsNullOrderByExpiresAtDesc(UUID userId);

    /** "Send a new code" — every older unused code stops working at once. */
    @Modifying
    @Query("update EmailVerification v set v.expiresAt = :now where v.userId = :userId and v.usedAt is null")
    int expireAllFor(@Param("userId") UUID userId, @Param("now") Instant now);
}
