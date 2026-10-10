package com.lumira.platform.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Per-input preflight limits for Office Open XML archives, before a POI object is constructed. */
public final class OfficeArchiveBudget {
    public static final Limits DEFAULT_LIMITS =
            new Limits(
                    2048,
                    32L * 1024 * 1024,
                    96L * 1024 * 1024,
                    200,
                    32L * 1024 * 1024,
                    5_000_000,
                    128);

    public record Limits(
            int entries,
            long maxEntryBytes,
            long maxInflatedBytes,
            int maxCompressionRatio,
            long maxXmlBytes,
            long maxXmlNodes,
            int maxXmlDepth) {
        public Limits {
            if (entries < 1
                    || maxEntryBytes < 1
                    || maxInflatedBytes < maxEntryBytes
                    || maxCompressionRatio < 1
                    || maxXmlBytes < 1
                    || maxXmlNodes < 1
                    || maxXmlDepth < 1) {
                throw new IllegalArgumentException("Invalid Office archive budget");
            }
        }
    }

    /** Raised when measured archive work exceeds the configured per-input budget. */
    public static final class Exceeded extends IOException {
        public Exceeded(String message) {
            super(message);
        }
    }

    private OfficeArchiveBudget() {}

    private static final long PREFLIGHT_NANOS = java.time.Duration.ofSeconds(5).toNanos();

    public static void verify(byte[] bytes) throws IOException {
        verify(bytes, DEFAULT_LIMITS);
    }

    static void verify(byte[] bytes, Limits limits) throws IOException {
        if (bytes == null || bytes.length == 0) throw new IOException("Empty Office archive");
        long started = System.nanoTime();
        verifyCentralDirectoryEntryCount(bytes, limits.entries());
        Path archive = Files.createTempFile("lumira-office-preflight-", ".zip");
        try {
            Files.write(archive, bytes);
            checkDeadline(started);
            inspect(archive, limits, started);
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    private static void inspect(Path path, Limits limits, long started) throws IOException {
        long totalInflated = 0;
        long totalXmlBytes = 0;
        long totalXmlNodes = 0;
        Set<String> names = new HashSet<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entries = zip.entries();
            int entryCount = 0;
            while (entries.hasMoreElements()) {
                checkDeadline(started);
                ZipEntry entry = entries.nextElement();
                if (++entryCount > limits.entries()) throw new Exceeded("Too many Office parts");
                String name = entry.getName();
                if (name.length() > 4096 || !names.add(name))
                    throw new Exceeded("Invalid or duplicate Office part name");
                boolean xml = isXml(name);
                long entryBytes = 0;
                long entryNodes = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    CountingInputStream counted =
                            new CountingInputStream(
                                    input,
                                    limits.maxEntryBytes(),
                                    totalInflated,
                                    limits.maxInflatedBytes(),
                                    totalXmlBytes,
                                    xml ? limits.maxXmlBytes() : Long.MAX_VALUE,
                                    started);
                    if (xml) {
                        XMLStreamReader reader;
                        try {
                            reader =
                                    xmlFactory(limits.maxXmlDepth())
                                            .createXMLStreamReader(
                                                    new java.io.FilterInputStream(counted) {
                                                        @Override
                                                        public void close() {
                                                            // The caller drains the same counted
                                                            // ZIP stream after StAX closes.
                                                        }
                                                    });
                        } catch (XMLStreamException malformed) {
                            throw new IOException("Invalid Office XML structure", malformed);
                        }
                        try {
                            int depth = 0;
                            while (reader.hasNext()) {
                                counted.checkDeadline();
                                int event = reader.next();
                                entryNodes++;
                                if (entryNodes + totalXmlNodes > limits.maxXmlNodes())
                                    throw new Exceeded("Office XML node limit exceeded");
                                if (event == XMLStreamConstants.DTD) {
                                    throw new Exceeded("DTD is not allowed in Office XML");
                                }
                                if (event == XMLStreamConstants.START_ELEMENT) {
                                    depth++;
                                    if (depth > limits.maxXmlDepth())
                                        throw new Exceeded("Office XML nesting limit exceeded");
                                    entryNodes += reader.getAttributeCount();
                                    if (entryNodes + totalXmlNodes > limits.maxXmlNodes())
                                        throw new Exceeded("Office XML node limit exceeded");
                                } else if (event == XMLStreamConstants.END_ELEMENT) {
                                    depth--;
                                }
                            }
                            if (depth != 0) throw new IOException("Invalid Office XML structure");
                        } catch (XMLStreamException malformed) {
                            Throwable cause = malformed;
                            while (cause != null) {
                                if (cause instanceof Exceeded exceeded) throw exceeded;
                                cause = cause.getCause();
                            }
                            throw new IOException("Invalid Office XML structure", malformed);
                        } finally {
                            try {
                                reader.close();
                            } catch (XMLStreamException ignored) {
                            }
                        }
                        counted.drain();
                    } else {
                        counted.drain();
                    }
                    entryBytes = counted.count();
                }

                totalInflated += entryBytes;
                if (entryBytes > limits.maxEntryBytes()
                        || totalInflated > limits.maxInflatedBytes()) {
                    throw new Exceeded("Office inflated-size limit exceeded");
                }
                if (xml) {
                    totalXmlBytes += entryBytes;
                    totalXmlNodes += entryNodes;
                    if (entryBytes > limits.maxXmlBytes() || totalXmlBytes > limits.maxXmlBytes()) {
                        throw new Exceeded("Office XML byte limit exceeded");
                    }
                }
                long compressedBytes = entry.getCompressedSize();
                if (entryBytes > 1024 * 1024
                        && (compressedBytes <= 0
                                || (entryBytes - 1) / compressedBytes
                                        >= limits.maxCompressionRatio())) {
                    throw new Exceeded("Office compression-ratio limit exceeded");
                }
            }
        }
    }

    private static XMLInputFactory xmlFactory(int maximumDepth) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        set(factory, XMLInputFactory.SUPPORT_DTD, false);
        set(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        set(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        set(factory, XMLInputFactory.IS_COALESCING, false);
        // Xerces defaults to depth 100. Keep its streaming parser aligned just above our
        // per-input boundary so our own check returns the stable budget error at that boundary.
        set(
                factory,
                "http://www.oracle.com/xml/jaxp/properties/maxElementDepth",
                Long.toString((long) maximumDepth + 1));
        return factory;
    }

    private static void verifyCentralDirectoryEntryCount(byte[] bytes, int maximum)
            throws IOException {
        int first = Math.max(0, bytes.length - 22 - 65_535);
        int end = bytes.length - 22;
        int eocd = -1;
        for (int offset = end; offset >= first; offset--) {
            if (u32(bytes, offset) == 0x06054b50L
                    && offset + 22L + u16(bytes, offset + 20) == bytes.length) {
                eocd = offset;
                break;
            }
        }
        if (eocd < 0) throw new IOException("Invalid Office ZIP end record");
        long entries = u16(bytes, eocd + 10);
        long centralDirectorySize = u32(bytes, eocd + 12);
        long centralDirectoryOffset = u32(bytes, eocd + 16);
        if (entries != 0xffffL) {
            if (entries > maximum) throw new Exceeded("Too many Office parts");
        }
        if (entries == 0xffffL
                || centralDirectorySize == 0xffffffffL
                || centralDirectoryOffset == 0xffffffffL) {
            int locator = eocd - 20;
            if (locator < 0 || u32(bytes, locator) != 0x07064b50L)
                throw new IOException("Missing Office ZIP64 locator");
            long zip64Offset = u64(bytes, locator + 8);
            if (zip64Offset < 0
                    || zip64Offset > bytes.length - 56L
                    || u32(bytes, (int) zip64Offset) != 0x06064b50L) {
                throw new IOException("Invalid Office ZIP64 directory");
            }
            long zip64Entries = u64(bytes, (int) zip64Offset + 32);
            if (zip64Entries < 0 || zip64Entries > maximum)
                throw new Exceeded("Too many Office parts");
            centralDirectorySize = u64(bytes, (int) zip64Offset + 40);
            centralDirectoryOffset = u64(bytes, (int) zip64Offset + 48);
        }
        scanCentralDirectory(bytes, eocd, centralDirectoryOffset, centralDirectorySize, maximum);
    }

    private static void scanCentralDirectory(
            byte[] bytes, int eocd, long offset, long size, int maximum) throws IOException {
        if (offset < 0
                || size < 0
                || offset > eocd
                || size > eocd - offset
                || offset > Integer.MAX_VALUE) {
            throw new IOException("Invalid Office ZIP central directory");
        }
        long cursor = offset;
        long end = offset + size;
        int entries = 0;
        while (cursor < end) {
            if (cursor > Integer.MAX_VALUE - 46L || u32(bytes, (int) cursor) != 0x02014b50L) {
                throw new IOException("Invalid Office ZIP central directory");
            }
            int header = (int) cursor;
            long recordBytes =
                    46L
                            + u16(bytes, header + 28)
                            + u16(bytes, header + 30)
                            + u16(bytes, header + 32);
            if (recordBytes > end - cursor)
                throw new IOException("Invalid Office ZIP directory entry");
            if (++entries > maximum) throw new Exceeded("Too many Office parts");
            cursor += recordBytes;
        }
        if (cursor != end) throw new IOException("Invalid Office ZIP central directory");
    }

    private static int u16(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 2 > bytes.length) throw new IOException("Invalid Office ZIP");
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 4 > bytes.length) throw new IOException("Invalid Office ZIP");
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static long u64(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 8 > bytes.length) throw new IOException("Invalid Office ZIP");
        long value = 0;
        for (int index = 7; index >= 0; index--)
            value = (value << 8) | (bytes[offset + index] & 0xffL);
        return value;
    }

    private static void checkDeadline(long started) throws Exceeded {
        if (System.nanoTime() - started >= PREFLIGHT_NANOS)
            throw new Exceeded("Office preflight budget exceeded");
    }

    private static void set(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalStateException(
                    "XML parser cannot enforce Office preflight", unsupported);
        }
    }

    private static boolean isXml(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xml") || lower.endsWith(".rels");
    }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        private final long perEntryLimit;
        private final long priorTotal;
        private final long totalLimit;
        private final long priorXml;
        private final long xmlLimit;
        private final long started;
        private long count;

        private CountingInputStream(
                InputStream input,
                long perEntryLimit,
                long priorTotal,
                long totalLimit,
                long priorXml,
                long xmlLimit,
                long started) {
            super(input);
            this.perEntryLimit = perEntryLimit;
            this.priorTotal = priorTotal;
            this.totalLimit = totalLimit;
            this.priorXml = priorXml;
            this.xmlLimit = xmlLimit;
            this.started = started;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) record(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = in.read(bytes, offset, length);
            if (read > 0) record(read);
            return read;
        }

        private void record(int amount) throws Exceeded {
            OfficeArchiveBudget.checkDeadline(started);
            count += amount;
            if (count > perEntryLimit
                    || priorTotal + count > totalLimit
                    || priorXml + count > xmlLimit)
                throw new Exceeded("Office inflated-size limit exceeded");
        }

        private long count() {
            return count;
        }

        private void checkDeadline() throws Exceeded {
            OfficeArchiveBudget.checkDeadline(started);
        }

        private void drain() throws IOException {
            byte[] buffer = new byte[8192];
            while (read(buffer) != -1) {}
        }
    }
}
