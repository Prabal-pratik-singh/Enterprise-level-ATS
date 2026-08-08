package com.ats.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {
}
