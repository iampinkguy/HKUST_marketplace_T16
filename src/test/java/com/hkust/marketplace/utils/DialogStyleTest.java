package com.hkust.marketplace.utils;

import org.junit.Test;

import static org.junit.Assert.*;

public class DialogStyleTest {

    @Test
    public void internalUiErrorsAreDetectedAndHiddenFromUsers() {
        IllegalStateException pulse = new IllegalStateException(
                "showAndWait is not allowed during animation or layout processing");
        assertTrue(DialogStyle.isInternalUiError(pulse));
        assertTrue(DialogStyle.isInternalUiError(
                new IllegalStateException("Not on FX application thread")));
        assertFalse(DialogStyle.isInternalUiError(new IllegalStateException("Listing changed.")));
        assertFalse(DialogStyle.isInternalUiError(null));
        assertFalse(DialogStyle.isInternalUiError(new IllegalStateException("")));

        assertNull(DialogStyle.userFacing(pulse, null));
        assertEquals("Please try again.", DialogStyle.userFacing(pulse, "Please try again."));
        assertEquals("Listing changed.",
                DialogStyle.userFacing(new IllegalStateException("Listing changed."), "fallback"));
        assertEquals("Something went wrong. Please try again.", DialogStyle.userFacing((Throwable) null, null));
        assertEquals("fallback", DialogStyle.userFacing((String) null, "fallback"));
        assertEquals("fallback", DialogStyle.userFacing("   ", "fallback"));
        assertEquals("Keep this business message",
                DialogStyle.userFacing("Keep this business message", "fallback"));
    }
}
