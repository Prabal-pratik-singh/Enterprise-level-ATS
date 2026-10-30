package com.ats.candidate;

import com.ats.events.EventEnvelope;
import com.ats.events.Topics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Live status for the "My Resume" page: whenever a profile event lands (parsed, changed — including a
 * failure — or embedded), the account's current profile is pushed to its open pages. Every one of these
 * events is written through the outbox AFTER its database change, so the pushed view is never older than
 * the event. Best effort: a failed push is logged, never retried — the page also reloads on its own.
 */
@Component
public class ProfileEventsListener {

    private static final Logger log = LoggerFactory.getLogger(ProfileEventsListener.class);

    private final ObjectMapper mapper;
    private final ProfileSseHub hub;
    private final ProfileViews views;

    public ProfileEventsListener(ObjectMapper mapper, ProfileSseHub hub, ProfileViews views) {
        this.mapper = mapper;
        this.hub = hub;
        this.views = views;
    }

    // latest offsets, no ledger: only pages open right now matter, and a repeated push is harmless
    @KafkaListener(topics = {Topics.PROFILE_RESUME_PARSED, Topics.PROFILE_CHANGED, Topics.PROFILE_EMBEDDED},
            groupId = "api-profile-sse",
            properties = "auto.offset.reset=latest")
    public void onProfileEvent(String message) {
        try {
            EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
            if (event.subjectId() == null || !hub.watched(event.subjectId())) {
                return;   // nobody has this account's page open
            }
            int pages = hub.push(event.subjectId(), views.viewOf(event.subjectId()));
            log.info("pushed {} to {} open profile page(s)", event.eventType(), pages);
        } catch (Exception e) {
            log.warn("profile SSE push failed: {}", e.getMessage());
        }
    }
}
