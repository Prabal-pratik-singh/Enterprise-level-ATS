package com.ats.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ats.domain.CandidateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The 6-digit code: mailed, stored only as an HMAC, 10 minutes, 5 wrong tries, a new code ends the old. */
class EmailVerificationServiceTest {

    private final EmailVerificationRepository codes = mock(EmailVerificationRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final CandidateRepository candidates = mock(CandidateRepository.class);
    private final JavaMailSender mailer = mock(JavaMailSender.class);
    private final EmailVerificationService service = new EmailVerificationService(codes, users, candidates, mailer,
            new AuthProperties("test-secret-that-is-at-least-32-bytes-long", null, null, false, null, null,
                    "http://localhost:5174", "no-reply@ats.local"),
            new TransactionTemplate(mock(PlatformTransactionManager.class)));

    private final List<EmailVerification> table = new ArrayList<>();   // stands in for email_verifications
    private final UserAccount sneha = new UserAccount("sneha@test.dev", "hash", Role.CANDIDATE);

    @BeforeEach
    void fakeTheTable() {
        when(codes.save(any(EmailVerification.class))).thenAnswer(call -> {
            table.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(codes.findFirstByUserIdAndUsedAtIsNullOrderByExpiresAtDesc(any())).thenAnswer(call -> table.stream()
                .filter(v -> v.getUserId().equals(call.getArgument(0)) && v.getUsedAt() == null)
                .max(Comparator.comparing(EmailVerification::getExpiresAt)));
        when(users.findById(sneha.getId())).thenReturn(Optional.of(sneha));
    }

    /** Sends a code and returns it, the way the inbox would show it. */
    private String send(UserAccount user) {
        service.sendCode(user);
        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailer, atLeastOnce()).send(mail.capture());
        return mail.getValue().getText().replaceAll("(?s).*code is (\\d{6}).*", "$1");
    }

    private static String wrong(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    @Test
    void theCodeIsMailedAndOnlyItsHmacIsStored() {
        String code = send(sneha);

        assertThat(code).matches("\\d{6}");
        assertThat(table).hasSize(1);
        assertThat(table.get(0).getTokenHash()).isNotEqualTo(code).isEqualTo(service.hmac(code)).hasSize(64);
        assertThat(Duration.between(Instant.now(), table.get(0).getExpiresAt())).isBetween(
                Duration.ofMinutes(9), Duration.ofMinutes(10));
    }

    @Test
    void theRightCodeVerifiesAndClaimsHerEarlierApplications() {
        String code = send(sneha);

        assertThat(service.verifyCode(sneha.getId(), code).isEmailVerified()).isTrue();
        verify(candidates).claimByEmail("sneha@test.dev", sneha.getId());
    }

    @Test
    void wrongTriesCountDownAndTheFifthBurnsTheCode() {
        String code = send(sneha);

        assertThatThrownBy(() -> service.verifyCode(sneha.getId(), wrong(code))).hasMessageContaining("4 tries left");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.verifyCode(sneha.getId(), wrong(code)));
        }
        assertThatThrownBy(() -> service.verifyCode(sneha.getId(), wrong(code))).hasMessageContaining("too many wrong tries");
        // even the right code is refused now — it was burned
        assertThatThrownBy(() -> service.verifyCode(sneha.getId(), code)).hasMessageContaining("too many wrong tries");
        assertThat(sneha.isEmailVerified()).isFalse();
    }

    @Test
    void anExpiredCodeIsRefused() {
        String code = "123456";
        table.add(new EmailVerification(sneha.getId(), service.hmac(code), Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> service.verifyCode(sneha.getId(), code))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("expired");
        verify(candidates, never()).claimByEmail(anyString(), any());
    }

    @Test
    void aNewCodeEndsTheOldOne() {
        send(sneha);
        send(sneha);

        verify(codes, atLeastOnce()).expireAllFor(any(), any());   // the database ends every older unused code
    }

    @Test
    void oneAccountsCodeCannotVerifyAnother() {
        UserAccount ravi = new UserAccount("ravi@test.dev", "hash", Role.CANDIDATE);
        when(users.findById(ravi.getId())).thenReturn(Optional.of(ravi));
        String snehasCode = send(sneha);

        assertThatThrownBy(() -> service.verifyCode(ravi.getId(), snehasCode)).hasMessageContaining("no active code");
        assertThat(ravi.isEmailVerified()).isFalse();
    }

    @Test
    void aMailServerThatIsDownDoesNotBreakSignUp() {
        doThrow(new MailSendException("connection refused: mailpit:1025")).when(mailer).send(any(SimpleMailMessage.class));

        assertThatCode(() -> service.sendCode(sneha)).doesNotThrowAnyException();
        assertThat(table).hasSize(1);   // the code exists; "send a new code" can mail another
    }

    @Test
    void logLinesNeverCarryAFullEmail() {
        assertThat(Masking.email("prabal.singh@gmail.com")).isEqualTo("p***@gmail.com");
        assertThat(Masking.email(null)).isEqualTo("***");
    }
}
