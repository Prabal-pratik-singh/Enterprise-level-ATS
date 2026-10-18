package com.ats.api.rerank;

import java.util.UUID;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One candidate as the LLM sees it: compact (~150 tokens), evidence-first, no vectors,
 * no contact details. `card` is the JSON shown to the model; `matchScore` is the formula's number.
 */
public record RerankCard(UUID applicationId, String name, double matchScore, ObjectNode card) {
}
