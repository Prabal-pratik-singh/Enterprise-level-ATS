package com.ats.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Bulk-upload identities: a readable placeholder now, the resume's real details later. */
class CandidateTest {

    @Test
    void theFileNameBecomesAReadablePlaceholderName() {
        assertThat(Candidate.displayNameOf("cv_final-v3.pdf")).isEqualTo("cv final v3");
        assertThat(Candidate.displayNameOf("C:\\Users\\me\\Priya_Sharma_CV.docx")).isEqualTo("Priya Sharma CV");
        assertThat(Candidate.displayNameOf(".pdf")).isEqualTo(".pdf"); // a dot-file keeps its name
        assertThat(Candidate.displayNameOf("___.pdf")).isEqualTo("unnamed resume");
    }

    @Test
    void placeholderEmailsCanNeverBeRealAndNeverCollide() {
        Candidate a = Candidate.pendingFromFile("a.pdf");
        Candidate b = Candidate.pendingFromFile("a.pdf");
        assertThat(a.getEmail()).endsWith("@intake.invalid");
        assertThat(a.getEmail()).isNotEqualTo(b.getEmail()); // dedup must not see two files as one person
        assertThat(a.isIdentityPending()).isTrue();
    }

    @Test
    void theResumeFillsInWhatItHasAndKeepsThePlaceholderForTheRest() {
        Candidate c = Candidate.pendingFromFile("cv_final-v3.pdf");
        String placeholder = c.getEmail();
        c.adoptIdentityFromResume("Rohan Mehta", null, "+91-98765 43210"); // resume without an email
        assertThat(c.getFullName()).isEqualTo("Rohan Mehta");
        assertThat(c.getEmail()).isEqualTo(placeholder);
        assertThat(c.getPhone()).isEqualTo("+91-98765 43210");
        assertThat(c.isIdentityPending()).isFalse();
        assertThat(c.getIdentitySource()).isEqualTo("resume");
    }

    @Test
    void aTypedInCandidateIsNeverPending() {
        Candidate typed = new Candidate("Priya Iyer", "priya@gmail.com", null);
        assertThat(typed.isIdentityPending()).isFalse();
        assertThat(typed.getIdentitySource()).isEqualTo("form");
    }
}
