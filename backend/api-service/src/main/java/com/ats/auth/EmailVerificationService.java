package com.ats.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import com.ats.domain.CandidateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Verify your e-mail": sends a one-time link (Mailpit catches it locally) and, when it is opened,
 * marks the address verified and runs the account claim — older applications sent with the same
 * e-mail are linked to the account. Before verification nothing is linked: anyone can type any
 * address into a sign-up form, but only its owner can open the mail.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final Duration LINK_LIFETIME = Duration.ofHours(24);

    private final EmailVerificationRepository links;
    private final UserAccountRepository users;
    private final CandidateRepository candidates;
    private final JavaMailSender mailer;
    private final AuthProperties props;

    public EmailVerificationService(EmailVerificationRepository links, UserAccountRepository users,
                                    CandidateRepository candidates, JavaMailSender mailer, AuthProperties props) {
        this.links = links;
        this.users = users;
        this.candidates = candidates;
        this.mailer = mailer;
        this.props = props;
    }

    /**
     * Stores the link (its own short transaction), then mails it. Not @Transactional on purpose: the
     * mail goes out only after the link is saved. A mail failure (Mailpit down) is logged, never thrown —
     * the sign-up still succeeds and "resend" sends a new link.
     */
    public void sendLink(UserAccount user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        links.save(new EmailVerification(user.getId(), TokenService.sha256(token), Instant.now().plus(LINK_LIFETIME)));

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(props.mailFrom());
        mail.setTo(user.getEmail());
        mail.setSubject("Verify your e-mail for ATSPipeline");
        mail.setText("""
                Hi,

                please confirm your e-mail address by opening this link (it works once, for 24 hours):

                %s/candidate/verify?token=%s

                If you did not create an account, you can ignore this e-mail.
                """.formatted(props.publicUrl(), token));
        try {
            mailer.send(mail);
        } catch (MailException e) {
            log.warn("verification e-mail to {} not sent: {}", Masking.email(user.getEmail()), e.getMessage());
        }
    }

    /** Opens a link: verifies the address once and links earlier applications (candidates only). */
    @Transactional
    public UserAccount verify(String token) {
        if (token == null || token.isBlank()) {
            throw invalid("this link is not valid");
        }
        EmailVerification link = links.findByTokenHash(TokenService.sha256(token))
                .orElseThrow(() -> invalid("this link is not valid"));
        UserAccount user = users.findById(link.getUserId()).orElseThrow(() -> invalid("this link is not valid"));
        if (link.getUsedAt() != null) {
            return user;   // opened twice (e.g. two clicks): it already worked the first time
        }
        if (link.getExpiresAt().isBefore(Instant.now())) {
            throw invalid("this link expired — ask for a new one");
        }
        link.markUsed();
        user.markEmailVerified();
        int linked = user.getRole() == Role.CANDIDATE ? candidates.claimByEmail(user.getEmail(), user.getId()) : 0;
        log.info("e-mail verified for user {}; linked {} earlier application row(s)", user.getId(), linked);
        return user;
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
