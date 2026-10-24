package com.ats.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rotation: a refresh ticket works exactly once; a second use signs the account out everywhere. */
class TokenServiceTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";

    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TokenService service = new TokenService(
            new NimbusJwtEncoder(new ImmutableSecret<>(SecurityConfig.signingKey(SECRET))), refreshTokens, users,
            new AuthProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(7), false, null, null));
    private final UserAccount asha = new UserAccount("asha@test.dev", "hash", Role.CANDIDATE);

    private final Map<String, RefreshToken> table = new HashMap<>();   // stands in for refresh_tokens

    @BeforeEach
    void fakeTheTable() {
        when(refreshTokens.save(any(RefreshToken.class))).thenAnswer(call -> {
            RefreshToken saved = call.getArgument(0);
            table.put(saved.getTokenHash(), saved);
            return saved;
        });
        when(refreshTokens.findByTokenHash(anyString()))
                .thenAnswer(call -> Optional.ofNullable(table.get(call.<String>getArgument(0))));
        when(users.findById(asha.getId())).thenReturn(Optional.of(asha));
    }

    @Test
    void onlyTheHashOfTheTicketIsStored() {
        TokenService.Tokens issued = service.issue(asha);

        assertThat(table).containsOnlyKeys(TokenService.sha256(issued.refreshToken()));
        assertThat(table).doesNotContainKey(issued.refreshToken());
    }

    @Test
    void theAccessTokenCarriesOnlyTheIdAndTheRole() {
        Jwt jwt = NimbusJwtDecoder.withSecretKey(SecurityConfig.signingKey(SECRET)).macAlgorithm(MacAlgorithm.HS256)
                .build().decode(service.issue(asha).accessToken());

        assertThat(jwt.getSubject()).isEqualTo(asha.getId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).isEqualTo(List.of("CANDIDATE"));
        assertThat(jwt.getClaims()).containsOnlyKeys("sub", "iat", "exp", "roles");   // no email, no name
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void aTicketWorksExactlyOnce() {
        TokenService.Tokens first = service.issue(asha);
        TokenService.Tokens second = service.refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(table.get(TokenService.sha256(first.refreshToken())).getRevokedAt()).isNotNull();
        assertThat(service.refresh(second.refreshToken()).user()).isSameAs(asha);   // the new ticket works
    }

    @Test
    void aReusedTicketSignsTheAccountOutEverywhere() {
        TokenService.Tokens first = service.issue(asha);
        service.refresh(first.refreshToken());                      // the owner refreshes first

        assertThatThrownBy(() -> service.refresh(first.refreshToken()))   // then a copy comes back
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already used");
        verify(refreshTokens).revokeAllFor(eq(asha.getId()), any(Instant.class));
    }

    @Test
    void anExpiredTicketIsRefused() {
        String hash = TokenService.sha256("ticket-from-last-month");
        table.put(hash, new RefreshToken(asha.getId(), hash, Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> service.refresh("ticket-from-last-month")).hasMessageContaining("expired");
    }

    @Test
    void anUnknownTicketIsRefused() {
        assertThatThrownBy(() -> service.refresh("never-issued")).hasMessageContaining("unknown refresh token");
    }
}
