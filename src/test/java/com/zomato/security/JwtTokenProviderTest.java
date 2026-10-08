package com.zomato.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider();
        ReflectionTestUtils.setField(jwtTokenProvider, "secret", "zomatoCloneSecretKeyForJWTTokenGenerationAndValidation2024MustBe256BitsLong");
        ReflectionTestUtils.setField(jwtTokenProvider, "expiration", 3600000L); // 1 hour
    }

    @Test
    @DisplayName("Generate and validate JWT token successfully")
    void testGenerateAndValidateToken() {
        UserDetails userDetails = new User("test@example.com", "password", Collections.emptyList());

        String token = jwtTokenProvider.generateToken(userDetails);
        assertNotNull(token);
        assertFalse(token.isEmpty());

        String username = jwtTokenProvider.getUsernameFromToken(token);
        assertEquals("test@example.com", username);

        boolean isValid = jwtTokenProvider.validateToken(token, userDetails);
        assertTrue(isValid);
    }
}
