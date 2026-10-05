package com.ats.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {

    /** The candidate's full audit timeline, oldest first (report endpoint). */
    List<ApplicationEvent> findByApplicationIdOrderByCreatedAtAsc(UUID applicationId);
}
