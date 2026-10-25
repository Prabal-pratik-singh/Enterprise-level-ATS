package com.ats.auth;

import java.util.Optional;

import com.ats.domain.CandidateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Sign-up and sign-in rules. BCrypt strength 4 keeps the test fast; production uses the default 10. */
class AccountServiceTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final CandidateRepository candidates = mock(CandidateRepository.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private final AccountService accounts = new AccountService(users, passwords, candidates);

    @Test
    void emailsAreStoredInOneSpelling() {
        assertThat(AccountService.normalizeEmail("  Asha@Example.COM ")).isEqualTo("asha@example.com");
        assertThat(AccountService.emailProblem("asha@example.com")).isNull();
        assertThat(AccountService.emailProblem("asha")).isNotNull();
        assertThat(AccountService.emailProblem("asha@example")).isNotNull();
        assertThat(AccountService.emailProblem("asha k@example.com")).isNotNull();
        // bulk-upload placeholders can never become accounts (Mailpit would accept their mail)
        assertThat(AccountService.emailProblem("pending+1a2b3c4d@intake.invalid")).contains("cannot receive");
    }

    @Test
    void aVerifiedCandidateReclaimsAtSignInSoLaterApplicationsShowUp() {
        UserAccount prabal = new UserAccount("prabal@test.dev", passwords.encode("Passw0rd!x"), Role.CANDIDATE);
        prabal.markEmailVerified();
        when(users.findByEmail("prabal@test.dev")).thenReturn(Optional.of(prabal));

        accounts.logIn("prabal@test.dev", "Passw0rd!x");

        verify(candidates).claimByEmail("prabal@test.dev", prabal.getId());
    }

    @Test
    void anUnverifiedSignInClaimsNothing() {
        UserAccount stranger = new UserAccount("prabal@test.dev", passwords.encode("Passw0rd!x"), Role.CANDIDATE);
        when(users.findByEmail("prabal@test.dev")).thenReturn(Optional.of(stranger));   // typed his e-mail, never verified

        accounts.logIn("prabal@test.dev", "Passw0rd!x");

        verify(candidates, never()).claimByEmail(anyString(), any());
    }

    @Test
    void passwordsNeedTenCharactersAndMustFitBcrypt() {
        assertThat(AccountService.passwordProblem("short")).contains("at least 10");
        assertThat(AccountService.passwordProblem("Passw0rd!x")).isNull();
        assertThat(AccountService.passwordProblem("é".repeat(37))).contains("too long");   // 37 characters, 74 bytes
    }

    @Test
    void everyoneWhoSignsUpIsACandidate() {
        when(users.saveAndFlush(any(UserAccount.class))).thenAnswer(call -> call.getArgument(0));

        UserAccount created = accounts.signUp(" A@Test.dev ", "Passw0rd!x");

        assertThat(created.getRole()).isEqualTo(Role.CANDIDATE);
        assertThat(created.getEmail()).isEqualTo("a@test.dev");
        assertThat(created.isEmailVerified()).isFalse();
        assertThat(created.getPasswordHash()).isNotEqualTo("Passw0rd!x").startsWith("$2a$");
    }

    @Test
    void aSecondAccountWithTheSameEmailIsAConflict() {
        when(users.existsByEmail("a@test.dev")).thenReturn(true);

        assertThatThrownBy(() -> accounts.signUp("A@test.dev", "Passw0rd!x"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void wrongPasswordAndUnknownEmailGetTheSameAnswer() {
        UserAccount asha = new UserAccount("a@test.dev", passwords.encode("Passw0rd!x"), Role.CANDIDATE);
        when(users.findByEmail("a@test.dev")).thenReturn(Optional.of(asha));
        when(users.findByEmail("nobody@test.dev")).thenReturn(Optional.empty());

        assertThat(accounts.logIn("A@test.dev", "Passw0rd!x")).isSameAs(asha);
        assertThatThrownBy(() -> accounts.logIn("a@test.dev", "wrong-password"))
                .hasMessageContaining("wrong email or password");
        assertThatThrownBy(() -> accounts.logIn("nobody@test.dev", "Passw0rd!x"))
                .hasMessageContaining("wrong email or password");
    }
}
