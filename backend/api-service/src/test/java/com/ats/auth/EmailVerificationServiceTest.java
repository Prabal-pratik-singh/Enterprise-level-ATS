package com.ats.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.ats.domain.CandidateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The verify link: mailed once, stored as a hash, works once, and only then links earlier applications. */
class EmailVerificationServiceTest {

    private final EmailVerificationRepository links = mock(EmailVerificationRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final CandidateRepository candidates = mock(CandidateRepository.class);
    private final JavaMailSender mailer = mock(JavaMailSender.class);
    private final EmailVerificationService service = new EmailVerificationService(links, users, candidates, mailer,
            new AuthProperties(null, null, null, false, null, null, "http://localhost:5174", "no-reply@ats.local"));

    private final Map<String, EmailVerification> table = new HashMap<>();   // stands in for email_verifications
    private final UserAccount prabal = new UserAccount("prabal@test.dev", "hash", Role.CANDIDATE);

    @BeforeEach
    void fakeTheTable() {
        when(links.save(any(EmailVerification.class))).thenAnswer(call -> {
            EmailVerification saved = call.getArgument(0);
            table.put(saved.getTokenHash(), saved);
            return saved;
        });
        when(links.findByTokenHash(anyString()))
                .thenAnswer(call -> Optional.ofNullable(table.get(call.<String>getArgument(0))));
        when(users.findById(prabal.getId())).thenReturn(Optional.of(prabal));
    }

    /** Sends a link and returns the mail, the way the inbox would show it. */
    private SimpleMailMessage send(UserAccount user) {
        service.sendLink(user);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailer, atLeastOnce()).send(mail.capture());
        return mail.getValue();
    }

    private static String tokenIn(SimpleMailMessage mail) {
        String text = mail.getText();
        return text.substring(text.indexOf("token=") + 6).split("\\s")[0];
    }

    @Test
    void theMailLinksToTheDashboardAndOnlyTheHashIsStored() {
        SimpleMailMessage mail = send(prabal);
        String token = tokenIn(mail);

        assertThat(mail.getTo()).containsExactly("prabal@test.dev");
        assertThat(mail.getFrom()).isEqualTo("no-reply@ats.local");
        assertThat(mail.getText()).contains("http://localhost:5174/candidate/verify?token=" + token);
        assertThat(table).containsOnlyKeys(TokenService.sha256(token));
    }

    @Test
    void openingTheLinkVerifiesAndClaimsEarlierApplications() {
        String token = tokenIn(send(prabal));
        when(candidates.claimByEmail("prabal@test.dev", prabal.getId())).thenReturn(4);   // his 4 applications

        assertThat(service.verify(token).isEmailVerified()).isTrue();
        verify(candidates).claimByEmail("prabal@test.dev", prabal.getId());
    }

    @Test
    void aSecondClickChangesNothing() {
        String token = tokenIn(send(prabal));
        service.verify(token);
        service.verify(token);   // no error, no second claim

        verify(candidates, times(1)).claimByEmail(anyString(), any());
    }

    @Test
    void expiredAndUnknownLinksAreRefused() {
        String hash = TokenService.sha256("link-from-last-week");
        table.put(hash, new EmailVerification(prabal.getId(), hash, Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> service.verify("link-from-last-week")).hasMessageContaining("expired");
        assertThatThrownBy(() -> service.verify("never-sent")).hasMessageContaining("not valid");
        assertThat(prabal.isEmailVerified()).isFalse();
        verify(candidates, never()).claimByEmail(anyString(), any());
    }

    @Test
    void aRecruiterVerifyingClaimsNothing() {
        UserAccount recruiter = new UserAccount("hr@test.dev", "hash", Role.RECRUITER);
        when(users.findById(recruiter.getId())).thenReturn(Optional.of(recruiter));

        service.verify(tokenIn(send(recruiter)));

        verify(candidates, never()).claimByEmail(anyString(), any());
    }

    @Test
    void aMailServerThatIsDownDoesNotBreakSignUp() {
        doThrow(new MailSendException("connection refused: mailpit:1025")).when(mailer).send(any(SimpleMailMessage.class));

        assertThatCode(() -> service.sendLink(prabal)).doesNotThrowAnyException();
        assertThat(table).hasSize(1);   // the link exists; "resend" can mail a new one
    }

    @Test
    void logLinesNeverCarryAFullEmail() {
        assertThat(Masking.email("prabal.singh@gmail.com")).isEqualTo("p***@gmail.com");
        assertThat(Masking.email(null)).isEqualTo("***");
        assertThat(Masking.email("@no-user")).isEqualTo("***");
    }

    @Test
    void theLinkLastsADay() {
        assertThat(EmailVerificationService.LINK_LIFETIME).isEqualTo(Duration.ofHours(24));
    }
}
