package com.ats.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Two tickets per sign-in. The access token (15 min) is a signed JWT, checked on every request without
 * a database lookup. The refresh token (7 days) is a random value in an httpOnly cookie; only its SHA-256
 * is stored. Every refresh cancels the old ticket and issues a new pair (rotation). A cancelled ticket
 * coming back means someone copied it, so every ticket of that user is cancelled.
 */
@Service
public class TokenService {

    public record Tokens(String accessToken, String refreshToken, UserAccount user) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final RefreshTokenRepository refreshTokens;
    private final UserAccountRepository users;
    private final AuthProperties props;

    public TokenService(JwtEncoder encoder, RefreshTokenRepository refreshTokens, UserAccountRepository users,
                        AuthProperties props) {
        this.encoder = encoder;
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.props = props;
    }

    /** A fresh pair after a sign-in, a sign-up or a refresh. */
    @Transactional
    public Tokens issue(UserAccount user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user.getId(), sha256(refresh), Instant.now().plus(props.refreshTokenTtl())));
        return new Tokens(accessTokenFor(user), refresh, user);
    }

    /** One use per ticket. noRollbackFor: cancelling every ticket of a reused one must survive the 401. */
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Tokens refresh(String refresh) {
        RefreshToken old = refreshTokens.findByTokenHash(sha256(refresh))
                .orElseThrow(() -> unauthorized("unknown refresh token — sign in again"));
        if (old.getRevokedAt() != null) {
            // e.g. a thief copied the cookie on Monday, the owner refreshed first, now the copy comes back
            refreshTokens.revokeAllFor(old.getUserId(), Instant.now());
            throw unauthorized("refresh token already used — every session of this account was signed out");
        }
        if (old.getExpiresAt().isBefore(Instant.now())) {
            throw unauthorized("refresh token expired — sign in again");
        }
        old.revoke();
        UserAccount user = users.findById(old.getUserId())
                .orElseThrow(() -> unauthorized("account no longer exists"));
        return issue(user);
    }

    /** Sign-out: this ticket stops working at once. The 15-minute access token runs out by itself. */
    @Transactional
    public void revoke(String refresh) {
        refreshTokens.findByTokenHash(sha256(refresh)).ifPresent(RefreshToken::revoke);
    }

    /** Claims: sub = user id, roles = ["CANDIDATE"]. No email or name — the token is readable by anyone holding it. */
    String accessTokenFor(UserAccount user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenTtl()))
                .claim("roles", List.of(user.getRole().name()))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    long accessTokenSeconds() {
        return props.accessTokenTtl().toSeconds();
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this JVM", e);
        }
    }

    private static ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }
}
