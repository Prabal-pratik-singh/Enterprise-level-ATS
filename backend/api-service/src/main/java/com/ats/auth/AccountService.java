package com.ats.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Sign-up and sign-in. Everyone who signs up is a CANDIDATE — the role never comes from the request. */
@Service
public class AccountService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    static final int MIN_PASSWORD_CHARS = 10;
    static final int MAX_PASSWORD_BYTES = 72;   // BCrypt only reads the first 72 bytes

    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    // compared against when the email is unknown, so "no such account" answers as slowly as "wrong password"
    private final String timingHash;

    public AccountService(UserAccountRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
        this.timingHash = passwords.encode("timing-equalizer");
    }

    @Transactional
    public UserAccount signUp(String rawEmail, String password) {
        String email = normalizeEmail(rawEmail);
        String problem = emailProblem(email);
        if (problem == null) {
            problem = passwordProblem(password);
        }
        if (problem != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problem);
        }
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "an account with this email already exists");
        }
        try {
            return users.saveAndFlush(new UserAccount(email, passwords.encode(password), Role.CANDIDATE));
        } catch (DataIntegrityViolationException e) {
            // the same email signed up a moment ago, between our check and our insert
            throw new ResponseStatusException(HttpStatus.CONFLICT, "an account with this email already exists");
        }
    }

    /** The same answer, in about the same time, whether the email or the password was wrong. */
    @Transactional(readOnly = true)
    public UserAccount logIn(String rawEmail, String password) {
        Optional<UserAccount> user = users.findByEmail(normalizeEmail(rawEmail));
        String attempt = password == null || tooLong(password) ? "" : password;
        boolean matches = passwords.matches(attempt, user.map(UserAccount::getPasswordHash).orElse(timingHash));
        if (!matches || user.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wrong email or password");
        }
        return user.get();
    }

    /** " Asha@Example.COM " → "asha@example.com" — the one spelling we store and look up. */
    static String normalizeEmail(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** null when fine, otherwise the reason (shown to the user). */
    static String emailProblem(String email) {
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            return "enter a valid email address";
        }
        return null;
    }

    /** null when fine, otherwise the reason (shown to the user). */
    static String passwordProblem(String password) {
        if (password == null || password.length() < MIN_PASSWORD_CHARS) {
            return "password must be at least " + MIN_PASSWORD_CHARS + " characters";
        }
        if (tooLong(password)) {
            return "password is too long (max " + MAX_PASSWORD_BYTES + " bytes)";
        }
        return null;
    }

    private static boolean tooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }
}
