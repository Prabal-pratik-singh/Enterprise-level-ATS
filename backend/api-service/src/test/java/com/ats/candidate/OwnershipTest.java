package com.ats.candidate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ats.auth.RefreshTokenRepository;
import com.ats.auth.Role;
import com.ats.auth.SecurityConfig;
import com.ats.auth.TokenService;
import com.ats.auth.UserAccount;
import com.ats.auth.UserAccountRepository;
import com.ats.domain.Application;
import com.ats.domain.ApplicationRepository;
import com.ats.domain.Candidate;
import com.ats.domain.CandidateRepository;
import com.ats.domain.Job;
import com.ats.domain.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The brief's isolation rule — candidate A cannot read candidate B's data — through the real security
 * filter chain with real signed tokens. Repositories are stand-ins (no database).
 */
@WebMvcTest(controllers = MyApplicationsController.class,
        properties = {"app.auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long",
                "app.auth.access-token-ttl=15m", "app.auth.refresh-token-ttl=7d"})
@Import({SecurityConfig.class, TokenService.class})
class OwnershipTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TokenService tokens;

    @MockitoBean
    private ApplicationRepository applications;
    @MockitoBean
    private CandidateRepository candidates;
    @MockitoBean
    private JobRepository jobs;
    @MockitoBean
    private UserAccountRepository users;
    @MockitoBean
    private RefreshTokenRepository refreshTokens;

    private final UserAccount asha = new UserAccount("asha@test.dev", "hash", Role.CANDIDATE);
    private final UserAccount ravi = new UserAccount("ravi@test.dev", "hash", Role.CANDIDATE);
    private final UserAccount recruiter = new UserAccount("recruiter@ats.local", "hash", Role.RECRUITER);
    private final Job backend = new Job("Backend Engineer — Java/Kafka", null, "{}");

    /** Ravi's application: its candidate row was claimed by his account (the claim sets user_id in SQL). */
    private Application ravisApplication() {
        Candidate row = new Candidate("Ravi Kumar", "ravi@test.dev", null);
        ReflectionTestUtils.setField(row, "userId", ravi.getId());
        Application app = new Application(backend.getId(), row.getId());
        when(applications.findById(app.getId())).thenReturn(Optional.of(app));
        when(candidates.findById(row.getId())).thenReturn(Optional.of(row));
        when(jobs.findById(backend.getId())).thenReturn(Optional.of(backend));
        return app;
    }

    private String bearer(UserAccount user) {
        return "Bearer " + tokens.issue(user).accessToken();   // a real signed token, as after a sign-in
    }

    @Test
    void candidateACannotReadCandidateBsApplication() throws Exception {
        Application app = ravisApplication();
        mvc.perform(get("/api/candidate/applications/" + app.getId()).header("Authorization", bearer(asha)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCandidateCanReadTheirOwnApplication() throws Exception {
        Application app = ravisApplication();
        mvc.perform(get("/api/candidate/applications/" + app.getId()).header("Authorization", bearer(ravi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobTitle").value("Backend Engineer — Java/Kafka"))
                .andExpect(jsonPath("$.status").value("APPLIED"));
    }

    @Test
    void anUnclaimedApplicationBelongsToNobody() throws Exception {
        Candidate row = new Candidate("Walk-in Applicant", "walkin@test.dev", null);   // never claimed: user_id NULL
        Application app = new Application(backend.getId(), row.getId());
        when(applications.findById(app.getId())).thenReturn(Optional.of(app));
        when(candidates.findById(row.getId())).thenReturn(Optional.of(row));
        mvc.perform(get("/api/candidate/applications/" + app.getId()).header("Authorization", bearer(asha)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownApplicationIsNotFound() throws Exception {
        mvc.perform(get("/api/candidate/applications/" + UUID.randomUUID()).header("Authorization", bearer(asha)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theListHoldsOnlyTheCallersApplications() throws Exception {
        Application app = ravisApplication();
        when(applications.findAllOwnedBy(ravi.getId())).thenReturn(List.of(app));
        when(jobs.findAllById(List.of(backend.getId()))).thenReturn(List.of(backend));
        mvc.perform(get("/api/candidate/applications").header("Authorization", bearer(ravi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].applicationId").value(app.getId().toString()));
        mvc.perform(get("/api/candidate/applications").header("Authorization", bearer(asha)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));   // Asha has claimed nothing
    }

    @Test
    void candidateEndpointsAreForCandidatesOnly() throws Exception {
        mvc.perform(get("/api/candidate/applications").header("Authorization", bearer(recruiter)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/candidate/applications")).andExpect(status().isUnauthorized());
    }
}
