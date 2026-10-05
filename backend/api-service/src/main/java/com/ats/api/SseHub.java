package com.ats.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Registry of open SSE connections, grouped by job. The browser opens one
 * long-lived HTTP response per job page; we push a "candidate" event whenever
 * the matcher scores someone. Dead connections are dropped on failed sends;
 * a heartbeat comment every 25s keeps proxies from closing idle streams.
 */
@Component
public class SseHub {

    private static final Logger log = LoggerFactory.getLogger(SseHub.class);

    private final Map<UUID, List<SseEmitter>> emittersByJob = new ConcurrentHashMap<>();

    public SseEmitter register(UUID jobId) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = no server-side timeout; client reconnects anyway
        List<SseEmitter> list = emittersByJob.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        return emitter;
    }

    /** Push one scored candidate to everyone watching this job's page. */
    public void push(UUID jobId, Object payload) {
        List<SseEmitter> list = emittersByJob.getOrDefault(jobId, List.of());
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("candidate").data(payload));
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter); // browser tab closed — forget it
            }
        }
        if (!list.isEmpty()) {
            log.info("pushed SSE update to {} watcher(s) of job {}", list.size(), jobId);
        }
    }

    /** Comment line (not an event) — keeps idle connections alive through proxies. */
    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        emittersByJob.values().forEach(list -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                    list.remove(emitter);
                }
            }
        });
    }
}
