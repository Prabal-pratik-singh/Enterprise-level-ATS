package com.ats.api;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntakeControllerTest {

    // null collaborators on purpose: if validation let a bad file through, the null repository
    // would blow up with a NullPointerException instead of the expected 400
    private final IntakeController controller = new IntakeController(null, null, null);

    @Test
    void anUnsupportedFileIsRejectedBeforeAnyRowIsCreated() {
        assertThatThrownBy(() -> controller.intake(UUID.randomUUID(), new IntakeController.IntakeRequest("setup.exe")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only pdf, docx, png and jpg");
    }

    @Test
    void aFileNameIsRequired() {
        assertThatThrownBy(() -> controller.intake(UUID.randomUUID(), new IntakeController.IntakeRequest(" ")))
                .hasMessageContaining("filename is required");
        assertThatThrownBy(() -> controller.intake(UUID.randomUUID(), new IntakeController.IntakeRequest("resume")))
                .hasMessageContaining("filename with extension");
    }
}
