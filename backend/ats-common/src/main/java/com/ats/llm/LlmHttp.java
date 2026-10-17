package com.ats.llm;

import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.Map;
import java.util.function.LongConsumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One place for "POST JSON, get JSON back, fail loudly" — used by all three clients. */
final class LlmHttp {

    private static final Logger log = LoggerFactory.getLogger(LlmHttp.class);

    // Network blips (DNS, refused connection) usually clear in seconds — wait progressively
    // longer before giving up (~60s total). Read timeouts are NOT retried here: one slow call
    // has already waited 60s, and five more would break Kafka's 5-minute poll deadline.
    static final long[] NETWORK_BACKOFF_SECONDS = {2, 4, 8, 16, 30};

    // how we wait between network retries — tests swap in a no-op so they run instantly
    static LongConsumer sleeper = LlmHttp::sleepSeconds;

    /** "Could not reach the server at all" — the only failure worth waiting out. */
    private static final class NetworkUnreachable extends Exception {
        NetworkUnreachable(Throwable cause) {
            super(cause);
        }

        String reason() {
            return rootCause(getCause());
        }
    }

    static JsonNode postJson(HttpClient http, ObjectMapper mapper, String url,
                             JsonNode body, Map<String, String> headers, Duration readTimeout) {
        for (int attempt = 0; ; attempt++) {
            try {
                return postOnce(http, mapper, url, body, headers, readTimeout);
            } catch (NetworkUnreachable e) {
                if (attempt >= NETWORK_BACKOFF_SECONDS.length) {
                    throw new IllegalStateException("LLM call failed: cannot reach " + host(url)
                            + " after " + (attempt + 1) + " tries — " + e.reason(), e);
                }
                log.warn("cannot reach {} ({}), retrying in {}s", host(url), e.reason(), NETWORK_BACKOFF_SECONDS[attempt]);
                sleeper.accept(NETWORK_BACKOFF_SECONDS[attempt]);
            }
        }
    }

    private static JsonNode postOnce(HttpClient http, ObjectMapper mapper, String url, JsonNode body,
                                     Map<String, String> headers, Duration readTimeout) throws NetworkUnreachable {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(readTimeout) // spec's read timeout: give up if the model hangs
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            headers.forEach(request::header); // provider-specific auth headers

            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) { // anything not 2xx is a failure
                // Include a snippet of the body — that's where providers explain themselves
                throw new IllegalStateException("LLM call failed: HTTP " + response.statusCode()
                        + " from " + url + " — " + snippet(response.body()));
            }
            return mapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            if (isNetworkUnreachable(e)) {
                throw new NetworkUnreachable(e);
            }
            // timeouts, bad JSON... — escape to the consumer's retry → DLQ machinery, WITH the real reason
            throw new IllegalStateException("LLM call failed: " + url + " — " + rootCause(e), e);
        }
    }

    /** DNS failure, refused connection or a connect timeout anywhere in the cause chain. */
    static boolean isNetworkUnreachable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof UnresolvedAddressException || t instanceof UnknownHostException
                    || t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** The innermost exception, e.g. "UnresolvedAddressException (DNS)" or "HttpTimeoutException: request timed out". */
    static String rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String name = t.getClass().getSimpleName();
        if (t instanceof UnresolvedAddressException || t instanceof UnknownHostException) {
            name += " (DNS)";
        }
        return t.getMessage() == null ? name : name + ": " + t.getMessage();
    }

    private static String host(String url) {
        return URI.create(url).getHost();
    }

    private static void sleepSeconds(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting out a network failure", ie);
        }
    }

    private static String snippet(String body) {
        return body == null ? "" : body.substring(0, Math.min(body.length(), 300));
    }

    private LlmHttp() {
    }
}
