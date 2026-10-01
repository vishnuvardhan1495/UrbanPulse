package com.urbanpulse.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PhoneNumberUtilTest {

    @Test
    @DisplayName("Valid Indian mobile inputs normalize to canonical +91 XXXXXXXXXX format")
    void testValidIndianPhoneNumbers() {
        String expected = "+91 9966843779";

        assertEquals(expected, PhoneNumberUtil.normalize("9966843779"));
        assertEquals(expected, PhoneNumberUtil.normalize("+919966843779"));
        assertEquals(expected, PhoneNumberUtil.normalize("+91 9966843779"));
        assertEquals(expected, PhoneNumberUtil.normalize("09966843779"));
    }

    @Test
    @DisplayName("Invalid, malformed, non-Indian, null, or blank inputs throw IllegalArgumentException")
    void testInvalidPhoneNumbersThrowException() {
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize("   "));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize("1234567890"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize("123"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize("abcdefghij"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize("+14155552671"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize("+91 1234567890"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize("+91 12345"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize("+919123456789012"));
        assertThrows(IllegalArgumentException.class, () -> PhoneNumberUtil.normalize("abc9966843779"));
    }
}
