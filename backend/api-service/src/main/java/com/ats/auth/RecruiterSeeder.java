package com.ats.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The dashboard's demo recruiter, created once at startup from RECRUITER_EMAIL / RECRUITER_PASSWORD
 * (.env) — never a hard-coded password. An existing account is left alone, so changing the password
 * in .env later does not change it in the database.
 */
@Component
public class RecruiterSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecruiterSeeder.class);

    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final AuthProperties props;

    public RecruiterSeeder(UserAccountRepository users, PasswordEncoder passwords, AuthProperties props) {
        this.users = users;
        this.passwords = passwords;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = AccountService.normalizeEmail(props.recruiterEmail());
        String password = props.recruiterPassword();
        if (email.isEmpty() || password == null || password.isEmpty()) {
            log.warn("no demo recruiter seeded: set RECRUITER_EMAIL and RECRUITER_PASSWORD in .env to sign in to the dashboard");
            return;
        }
        String problem = AccountService.emailProblem(email);
        if (problem == null) {
            problem = AccountService.passwordProblem(password);
        }
        if (problem != null) {
            throw new IllegalStateException("demo recruiter in .env: " + problem);
        }
        if (users.existsByEmail(email)) {
            return;
        }
        UserAccount recruiter = new UserAccount(email, passwords.encode(password), Role.RECRUITER);
        recruiter.markEmailVerified();
        users.save(recruiter);
        log.info("seeded the demo recruiter account {}", recruiter.getId());   // the id, never the email
    }
}
