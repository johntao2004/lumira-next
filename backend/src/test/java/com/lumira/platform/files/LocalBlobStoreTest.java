package com.lumira.platform.files;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalBlobStoreTest {
    @Test
    void stagedBytesAreInvisibleUntilPublishedAndCloseRetainsPublishedBytes() throws Exception {
        var store = new LocalBlobStore(root);
        UUID key = UUID.randomUUID();
        try (var staged = store.stage(key, new ByteArrayInputStream(new byte[] {3}), 1)) {
            assertThrows(NoSuchFileException.class, () -> store.open(key));
            assertEquals(1, staged.metadata().size());
            staged.publish();
            assertThrows(IllegalStateException.class, staged::publish);
        }
        try (var input = store.open(key)) {
            assertArrayEquals(new byte[] {3}, input.readAllBytes());
        }
        assertEquals(1, files());
    }

    @Test
    void discardedStageCannotPublishAndDoesNotRemoveALaterStage() throws Exception {
        var store = new LocalBlobStore(root);
        UUID key = UUID.randomUUID();
        var first = store.stage(key, new ByteArrayInputStream(new byte[] {1}), 1);
        first.close();
        assertEquals(0, files());
        assertThrows(IllegalStateException.class, first::publish);
        try (var second = store.stage(key, new ByteArrayInputStream(new byte[] {2}), 1)) {
            first.close();
            second.publish();
        }
        try (var input = store.open(key)) {
            assertArrayEquals(new byte[] {2}, input.readAllBytes());
        }
    }

    @Test
    void cleanupRemovesStagingAndPreventsLatePublication() throws Exception {
        var store = new LocalBlobStore(root);
        UUID key = UUID.randomUUID();
        try (var stage = store.stage(key, new ByteArrayInputStream(new byte[] {1}), 1)) {
            store.delete(key);
            assertEquals(0, files());
            assertThrows(NoSuchFileException.class, stage::publish);
        }
    }

    @Test
    void duplicateStageDoesNotDeleteFirstWritersTemporaryFile() throws Exception {
        var store = new LocalBlobStore(root);
        UUID key = UUID.randomUUID();
        try (var stage = store.stage(key, new ByteArrayInputStream(new byte[] {1}), 1)) {
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> store.stage(key, new ByteArrayInputStream(new byte[] {2}), 1));
            stage.publish();
        }
        try (var input = store.open(key)) {
            assertArrayEquals(new byte[] {1}, input.readAllBytes());
        }
    }

    @Test
    void reservedKeyCannotReplacePublishedContent() throws Exception {
        var store = new LocalBlobStore(root);
        UUID key = UUID.randomUUID();
        assertEquals(key, store.put(key, new ByteArrayInputStream(new byte[] {1}), 1).key());
        assertThrows(
                FileAlreadyExistsException.class,
                () -> store.put(key, new ByteArrayInputStream(new byte[] {2}), 1));
        try (var input = store.open(key)) {
            assertArrayEquals(new byte[] {1}, input.readAllBytes());
        }
        assertEquals(1, files());
    }

    @TempDir Path root;

    private long files() throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void roundtripDigestAndIdempotentDelete() throws Exception {
        var store = new LocalBlobStore(root);
        var saved =
                store.put(
                        new ByteArrayInputStream(
                                "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        5);
        assertEquals(5, saved.size());
        assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", saved.sha256());
        try (var input = store.open(saved.key())) {
            assertEquals(
                    "hello",
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(1, files());
        store.delete(saved.key());
        store.delete(saved.key());
        assertEquals(0, files());
        assertThrows(NoSuchFileException.class, () -> store.open(saved.key()));
    }

    @Test
    void oversizedInputConsumesOnlyOneExtraByteAndLeavesNoFile() throws Exception {
        var input = new ByteArrayInputStream(new byte[1024 * 1024]);
        var store = new LocalBlobStore(root);
        assertThrows(BlobStore.TooLarge.class, () -> store.put(input, 10));
        assertEquals(1024 * 1024 - 11, input.available());
        assertEquals(0, files());
    }

    @Test
    void brokenStreamLeavesNoPublishedOrTemporaryFile() throws Exception {
        var store = new LocalBlobStore(root);
        var input =
                new InputStream() {
                    public int read() throws IOException {
                        throw new IOException("broken");
                    }
                };
        assertThrows(IOException.class, () -> store.put(input, 1024));
        assertEquals(0, files());
    }

    @Test
    void refusesSymbolicLinkFilesAndShards() throws Exception {
        var store = new LocalBlobStore(root);
        UUID key = UUID.randomUUID();
        Path shard = root.resolve(key.toString().substring(0, 2));
        Files.createDirectory(shard);
        Path outside = Files.createTempFile("blob-outside-", ".tmp");
        try {
            Path link = shard.resolve(key + ".blob");
            Files.createSymbolicLink(link, outside);
            assertThrows(IOException.class, () -> store.open(key));
            assertThrows(IOException.class, () -> store.delete(key));
            Files.delete(link);
            Files.delete(shard);
            Files.createSymbolicLink(shard, outside.getParent());
            assertThrows(IOException.class, () -> store.open(key));
            assertTrue(Files.exists(outside));
        } finally {
            Files.deleteIfExists(shard);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void emptyAndInvalidLimits() throws Exception {
        var store = new LocalBlobStore(root);
        assertEquals(0, store.put(new ByteArrayInputStream(new byte[0]), 0).size());
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put(new ByteArrayInputStream(new byte[0]), -1));
    }
}
