package com.ats.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /** Callers pass the lower-cased email — that is how it is stored. */
    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);
}
