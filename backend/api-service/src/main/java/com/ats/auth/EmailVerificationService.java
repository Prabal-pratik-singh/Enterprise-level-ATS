package com.ats.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.ats.domain.CandidateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Verify your e-mail" by a 6-digit code (OTP): the code is mailed (Mailpit catches it locally) and the
 * signed-in candidate types it in. Then the address is verified and the account claim runs — older
 * applications sent with the same e-mail are linked to the account. Before that nothing is linked:
 * anyone can type any address into a sign-up form, but only its owner can read the mail.
 *
 * Safety: 10 minutes per code, 5 wrong tries burn it, a new code ends the old one, and only an HMAC
 * of the code (keyed with the server secret) is stored — a plain hash of 6 digits could be reversed
 * in a second by trying all 1,000,000 of them.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    static final int MAX_TRIES = 5;

    private final EmailVerificationRepository codes;
    private final UserAccountRepository users;
    private final CandidateRepository candidates;
    private final JavaMailSender mailer;
    private final AuthProperties props;
    private final TransactionTemplate tx;

    public EmailVerificationService(EmailVerificationRepository codes, UserAccountRepository users,
                                    CandidateRepository candidates, JavaMailSender mailer, AuthProperties props,
                                    TransactionTemplate tx) {
        this.codes = codes;
        this.users = users;
        this.candidates = candidates;
        this.mailer = mailer;
        this.props = props;
        this.tx = tx;
    }

    /**
     * Ends every older code and stores the new one (one short transaction), THEN mails it. A mail failure
     * (Mailpit down) is logged, never thrown — sign-up still succeeds and "send a new code" retries.
     */
    public void sendCode(UserAccount user) {
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));   // 000000..999999
        tx.executeWithoutResult(status -> {
            codes.expireAllFor(user.getId(), Instant.now());
            codes.save(new EmailVerification(user.getId(), hmac(code), Instant.now().plus(CODE_LIFETIME)));
        });

        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(props.mailFrom());
        mail.setTo(user.getEmail());
        mail.setSubject("Your ATSPipeline code: " + code);
        mail.setText("""
                Hi,

                your ATSPipeline verification code is %s

                It works for 10 minutes. Type it on your dashboard to confirm this e-mail address.
                If you did not create an account, you can ignore this e-mail.
                """.formatted(code));
        try {
            mailer.send(mail);
        } catch (MailException e) {
            log.warn("verification e-mail to {} not sent: {}", Masking.email(user.getEmail()), e.getMessage());
        }
    }

    /** The signed-in user's code: verifies the address once and links earlier applications (candidates only). */
    @Transactional(noRollbackFor = ResponseStatusException.class)   // a wrong try must stay counted
    public UserAccount verifyCode(UUID userId, String code) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "account no longer exists"));
        if (user.isEmailVerified()) {
            return user;   // a second submit (double click) — it already worked
        }
        EmailVerification live = codes.findFirstByUserIdAndUsedAtIsNullOrderByExpiresAtDesc(userId)
                .orElseThrow(() -> invalid("no active code — send a new one"));
        if (!live.getExpiresAt().isAfter(Instant.now())) {
            throw invalid("this code expired — send a new one");
        }
        if (live.getAttempts() >= MAX_TRIES) {
            throw invalid("too many wrong tries — send a new code");
        }
        String attempt = code == null ? "" : code.trim();
        if (!MessageDigest.isEqual(live.getTokenHash().getBytes(StandardCharsets.UTF_8),
                hmac(attempt).getBytes(StandardCharsets.UTF_8))) {
            live.countWrongTry();
            int left = MAX_TRIES - live.getAttempts();
            throw invalid(left == 0 ? "too many wrong tries — send a new code"
                    : "wrong code — " + left + (left == 1 ? " try left" : " tries left"));
        }
        live.markUsed();
        user.markEmailVerified();
        int linked = user.getRole() == Role.CANDIDATE ? candidates.claimByEmail(user.getEmail(), user.getId()) : 0;
        log.info("e-mail verified for user {}; linked {} earlier application row(s)", user.getId(), linked);
        return user;
    }

    /** HMAC-SHA256 keyed with the server secret, hex — what is stored instead of the code. */
    String hmac(String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
