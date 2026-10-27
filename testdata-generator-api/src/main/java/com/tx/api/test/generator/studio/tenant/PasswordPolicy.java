package com.tx.api.test.generator.studio.tenant;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Local Studio passwords. Directory accounts keep the company password and skip these rules.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;
    public static final int DEFAULT_MAX_AGE_DAYS = 90;
    public static final int DEFAULT_HISTORY = 5;
    public static final String RULE =
            "Password must be 12 to 128 characters and include a letter and a number";

    private PasswordPolicy() {
    }

    public static String problem(String password, String email) {
        if (password == null || password.isBlank()) {
            return RULE;
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            return RULE;
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < password.length(); i++) {
            char ch = password.charAt(i);
            if (Character.isLetter(ch)) {
                letter = true;
            } else if (Character.isDigit(ch)) {
                digit = true;
            }
        }
        if (!letter || !digit) {
            return RULE;
        }
        if (email != null && password.equalsIgnoreCase(email.trim())) {
            return "Password must not be the same as the email address";
        }
        return null;
    }

    public static void requireAcceptable(String password, String email) {
        String problem = problem(password, email);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
    }

    public static boolean changeRequired(Account account, int maxAgeDays, Instant now) {
        if (account == null || LdapAccounts.isDirectoryManaged(account.getPasswordHash())) {
            return false;
        }
        if (account.isMustChangePassword()) {
            return true;
        }
        if (maxAgeDays <= 0) {
            return false;
        }
        Instant changed = account.getPasswordChangedAt() != null
                ? account.getPasswordChangedAt()
                : account.getCreatedAt();
        if (changed == null || now == null) {
            return false;
        }
        return !changed.plus(maxAgeDays, ChronoUnit.DAYS).isAfter(now);
    }

    public static boolean reuses(String candidate, List<String> hashes) {
        if (candidate == null || hashes == null) {
            return false;
        }
        for (String hash : hashes) {
            if (hash != null && !LdapAccounts.isDirectoryManaged(hash) && PasswordHasher.matches(candidate, hash)) {
                return true;
            }
        }
        return false;
    }
}
