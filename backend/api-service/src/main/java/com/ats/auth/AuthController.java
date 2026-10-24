package com.ats.auth;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sign-up, sign-in, refresh, sign-out. The access token comes back in the JSON body; the refresh
 * token only ever travels in an httpOnly cookie that page scripts cannot read and the browser
 * sends to /api/auth/* only.
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

    static final String REFRESH_COOKIE = "ats_refresh";

    private final AccountService accounts;
    private final TokenService tokens;
    private final AuthProperties props;

    public AuthController(AccountService accounts, TokenService tokens, AuthProperties props) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.props = props;
    }

    @PostMapping("/signup")
    public ResponseEntity<Session> signUp(@RequestBody Credentials body) {
        return session(HttpStatus.CREATED, tokens.issue(accounts.signUp(body.email(), body.password())));
    }

    @PostMapping("/login")
    public ResponseEntity<Session> logIn(@RequestBody Credentials body) {
        return session(HttpStatus.OK, tokens.issue(accounts.logIn(body.email(), body.password())));
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
