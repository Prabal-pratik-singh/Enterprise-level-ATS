package com.ats.candidate;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Live profile pushes go to the account's own open pages — never to anybody else's. */
class ProfileSseHubTest {

    private final ProfileSseHub hub = new ProfileSseHub();
    private final UUID sneha = UUID.randomUUID();
    private final UUID ravi = UUID.randomUUID();

    @Test
    void aPushReachesOnlyThatAccountsPages() {
        hub.register(sneha);
        hub.register(sneha);   // two tabs

        assertThat(hub.watched(sneha)).isTrue();
        assertThat(hub.watched(ravi)).isFalse();
        assertThat(hub.push(sneha, "her profile")).isEqualTo(2);
        assertThat(hub.push(ravi, "his profile")).isZero();
    }
}
