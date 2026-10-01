package vn.codegym.salesinventory.security;

import java.security.SecureRandom;

public final class TemporaryPasswordGenerator {
    private static final char[] UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final char[] LOWER = "abcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] SYMBOLS = "!@#$%".toCharArray();
    private static final char[] ALL = (
            new String(UPPER) + new String(LOWER) + new String(DIGITS) + new String(SYMBOLS)).toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] password = new char[14];
        password[0] = pick(UPPER);
        password[1] = pick(LOWER);
        password[2] = pick(DIGITS);
        password[3] = pick(SYMBOLS);
        for (int index = 4; index < password.length; index++) {
            password[index] = pick(ALL);
        }
        for (int index = password.length - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            char value = password[index];
            password[index] = password[other];
            password[other] = value;
        }
        return new String(password);
    }

    private char pick(char[] characters) {
        return characters[random.nextInt(characters.length)];
    }
}
