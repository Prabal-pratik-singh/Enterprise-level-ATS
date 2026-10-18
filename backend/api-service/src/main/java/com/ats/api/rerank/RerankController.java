package com.ats.api.rerank;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * POST /api/jobs/{id}/rerank — starts an LLM rerank of the job's top 50 in the BACKGROUND
 * (5 calls take ~2-3 min on the free tier — far too long to hold a browser request open).
 * Each judged candidate streams to open pages over SSE as soon as it is recorded.
 */
@RestController
public class RerankController {

    static final int TOP = 50;   // spec: top 50 by score

    private final RerankCards cards;
    private final Reranker reranker;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public RerankController(RerankCards cards, Reranker reranker) {
        this.cards = cards;
        this.reranker = reranker;
    }

    @PostMapping("/api/jobs/{jobId}/rerank")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> start(@PathVariable UUID jobId) {
        if (!reranker.available()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "no LLM configured for the api — set LLM_PROVIDER");
        }
        List<RerankCard> top = cards.topForJob(jobId, TOP);
        if (top.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "nothing to rerank for this job yet");
        }
        if (!running.add(jobId)) {   // one rerank per job at a time
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a rerank is already running for this job");
        }
        executor.execute(() -> {
            try {
                reranker.run(jobId, top);
            } finally {
                running.remove(jobId);
            }
        });
        return Map.of("jobId", jobId, "candidates", top.size(),
                "batches", (top.size() + Reranker.BATCH - 1) / Reranker.BATCH);
    }

    /** Lets the page show "reranking…" and re-enable the button when done. */
    @GetMapping("/api/jobs/{jobId}/rerank")
    public Map<String, Object> status(@PathVariable UUID jobId) {
        return Map.of("jobId", jobId, "running", running.contains(jobId));
    }
}
