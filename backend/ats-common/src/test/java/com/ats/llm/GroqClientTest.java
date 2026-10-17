package com.ats.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Groq's fallback and LlmHttp's network retries, against a tiny local fake server (JDK built-in). */
class GroqClientTest {

    private static final String JSON_VALIDATE_FAILED = """
            {"error":{"message":"Failed to validate JSON.","type":"invalid_request_error","code":"json_validate_failed"}}""";
    private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> modelsAsked = new ArrayList<>();
    private HttpServer server;
    private int status20b;      // what the fake answers when asked for the 20b model
    private String body20b;

    @BeforeEach
    void start() throws IOException {
        LlmHttp.sleeper = seconds -> { };   // network backoff runs instantly in tests
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String model = mapper.readTree(request).path("model").asText();
            modelsAsked.add(model);
            boolean big = model.endsWith("120b");
            byte[] answer = (big ? OK : body20b).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(big ? 200 : status20b, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        LlmHttp.sleeper = seconds -> { };
    }

    private GroqClient client(String fallback, String url) {
        LlmProperties props = new LlmProperties("groq", 5, 10, null,
                new LlmProperties.Groq("test-key", "openai/gpt-oss-20b", fallback), null);
        return new GroqClient(HttpClient.newHttpClient(), mapper, props, Duration.ofSeconds(10), url);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
    }

    @Test
    void jsonValidateFailedSwitchesStraightToTheStrongerModel() {
        status20b = 400;
        body20b = JSON_VALIDATE_FAILED;
        String answer = client("openai/gpt-oss-120b", url()).complete("sys", "user", "{}");
        assertThat(answer).isEqualTo("{\"ok\":true}");
        assertThat(modelsAsked).containsExactly("openai/gpt-oss-20b", "openai/gpt-oss-120b");
    }

    @Test
    void otherErrorsAreNotRetriedWithTheStrongerModel() {
        status20b = 400;
        body20b = "{\"error\":{\"message\":\"context too long\",\"code\":\"context_length_exceeded\"}}";
        assertThatThrownBy(() -> client("openai/gpt-oss-120b", url()).complete("sys", "user", "{}"))
                .hasMessageContaining("HTTP 400");
        assertThat(modelsAsked).containsExactly("openai/gpt-oss-20b");
    }

    @Test
    void withoutAFallbackTheErrorEscalatesAsBefore() {
        status20b = 400;
        body20b = JSON_VALIDATE_FAILED;
        assertThatThrownBy(() -> client(null, url()).complete("sys", "user", "{}"))
                .hasMessageContaining("json_validate_failed");
        assertThat(modelsAsked).containsExactly("openai/gpt-oss-20b");
    }

    @Test
    void aSlowAnswerTimesOutOnceAndIsNotRetried() {
        server.createContext("/slow", exchange -> {
            modelsAsked.add("slow");
            try {
                Thread.sleep(2_000);   // longer than the 1s read timeout below
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        List<Long> waits = new ArrayList<>();
        LlmHttp.sleeper = waits::add;
        LlmProperties props = new LlmProperties("groq", 5, 1, null,
                new LlmProperties.Groq("test-key", "openai/gpt-oss-20b", null), null);
        GroqClient slow = new GroqClient(HttpClient.newHttpClient(), mapper, props, Duration.ofSeconds(1),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/slow");
        assertThatThrownBy(() -> slow.complete("sys", "user", "{}"))
                .hasMessageContaining("HttpTimeoutException");   // the real reason, not just "failed"
        assertThat(waits).isEmpty();                              // no network backoff for a timeout
        assertThat(modelsAsked).containsExactly("slow");          // asked exactly once
    }

    @Test
    void anUnreachableHostIsRetriedWithBackoffThenNamedPlainly() {
        List<Long> waits = new ArrayList<>();
        LlmHttp.sleeper = waits::add;
        // ".invalid" can never resolve — a guaranteed DNS failure, like the 09:35 one
        assertThatThrownBy(() -> client(null, "http://no-such-host.invalid/chat").complete("sys", "user", "{}"))
                .hasMessageContaining("cannot reach no-such-host.invalid after 6 tries");
        assertThat(waits).containsExactly(2L, 4L, 8L, 16L, 30L);
    }
}
