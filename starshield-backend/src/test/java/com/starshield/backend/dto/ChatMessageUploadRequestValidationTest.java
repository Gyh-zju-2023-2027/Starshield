package com.starshield.backend.dto;

import com.starshield.backend.entity.ChatPlatform;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMessageUploadRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validRequest_passes() {
        ChatMessageUploadRequest req = new ChatMessageUploadRequest();
        req.setPlayerId("player-1");
        req.setContent("hello");
        req.setPlatform(ChatPlatform.OTHER);
        assertTrue(validator.validate(req).isEmpty());
    }

    @Test
    void missingFields_fail() {
        ChatMessageUploadRequest req = new ChatMessageUploadRequest();
        Set<ConstraintViolation<ChatMessageUploadRequest>> violations = validator.validate(req);
        assertFalse(violations.isEmpty());
    }

    @Test
    void blankPlayerId_fail() {
        ChatMessageUploadRequest req = new ChatMessageUploadRequest();
        req.setPlayerId("");
        req.setContent("hello");
        req.setPlatform(ChatPlatform.OTHER);
        assertFalse(validator.validate(req).isEmpty());
    }
}
