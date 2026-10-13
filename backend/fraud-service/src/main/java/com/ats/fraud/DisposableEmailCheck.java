package com.ats.fraud;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Check 4 — a throwaway inbox (mailinator & co.). Weak on its own — some people
 * just guard their privacy — so it never flags anyone by itself; it only adds
 * weight when something else is already wrong.
 */
@Component
public class DisposableEmailCheck implements TrustCheck {

    static final double WEIGHT = 0.3;

    private final Set<String> domains;

    public DisposableEmailCheck() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/trust/disposable-domains.txt"), StandardCharsets.UTF_8))) {
            domains = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")) // allow comments in the list
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (Exception e) {
            throw new IllegalStateException("failed to load disposable-domains.txt", e);
        }
    }

    @Override
    public List<FraudFlag> check(TrustInput in) {
        Set<String> emails = new LinkedHashSet<>(); // the applied-with email AND the one printed on the resume
        if (in.applicantEmail() != null && !in.applicantEmail().isBlank()) {
            emails.add(in.applicantEmail().trim().toLowerCase(Locale.ROOT));
        }
        String onResume = in.profile().path("contact").path("email").asText(null);
        if (onResume != null && !onResume.isBlank()) {
            emails.add(onResume.trim().toLowerCase(Locale.ROOT));
        }
        for (String email : emails) {
            String domain = email.substring(email.lastIndexOf('@') + 1);
            if (isDisposable(domain)) {
                return List.of(new FraudFlag("DISPOSABLE_EMAIL", WEIGHT,
                        "Uses a throwaway email address (%s) — nobody can reach this candidate next month."
                                .formatted(email)));
            }
        }
        return List.of();
    }

    /** "mailinator.com" and "x.mailinator.com" both match. */
    private boolean isDisposable(String domain) {
        for (String d = domain; d.contains("."); d = d.substring(d.indexOf('.') + 1)) {
            if (domains.contains(d)) {
                return true;
            }
        }
        return false;
    }
}
