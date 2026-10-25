package com.ats.auth;

import java.time.Duration;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sign-up, sign-in, refresh, sign-out, e-mail verification. The access token comes back in the JSON
 * body; the refresh token only ever travels in an httpOnly cookie that page scripts cannot read and
 * the browser sends to /api/auth/* only. Sign-in, sign-up and resend are rate-limited per IP.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record Credentials(String email, String password) {
    }

    public record UserView(UUID id, String email, Role role, boolean emailVerified) {
        static UserView of(UserAccount user) {
            return new UserView(user.getId(), user.getEmail(), user.getRole(), user.isEmailVerified());
        }
    }

    public record Session(String accessToken, long expiresInSeconds, UserView user) {
    }

    public record VerifyRequest(String token) {
    }

    static final String REFRESH_COOKIE = "ats_refresh";

    // per IP: enough for a person, too slow for a password-guessing script
    private static final int LOGIN_LIMIT = 10;              // per minute
    private static final int SIGNUP_LIMIT = 5;              // per 10 minutes
    private static final int RESEND_LIMIT = 3;              // per 10 minutes (each one sends an e-mail)

    private final AccountService accounts;
    private final TokenService tokens;
    private final EmailVerificationService verifications;
    private final UserAccountRepository users;
    private final RateLimiter limiter;
    private final AuthProperties props;

    public AuthController(AccountService accounts, TokenService tokens, EmailVerificationService verifications,
                          UserAccountRepository users, RateLimiter limiter, AuthProperties props) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.verifications = verifications;
        this.users = users;
        this.limiter = limiter;
        this.props = props;
    }

    /** New candidate account, signed in at once; the verify link goes to the inbox (Mailpit locally). */
    @PostMapping("/signup")
    public ResponseEntity<Session> signUp(@RequestBody Credentials body, HttpServletRequest request) {
        limiter.check("signup", RateLimiter.clientIp(request), SIGNUP_LIMIT, Duration.ofMinutes(10));
        UserAccount user = accounts.signUp(body.email(), body.password());   // committed before the mail goes out
        verifications.sendLink(user);
        return session(HttpStatus.CREATED, tokens.issue(user));
    }

    @PostMapping("/login")
    public ResponseEntity<Session> logIn(@RequestBody Credentials body, HttpServletRequest request) {
        limiter.check("login", RateLimiter.clientIp(request), LOGIN_LIMIT, Duration.ofMinutes(1));
        return session(HttpStatus.OK, tokens.issue(accounts.logIn(body.email(), body.password())));
    }

    /** The link from the e-mail: verifies the address and links earlier applications with it. */
    @PostMapping("/verify-email")
    public UserView verifyEmail(@RequestBody VerifyRequest body) {
        return UserView.of(verifications.verify(body.token()));
    }

    /** A new link for the signed-in user (the first mail was lost, or the link expired). */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@AuthenticationPrincipal Jwt token, HttpServletRequest request) {
        UserAccount user = users.findById(UUID.fromString(token.getSubject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "account no longer exists"));
        if (user.isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "your e-mail is already verified");
        }
        limiter.check("resend", RateLimiter.clientIp(request), RESEND_LIMIT, Duration.ofMinutes(10));
        verifications.sendLink(user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<Session> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refresh) {
        if (refresh == null || refresh.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not signed in");
        }
        return session(HttpStatus.OK, tokens.refresh(refresh));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logOut(@CookieValue(name = REFRESH_COOKIE, required = false) String refresh) {
        if (refresh != null && !refresh.isBlank()) {
            tokens.revoke(refresh);
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO)).build();
    }

    private ResponseEntity<Session> session(HttpStatus status, TokenService.Tokens issued) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(issued.refreshToken(), props.refreshTokenTtl()))
                .body(new Session(issued.accessToken(), tokens.accessTokenSeconds(), UserView.of(issued.user())));
    }

    /** SameSite=Strict: never sent from another site. Path /api/auth: never sent with ordinary API calls. */
    private String refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAge)
                .build()
                .toString();
    }
}
