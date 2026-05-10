package com.misu.fileServer.util;

import com.misu.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadExtensionGuardTest {

    private final UploadExtensionGuard guard = new UploadExtensionGuard("exe,bat,sh,html");

    @Test
    void rejects_blocked_extension_case_insensitive() {
        assertThrows(ServiceException.class, () -> guard.requireSafeForUpload("malware.EXE"));
        assertThrows(ServiceException.class, () -> guard.requireSafeForUpload("script.sh"));
        assertThrows(ServiceException.class, () -> guard.requireSafeForUpload("page.HTML"));
    }

    @Test
    void allows_safe_extensions() {
        assertDoesNotThrow(() -> guard.requireSafeForUpload("photo.jpg"));
        assertDoesNotThrow(() -> guard.requireSafeForUpload("doc.pdf"));
        assertDoesNotThrow(() -> guard.requireSafeForUpload("plain.txt"));
        assertDoesNotThrow(() -> guard.requireSafeForUpload("no-extension"));
    }

    @Test
    void isBlocked_reports_correctly() {
        assertTrue(guard.isBlocked("a.exe"));
        assertFalse(guard.isBlocked("a.png"));
    }

    @Test
    void supports_dot_prefix_in_config() {
        UploadExtensionGuard withDots = new UploadExtensionGuard(".exe, .sh ");
        assertTrue(withDots.isBlocked("foo.exe"));
        assertTrue(withDots.isBlocked("bar.sh"));
        assertFalse(withDots.isBlocked("baz.png"));
    }

    @Test
    void empty_blocklist_blocks_nothing() {
        UploadExtensionGuard empty = new UploadExtensionGuard("");
        assertDoesNotThrow(() -> empty.requireSafeForUpload("x.exe"));
        assertFalse(empty.isBlocked("x.exe"));
    }
}
