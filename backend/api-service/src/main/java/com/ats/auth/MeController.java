package com.ats.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** "Who am I?" — always the caller's own account: the id comes from the signed token, never from the URL. */
@RestController
public class MeController {

    private final UserAccountRepository users;

    public MeController(UserAccountRepository users) {
        this.users = users;
    }

    @GetMapping("/api/me")
    public AuthController.UserView me(@AuthenticationPrincipal Jwt token) {
        return users.findById(UUID.fromString(token.getSubject()))
                .map(AuthController.UserView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "account no longer exists"));
    }
}
