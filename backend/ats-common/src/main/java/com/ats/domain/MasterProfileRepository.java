package com.ats.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MasterProfileRepository extends JpaRepository<MasterProfile, UUID> {

    /** The vector column is not mapped (pgvector) — this only says whether it is filled. */
    @Query(value = "select embedding is not null from candidate_master_profiles where user_id = :userId",
            nativeQuery = true)
    Optional<Boolean> hasEmbedding(@Param("userId") UUID userId);
}
