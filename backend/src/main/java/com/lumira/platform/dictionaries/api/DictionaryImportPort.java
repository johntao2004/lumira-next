package com.lumira.platform.dictionaries.api;

import java.util.List;

/** Bounded file decoding implemented by the host's isolated Office parsing infrastructure. */
public interface DictionaryImportPort {
    int MAX_BYTES = 20 * 1024 * 1024;

    record Parsed(String sha256, List<DictionaryDataset.Entry> items) {}

    Parsed parse(byte[] bytes, String filename, String structure);
}
