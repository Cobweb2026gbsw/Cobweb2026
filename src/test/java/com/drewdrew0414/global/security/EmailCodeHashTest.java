package com.drewdrew0414.global.security;

import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmailCodeHashTest {
    private final EmailCodeHash hashes = new EmailCodeHash("a-unique-server-secret-for-this-test-1234567890");

    @Test void codeIsBoundToEmailAndPurposeWithoutStoringEightDigits() {
        String digest = hashes.hash("User@example.com", VerificationPurpose.JOIN, "01234567");
        assertEquals(64, digest.length());
        assertNotEquals("01234567", digest);
        assertTrue(hashes.matches(digest, "user@example.com", VerificationPurpose.JOIN, "01234567"));
        assertFalse(hashes.matches(digest, "other@example.com", VerificationPurpose.JOIN, "01234567"));
        assertFalse(hashes.matches(digest, "user@example.com", VerificationPurpose.PASSWORD_RESET, "01234567"));
        assertFalse(hashes.matches(digest, "user@example.com", VerificationPurpose.JOIN, "01234568"));
    }
}
