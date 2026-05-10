package com.misu.fileServer.util;

import com.misu.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FilePathGuardTest {

    @Test
    void normalizeRelativePath_strips_leading_slashes_and_normalizes() {
        assertEquals("a/b/c", FilePathGuard.normalizeRelativePath("/a/b/c"));
        assertEquals("a/b/c", FilePathGuard.normalizeRelativePath("a/b/c"));
        assertEquals("a/c", FilePathGuard.normalizeRelativePath("a/b/../c"));
    }

    @Test
    void normalizeRelativePath_rejects_traversal() {
        assertThrows(ServiceException.class, () -> FilePathGuard.normalizeRelativePath("../etc/passwd"));
        assertThrows(ServiceException.class, () -> FilePathGuard.normalizeRelativePath("a/../../etc/passwd"));
    }

    @Test
    void normalizeRelativePath_rejects_null_byte() {
        String pathWithNullByte = "a/b" + ((char) 0) + ".jpg";
        assertThrows(ServiceException.class, () -> FilePathGuard.normalizeRelativePath(pathWithNullByte));
    }

    @Test
    void normalizeRelativePath_converts_backslashes_to_forward_slashes() {
        assertEquals("a/b/c", FilePathGuard.normalizeRelativePath("a\\b\\c"));
    }

    @Test
    void normalizeRelativePath_blank_with_allowRoot_returns_empty() {
        assertEquals("", FilePathGuard.normalizeRelativePath("/", true));
        assertEquals("", FilePathGuard.normalizeRelativePath("", true));
    }

    @Test
    void normalizeRelativePath_blank_without_allowRoot_throws() {
        assertThrows(ServiceException.class, () -> FilePathGuard.normalizeRelativePath(""));
        assertThrows(ServiceException.class, () -> FilePathGuard.normalizeRelativePath("/"));
    }

    @Test
    void normalizeFileName_basic() {
        assertEquals("hello.txt", FilePathGuard.normalizeFileName("  hello.txt  "));
    }

    @Test
    void normalizeFileName_rejects_separators() {
        assertThrows(ServiceException.class, () -> FilePathGuard.normalizeFileName("a/b"));
        assertThrows(ServiceException.class, () -> FilePathGuard.normalizeFileName("a\\b"));
        assertThrows(ServiceException.class, () -> FilePathGuard.normalizeFileName("."));
        assertThrows(ServiceException.class, () -> FilePathGuard.normalizeFileName(".."));
    }
}
