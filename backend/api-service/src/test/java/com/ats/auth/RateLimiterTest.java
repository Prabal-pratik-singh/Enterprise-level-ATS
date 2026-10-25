package com.ats.auth;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Per-IP limits on a fake clock: the 11th sign-in in a minute waits; other IPs and actions don't. */
class RateLimiterTest {

    private long now = 1_000_000;
    private final RateLimiter limiter = new RateLimiter(() -> now);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private void tenSignIns(String ip) {
        for (int i = 0; i < 10; i++) {
            limiter.check("login", ip, 10, MINUTE);
        }
    }

    @Test
    void theEleventhSignInWithinAMinuteIsRefusedWithRetryAfter() {
        tenSignIns("192.168.1.20");

        assertThatThrownBy(() -> limiter.check("login", "192.168.1.20", 10, MINUTE))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode().value()).isEqualTo(429);
                    assertThat(e.getHeaders().getFirst("Retry-After")).isEqualTo("60");
                    assertThat(e.getReason()).isEqualTo("too many attempts — try again in 60 s");
                });
    }

    @Test
    void otherIpsAndOtherActionsCountSeparately() {
        tenSignIns("192.168.1.20");

        limiter.check("login", "192.168.1.21", 10, MINUTE);                   // another IP
        limiter.check("signup", "192.168.1.20", 5, Duration.ofMinutes(10));   // another action
    }

    @Test
    void theWaitShrinksAndANewWindowStartsWhenTheMinuteIsOver() {
        tenSignIns("192.168.1.20");

        now += 42_000;   // 42 s later: same window, 18 s left
        assertThatThrownBy(() -> limiter.check("login", "192.168.1.20", 10, MINUTE))
                .hasMessageContaining("try again in 18 s");
        now += 18_000;   // the minute is over
        limiter.check("login", "192.168.1.20", 10, MINUTE);                   // allowed again
    }

    @Test
    void theBrowserIpComesFromNginxWhenPresent() {
        MockHttpServletRequest viaNginx = new MockHttpServletRequest();
        viaNginx.setRemoteAddr("172.18.0.9");               // the nginx container
        viaNginx.addHeader("X-Real-IP", "192.168.1.20");    // the browser, as nginx saw it
        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("172.18.0.1");                 // curl straight to :8082

        assertThat(RateLimiter.clientIp(viaNginx)).isEqualTo("192.168.1.20");
        assertThat(RateLimiter.clientIp(direct)).isEqualTo("172.18.0.1");
    }
}
