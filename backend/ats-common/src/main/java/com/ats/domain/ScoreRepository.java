package com.ats.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScoreRepository extends JpaRepository<Score, UUID> {

    Optional<Score> findByApplicationId(UUID applicationId);
}
