package com.ats.extractor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

/**
 * Judges LLM output against resources/schema/profile-schema.json. The LLM is
 * a text generator, not a contract — this class is the contract.
 */
@Component
public class ProfileValidator {

    private final JsonSchema schema;   // compiled once, reused for every resume
    private final String schemaJson;   // raw text — embedded into the prompts

    public ProfileValidator(ObjectMapper mapper) {
        try (InputStream in = getClass().getResourceAsStream("/schema/profile-schema.json")) {
            byte[] raw = in.readAllBytes();
            this.schemaJson = new String(raw, StandardCharsets.UTF_8);
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
                    .getSchema(mapper.readTree(raw));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load profile schema", e);
        }
    }

    /** Empty list = valid. Otherwise: human-readable reasons, fed back to the LLM on retry. */
    public List<String> validate(JsonNode candidateProfile) {
        return schema.validate(candidateProfile).stream()
                .map(ValidationMessage::getMessage)
                .toList();
    }

    public String schemaJson() {
        return schemaJson;
    }
}
