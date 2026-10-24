package com.ats.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.ats.api.CandidateController;
import com.ats.api.CandidateQueryService;
import com.ats.api.JobController;
import com.ats.api.SkillController;
import com.ats.domain.Candidate;
import com.ats.domain.CandidateRepository;
import com.ats.domain.JobRepository;
import com.ats.taxonomy.SkillTaxonomy;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The access rules, through the real security filter chain with real signed tokens. There is no
 * database: repositories are stand-ins. GET /api/jobs stands for every recruiter endpoint —
 * SecurityConfig's last rule makes everything not listed recruiter-only.
 */
@WebMvcTest(controllers = {MeController.class, JobController.class, SkillController.class, CandidateController.class},
        properties = {"app.auth.jwt-secret=" + SecurityRulesTest.SECRET,
                "app.auth.access-token-ttl=15m", "app.auth.refresh-token-ttl=7d"})
@Import({SecurityConfig.class, TokenService.class})
class SecurityRulesTest {

    static final String SECRET = "test-secret-that-is-at-least-32-bytes-long";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TokenService tokens;
    @Autowired
    private JwtEncoder encoder;

    @MockitoBean
    private UserAccountRepository users;
    @MockitoBean
    private RefreshTokenRepository refreshTokens;
    @MockitoBean
    private JobRepository jobs;
    @MockitoBean
    private CandidateQueryService candidateQueries;
    @MockitoBean
    private CandidateRepository candidates;
    @MockitoBean
    private SkillTaxonomy taxonomy;

    private final UserAccount asha = new UserAccount("asha@test.dev", "hash", Role.CANDIDATE);
    private final UserAccount recruiter = new UserAccount("recruiter@ats.local", "hash", Role.RECRUITER);

    private String bearer(UserAccount user) {
        return "Bearer " + tokens.accessTokenFor(user);
    }

    @Test
    void noTokenMeansUnauthorized() throws Exception {
        mvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void aCandidateCannotCallRecruiterEndpoints() throws Exception {
        mvc.perform(get("/api/jobs").header("Authorization", bearer(asha)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRecruiterCanCallRecruiterEndpoints() throws Exception {
        when(jobs.findAll()).thenReturn(List.of());
        mvc.perform(get("/api/jobs").header("Authorization", bearer(recruiter)))
                .andExpect(status().isOk());
    }

    @Test
    void meIsAlwaysTheCallersOwnAccount() throws Exception {
        when(users.findById(asha.getId())).thenReturn(Optional.of(asha));
        mvc.perform(get("/api/me").header("Authorization", bearer(asha)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(asha.getId().toString()))
                .andExpect(jsonPath("$.email").value("asha@test.dev"))
                .andExpect(jsonPath("$.role").value("CANDIDATE"));
    }

    @Test
    void theSkillListAndTheApplyFlowNeedNoAccount() throws Exception {
        mvc.perform(get("/api/skills")).andExpect(status().isOk());
        when(candidates.save(any(Candidate.class))).thenAnswer(call -> call.getArgument(0));
        mvc.perform(post("/api/candidates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ravi Kumar\",\"email\":\"ravi@example.com\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() throws Exception {
        JwtEncoder forger = new NimbusJwtEncoder(new ImmutableSecret<>(
                SecurityConfig.signingKey("someone-elses-secret-also-32-bytes-long")));
        String forged = sign(forger, recruiter, Instant.now().plusSeconds(900));
        mvc.perform(get("/api/jobs").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        String expired = sign(encoder, recruiter, Instant.now().minusSeconds(300)); // beyond the 60 s clock allowance
        mvc.perform(get("/api/jobs").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    private static String sign(JwtEncoder with, UserAccount user, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(expiresAt.minusSeconds(900))
                .expiresAt(expiresAt)
                .claim("roles", List.of(user.getRole().name()))
                .build();
        return with.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
