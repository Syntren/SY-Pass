package com.syntren.sypass.util;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PasswordGenerator {
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SYMBOLS = "!@#$%^&*()-_=+";
    private static final String ALL_CHARS = UPPER + LOWER + DIGITS + SYMBOLS;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generate(int length) {
        int targetLength = Math.max(6, Math.min(64, length));

        List<Character> charList = new ArrayList<>();
        charList.add(UPPER.charAt(RANDOM.nextInt(UPPER.length())));
        charList.add(LOWER.charAt(RANDOM.nextInt(LOWER.length())));
        charList.add(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        charList.add(SYMBOLS.charAt(RANDOM.nextInt(SYMBOLS.length())));

        for (int i = 4; i < targetLength; i++) {
            charList.add(ALL_CHARS.charAt(RANDOM.nextInt(ALL_CHARS.length())));
        }

        Collections.shuffle(charList, RANDOM);

        StringBuilder sb = new StringBuilder(charList.size());
        for (char c : charList) {
            sb.append(c);
        }
        return sb.toString();
    }

    public static String generateDefault() {
        return generate(16);
    }
}
