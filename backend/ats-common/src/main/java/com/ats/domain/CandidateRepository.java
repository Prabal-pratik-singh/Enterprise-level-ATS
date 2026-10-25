package com.ats.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CandidateRepository extends JpaRepository<Candidate, UUID> {

    /**
     * Account claim: link every application row carrying this e-mail to the account. Called only for an
     * address the user has proved they own (verified). Uses the lower(email) index; rows already linked
     * and bulk-upload placeholders (@intake.invalid) never match. Returns how many rows were linked.
     */
    @Modifying
    @Query(value = """
            update candidates set user_id = :userId
            where lower(email) = :email and user_id is null and email not like '%@intake.invalid'
            """, nativeQuery = true)
    int claimByEmail(@Param("email") String lowerCaseEmail, @Param("userId") UUID userId);
}
