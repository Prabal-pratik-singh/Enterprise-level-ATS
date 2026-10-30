package com.ats.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProfileResumeRepository extends JpaRepository<ProfileResume, UUID> {

    Optional<ProfileResume> findByUserIdAndVersion(UUID userId, int version);

    /** Every version, newest first — the "my resume versions" list. */
    List<ProfileResume> findByUserIdOrderByVersionDesc(UUID userId);

    @Query("select coalesce(max(r.version), 0) from ProfileResume r where r.userId = :userId")
    int maxVersion(@Param("userId") UUID userId);
}
