package com.ats.candidate;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Open "My Resume" pages, grouped by ACCOUNT (the recruiter's SseHub groups by job). A page only ever
 * receives its own account's profile. Dead connections are dropped on failed sends; a heartbeat comment
 * every 25 s keeps proxies from closing idle streams.
 */
@Component
public class ProfileSseHub {

    private final Map<UUID, List<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter register(UUID userId) {
        SseEmitter emitter = new SseEmitter(0L);   // no server-side timeout; the page reconnects anyway
        List<SseEmitter> list = emittersByUser.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(e -> list.remove(emitter));
        return emitter;
    }

    public boolean watched(UUID userId) {
        return !emittersByUser.getOrDefault(userId, List.of()).isEmpty();
    }

    /** Sends the account's current profile to its open pages; returns how many got it. */
    public int push(UUID userId, Object profileView) {
        int sent = 0;
        List<SseEmitter> list = emittersByUser.getOrDefault(userId, List.of());
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("profile").data(profileView));
                sent++;
            } catch (IOException | IllegalStateException e) {
                list.remove(emitter);   // the tab was closed
            }
        }
        return sent;
    }

    @Scheduled(fixedDelay = 25_000)
    public void heartbeat() {
        emittersByUser.values().forEach(list -> {
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
