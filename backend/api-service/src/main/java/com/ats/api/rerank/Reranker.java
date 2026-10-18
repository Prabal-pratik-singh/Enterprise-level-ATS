package com.ats.api.rerank;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.ats.llm.LlmClient;
import com.ats.llm.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Asks the LLM to judge the top candidates like a senior recruiter: must-have coverage WITH
 * evidence, depth vs breadth, red flags, a 3-line summary — and a nudge of at most ±10%.
 * 5 compact cards per call: short enough that an answer is never cut off, and 50 candidates
 * still fit the free tier's 8,000 tokens/minute in ~3 minutes.
 */
@Service
public class Reranker {

    static final int BATCH = 5;   // measured 2026-10-19: 10 per call got answers cut off (17-24/50 judged)

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    private final ObjectProvider<LlmClient> llm;   // lazy: the api only has one when app.llm is configured
    private final LlmProperties props;
    private final JobBrief.Loader briefs;
    private final RerankRecorder recorder;
    private final ObjectMapper mapper;

    public Reranker(ObjectProvider<LlmClient> llm, LlmProperties props, JobBrief.Loader briefs, RerankRecorder recorder,
                    ObjectMapper mapper) {
        this.llm = llm;
        this.props = props;
        this.briefs = briefs;
        this.recorder = recorder;
        this.mapper = mapper;
    }

    public boolean available() {
        return llm.getIfAvailable() != null;
    }

    public void run(UUID jobId, List<RerankCard> cards) {
        LlmClient client = llm.getObject();
        JobBrief job = briefs.forJob(jobId);
        String model = modelName();
        long t0 = System.currentTimeMillis();
        List<RerankCard> unjudged = judgeAll(client, job, jobId, cards, model);
        if (!unjudged.isEmpty()) {
            // the model sometimes leaves candidates out (or a verdict was discarded): one more round, just them
            log.info("rerank: {} candidate(s) without a verdict — asking once more", unjudged.size());
            unjudged = judgeAll(client, job, jobId, unjudged, model);
        }
        for (RerankCard card : unjudged) {
            try {
                recorder.clear(jobId, card.applicationId(), "no unbiased verdict this run");   // never keep a stale one
            } catch (Exception e) {
                log.warn("could not clear the old rerank verdict of {}: {}", card.applicationId(), e.getMessage());
            }
        }
        log.info("rerank of job {} done: {}/{} candidates judged by {} in {}s", jobId,
                cards.size() - unjudged.size(), cards.size(), model, (System.currentTimeMillis() - t0) / 1000);
    }

    /** One pass in batches; returns the cards that got NO recorded verdict. */
    List<RerankCard> judgeAll(LlmClient client, JobBrief job, UUID jobId, List<RerankCard> cards, String model) {
        List<RerankCard> leftover = new ArrayList<>();
        for (List<RerankCard> batch : partition(cards, BATCH)) {
            Set<UUID> judged = new HashSet<>();
            try {
                // straight to the stronger model: measured 2026-10-19, the small one returned invalid
                // JSON on 9 of 10 rerank calls — each failure cost a call AND per-minute token budget
                String answer = client.completeWithStrongerModel(RerankPrompts.SYSTEM,
                        RerankPrompts.user(mapper, job, batch), RerankPrompts.SCHEMA);
                for (RerankPrompts.Verdict v : RerankPrompts.parse(mapper, answer, batch, model)) {
                    recorder.record(jobId, v);   // one transaction per candidate: visible on the page at once
                    judged.add(v.applicationId());
                }
            } catch (Exception e) {
                // one bad batch must not lose the others — its candidates keep their formula score
                log.warn("rerank batch of {} failed for job {}: {}", batch.size(), jobId, e.getMessage());
            }
            batch.stream().filter(c -> !judged.contains(c.applicationId())).forEach(leftover::add);
        }
        return leftover;
    }

    /** The model that actually judges: the provider's stronger one when configured. */
    private String modelName() {
        return switch (props.provider() == null ? "" : props.provider().toLowerCase()) {
            case "groq" -> props.groq().fallbackModel() != null && !props.groq().fallbackModel().isBlank()
                    ? props.groq().fallbackModel() : props.groq().model();
            case "gemini" -> props.gemini().model();
            case "ollama" -> props.ollama().model();
            default -> props.provider();
        };
    }

    static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return out;
    }
}
