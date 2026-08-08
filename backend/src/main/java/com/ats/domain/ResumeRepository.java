package com.ats.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ResumeRepository extends JpaRepository<Resume, UUID> {

    Optional<Resume> findByApplicationIdAndVersion(UUID applicationId, int version);

    @Query("select coalesce(max(r.version), 0) from Resume r where r.applicationId = :applicationId")
    int maxVersion(@Param("applicationId") UUID applicationId);
}
