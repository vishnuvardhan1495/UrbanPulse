package com.urbanpulse.util;

import java.util.regex.Pattern;

/**
 * Utility for validating and normalizing Indian mobile numbers.
 * Converts valid Indian phone numbers to canonical PostgreSQL display/storage format: "+91 XXXXXXXXXX".
 */
public class PhoneNumberUtil {

    private static final Pattern INDIAN_MOBILE_PATTERN = Pattern.compile("^[6-9]\\d{9}$");

    /**
     * Validates and normalizes an Indian mobile number into canonical PostgreSQL format: "+91 XXXXXXXXXX"
     *
     * @param rawPhone the raw phone number string
     * @return canonical format: "+91 XXXXXXXXXX"
     * @throws IllegalArgumentException if input is null, blank, malformed, or not a valid Indian mobile number
     */
    public static String normalize(String rawPhone) {
        if (rawPhone == null || rawPhone.trim().isEmpty()) {
            throw new IllegalArgumentException("Phone number cannot be null or empty");
        }

        String cleaned = rawPhone.trim();

        // Check for disallowed characters (alphabetic letters)
        if (cleaned.matches(".*[a-zA-Z].*")) {
            throw new IllegalArgumentException("Invalid phone number containing alphabetic characters: " + rawPhone);
        }

        // Strip spaces, hyphens, and parentheses
        cleaned = cleaned.replaceAll("[\\s\\-()]", "");

        // Remove international / trunk prefix if present
        if (cleaned.startsWith("+91")) {
            cleaned = cleaned.substring(3);
        } else if (cleaned.startsWith("91") && cleaned.length() == 12) {
            cleaned = cleaned.substring(2);
        } else if (cleaned.startsWith("0") && cleaned.length() == 11) {
            cleaned = cleaned.substring(1);
        } else if (cleaned.startsWith("+")) {
            // Non-Indian international prefix
            throw new IllegalArgumentException("Unsupported non-Indian phone number: " + rawPhone);
        }

        // Validate that remainder is exactly 10 digits starting with 6, 7, 8, or 9
        if (!INDIAN_MOBILE_PATTERN.matcher(cleaned).matches()) {
            throw new IllegalArgumentException("Invalid Indian mobile number: " + rawPhone);
        }

        return "+91 " + cleaned;
    }
}
