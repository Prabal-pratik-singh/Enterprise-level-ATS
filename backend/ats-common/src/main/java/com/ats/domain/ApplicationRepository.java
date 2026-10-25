package com.ats.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    /** "My applications": every application whose candidate row an account has claimed, newest first. */
    @Query("""
            select a from Application a, Candidate c
            where c.id = a.candidateId and c.userId = :userId
            order by a.createdAt desc
            """)
    List<Application> findAllOwnedBy(@Param("userId") UUID userId);
}
