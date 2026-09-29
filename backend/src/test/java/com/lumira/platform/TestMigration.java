package com.lumira.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads one named section from the compressed historical SQL fixture archive. */
public final class TestMigration {
  private static final Path ARCHIVE = Path.of("src/test/resources/legacy-migrations.archive");

  private TestMigration() {}

  public static String read(String fileName) {
    String source;
    try {
      source = Files.readString(ARCHIVE);
    } catch (IOException error) {
      throw new UncheckedIOException("Cannot read historical SQL fixture archive", error);
    }
    String marker = "-- @legacy-migration " + fileName;
    int markerStart = source.indexOf(marker);
    if (markerStart < 0) throw new IllegalArgumentException("Unknown historical SQL fixture: " + fileName);
    int contentStart = source.indexOf('\n', markerStart);
    if (contentStart < 0) throw new IllegalArgumentException("Malformed historical SQL fixture: " + fileName);
    contentStart++;
    int nextMarker = source.indexOf("\n-- @legacy-migration ", contentStart);
    int contentEnd = nextMarker < 0 ? source.length() : nextMarker;
    return source.substring(contentStart, contentEnd).strip();
  }
}
