package com.datagraph.bank.common.validation;

import java.util.regex.Pattern;

/**
 * Guards human-readable metadata against lossy character conversion.
 */
public final class MetadataTextValidator {
    private static final Pattern REPEATED_QUESTION_MARKS = Pattern.compile("[?]{2,}");

    private MetadataTextValidator() {
    }

    public static String requireClean(String value, String fieldName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        if (!isClean(normalized)) {
            throw new IllegalArgumentException(
                    fieldName + " contains invalid replacement characters; verify that the request uses UTF-8");
        }
        return normalized;
    }

    public static boolean isClean(String value) {
        return value != null
                && value.indexOf('\uFFFD') < 0
                && !REPEATED_QUESTION_MARKS.matcher(value).find();
    }
}
