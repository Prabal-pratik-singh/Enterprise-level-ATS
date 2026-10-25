package com.ats.auth;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * A fixed-window counter per (action, IP): e.g. at most 10 sign-in attempts from one IP per minute.
 * The 11th gets 429 with a Retry-After header. Counters live in memory — the demo runs one API
 * instance, so there is no need for Redis (several instances would each count separately).
 */
@Component
public class RateLimiter {

    private record Window(long resetAtMillis, int count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;   // milliseconds; replaceable in tests

    public RateLimiter() {
        this(System::currentTimeMillis);
    }

    RateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /** Counts one attempt; throws 429 once the limit for this window is used up. */
    public void check(String action, String ip, int limit, Duration per) {
        long now = clock.getAsLong();
        Window window = windows.compute(action + "|" + ip, (key, old) ->
                old == null || now >= old.resetAtMillis()
                        ? new Window(now + per.toMillis(), 1)          // first attempt of a new window
                        : new Window(old.resetAtMillis(), old.count() + 1));
        if (window.count() > limit) {
            long retryAfterSeconds = Math.max(1, (window.resetAtMillis() - now + 999) / 1000);
            throw tooManyAttempts(retryAfterSeconds);
        }
    }

    /** The browser's IP: our nginx passes it as X-Real-IP; a request straight to :8082 has none. */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Real-IP");
        return forwarded != null && !forwarded.isBlank() ? forwarded.trim() : request.getRemoteAddr();
    }

    /** Forget finished windows, so the map does not grow forever. */
    @Scheduled(fixedDelay = 600_000)
    public void evictExpired() {
        long now = clock.getAsLong();
        windows.values().removeIf(window -> now >= window.resetAtMillis());
    }

    private static ResponseStatusException tooManyAttempts(long retryAfterSeconds) {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "too many attempts — try again in " + retryAfterSeconds + " s") {
            @Override
            public HttpHeaders getHeaders() {
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
                return headers;
            }
        };
    }
}
