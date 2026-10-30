package com.ats.candidate;

import com.ats.api.ResumeService;
import com.ats.auth.RefreshTokenRepository;
import com.ats.auth.Role;
import com.ats.auth.SecurityConfig;
import com.ats.auth.TokenService;
import com.ats.auth.UserAccount;
import com.ats.auth.UserAccountRepository;
import com.ats.domain.MasterProfileRepository;
import com.ats.domain.ProfileResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The profile endpoints through the real security filter chain: the account always comes from the token. */
@WebMvcTest(controllers = ProfileController.class,
        properties = {"app.auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long",
                "app.auth.access-token-ttl=15m", "app.auth.refresh-token-ttl=7d"})
@Import({SecurityConfig.class, TokenService.class})
class ProfileControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TokenService tokens;

    @MockitoBean
    private ProfileResumeService profileResumes;
    @MockitoBean
    private ProfileResumeRepository resumes;
    @MockitoBean
    private MasterProfileRepository profiles;
    @MockitoBean
    private UserAccountRepository users;
    @MockitoBean
    private RefreshTokenRepository refreshTokens;

    private final UserAccount sneha = new UserAccount("sneha@test.dev", "hash", Role.CANDIDATE);
    private final UserAccount recruiter = new UserAccount("recruiter@ats.local", "hash", Role.RECRUITER);

    private String bearer(UserAccount user) {
        return "Bearer " + tokens.issue(user).accessToken();
    }

    @Test
    void theUploadLinkIsForTheSignedInCandidatesOwnProfile() throws Exception {
        String key = "profiles/" + sneha.getId() + "/v1/resume.pdf";
        when(profileResumes.createUploadUrl(sneha.getId(), "cv.pdf"))   // her id — taken from her token
                .thenReturn(new ResumeService.UploadUrl("http://localhost:9000/put", key, 1, "application/pdf", 600));

        mvc.perform(post("/api/candidate/profile/resume/upload-url").header("Authorization", bearer(sneha))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"filename\":\"cv.pdf\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void aNewAccountHasAnEmptyProfile() throws Exception {
        mvc.perform(get("/api/candidate/profile").header("Authorization", bearer(sneha)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestResume").value(nullValue()))
                .andExpect(jsonPath("$.profile").value(nullValue()))
                .andExpect(jsonPath("$.embedded").value(false));
    }

    @Test
    void recruitersAndAnonymousCallersCannotUseIt() throws Exception {
        mvc.perform(get("/api/candidate/profile").header("Authorization", bearer(recruiter)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/candidate/profile/resumes")).andExpect(status().isUnauthorized());
    }
}
