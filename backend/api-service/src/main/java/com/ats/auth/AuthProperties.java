package com.ats.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * jwtSecret: signs the access tokens (JWT_SECRET, 32+ characters — the API refuses to start without it).
 * cookieSecure: send the refresh cookie over HTTPS only; false for the plain-http localhost demo.
 * recruiterEmail / recruiterPassword: the demo recruiter, created once at startup (RecruiterSeeder).
 * publicUrl: where links in e-mails point (the dashboard, APP_PUBLIC_URL). mailFrom: the sender address.
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl,
                             boolean cookieSecure, String recruiterEmail, String recruiterPassword,
                             String publicUrl, String mailFrom) {
}
