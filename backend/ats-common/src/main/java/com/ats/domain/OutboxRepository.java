package com.ats.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxRow, UUID> {

    /**
     * SKIP LOCKED lets multiple relay instances (api + every worker) poll the
     * same table without publishing the same row twice.
     */
    @Query(value = "select * from outbox where published_at is null order by created_at limit :batch for update skip locked",
            nativeQuery = true)
    List<OutboxRow> lockNextUnpublished(@Param("batch") int batch);
}
