package com.syntren.sypass.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PasswordGeneratorTest {

    @Test
    @DisplayName("Generated default password has length 16 and all character classes")
    void testGenerateDefault() {
        String password = PasswordGenerator.generateDefault();
        assertNotNull(password);
        assertEquals(16, password.length());

        assertTrue(password.chars().anyMatch(Character::isUpperCase), "Must contain uppercase letter");
        assertTrue(password.chars().anyMatch(Character::isLowerCase), "Must contain lowercase letter");
        assertTrue(password.chars().anyMatch(Character::isDigit), "Must contain digit");
        assertTrue(password.chars().anyMatch(ch -> "!@#$%^&*()-_=+".indexOf(ch) >= 0), "Must contain special symbol");
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 10, 24, 32, 64})
    @DisplayName("Password length conforms to requested length within bounds")
    void testCustomLengths(int length) {
        String password = PasswordGenerator.generate(length);
        assertEquals(length, password.length());
    }

    @Test
    @DisplayName("Password length clamps under min (6) and over max (64)")
    void testLengthClamping() {
        assertEquals(6, PasswordGenerator.generate(2).length(), "Clamps to minimum 6");
        assertEquals(64, PasswordGenerator.generate(100).length(), "Clamps to maximum 64");
    }

    @Test
    @DisplayName("Generated passwords have high entropy and do not duplicate across 100 runs")
    void testUniqueness() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            assertTrue(generated.add(PasswordGenerator.generate(16)), "Generated password should be unique");
        }
    }
}
