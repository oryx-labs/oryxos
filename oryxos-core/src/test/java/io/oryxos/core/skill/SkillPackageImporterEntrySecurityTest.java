package io.oryxos.core.skill;

import static io.oryxos.core.skill.SkillPackageTestSupport.assertStagingEmpty;
import static io.oryxos.core.skill.SkillPackageTestSupport.file;
import static io.oryxos.core.skill.SkillPackageTestSupport.markEncrypted;
import static io.oryxos.core.skill.SkillPackageTestSupport.markUnsupportedCompression;
import static io.oryxos.core.skill.SkillPackageTestSupport.unixFile;
import static io.oryxos.core.skill.SkillPackageTestSupport.validSkillMarkdown;
import static io.oryxos.core.skill.SkillPackageTestSupport.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SkillPackageImporterEntrySecurityTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-22T10:30:00Z"), ZoneOffset.UTC);

  @TempDir Path tempDir;

  @ParameterizedTest
  @MethodSource("specialUnixModes")
  void rejectsSymlinkDeviceFifoAndSocketEntries(int mode, String expectedCode) throws Exception {
    Path root = Files.createDirectories(tempDir.resolve(".oryxos"));
    SkillPackageImporter importer = new SkillPackageImporter(root, SkillLimits.defaults(), CLOCK);
    byte[] archive =
        zip(
            file("SKILL.md", validSkillMarkdown("weather")),
            unixFile("special-entry", "target".getBytes(), mode));

    SkillImportException error =
        assertThrows(
            SkillImportException.class,
            () -> importer.prepare(new ByteArrayInputStream(archive), "special.zip"));

    assertEquals(expectedCode, error.reasonCode());
    assertStagingEmpty(root);
  }

  static Stream<Arguments> specialUnixModes() {
    return Stream.of(
        Arguments.of(0120777, "LINK_NOT_ALLOWED"),
        Arguments.of(0020666, "UNSUPPORTED_ENTRY_TYPE"),
        Arguments.of(0010666, "UNSUPPORTED_ENTRY_TYPE"),
        Arguments.of(0140666, "UNSUPPORTED_ENTRY_TYPE"));
  }

  @Test
  void rejectsEncryptedAndUnsupportedCompressionFromCentralDirectory() throws Exception {
    byte[] valid = zip(file("SKILL.md", validSkillMarkdown("weather")));
    assertRejected(markEncrypted(valid), "ENCRYPTED_ENTRY");
    assertRejected(markUnsupportedCompression(valid, 99), "UNSUPPORTED_COMPRESSION");
  }

  @Test
  void rejectsMalformedCentralDirectoryAsInvalidArchive() throws Exception {
    assertRejected(new byte[] {0x50, 0x4b, 0x03, 0x04, 0x00}, "INVALID_ARCHIVE");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        ".oryxos-disabled",
        ".oryxos-origin.yml",
        "ref/.oryxos-disabled",
        ".ORYXOS-disabled",
        ".OryxOS-origin.yml",
        "ref/.ORYXOS-private"
      })
  void rejectsOryxosReservedEntriesAnywhere(String reservedName) throws Exception {
    byte[] archive =
        zip(
            file("SKILL.md", validSkillMarkdown("weather")),
            file(reservedName, "attacker-controlled"));

    assertRejected(archive, "RESERVED_ENTRY");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "payload.zip",
        "payload.jar",
        "payload.war",
        "payload.ear",
        "payload.tar",
        "payload.tgz",
        "payload.gz",
        "payload.bz2",
        "payload.xz",
        "payload.7z",
        "payload.rar",
        "Payload.CLASS",
        "native.so",
        "native.dylib",
        "native.dll",
        "native.exe"
      })
  void rejectsNestedArchiveClassAndNativeExtensions(String filename) throws Exception {
    byte[] archive =
        zip(
            file("SKILL.md", validSkillMarkdown("weather")),
            file("assets/" + filename, "plain-looking content"));

    assertRejected(archive, "UNSUPPORTED_FILE_TYPE");
  }

  @ParameterizedTest
  @MethodSource("blockedMagic")
  void rejectsArchiveClassAndNativeMagicRegardlessOfExtension(byte[] magic) throws Exception {
    byte[] content = new byte[Math.max(512, magic.length)];
    System.arraycopy(magic, 0, content, 0, magic.length);
    byte[] archive =
        zip(file("SKILL.md", validSkillMarkdown("weather")), file("assets/innocent.dat", content));

    assertRejected(archive, "UNSUPPORTED_FILE_TYPE");
  }

  static Stream<byte[]> blockedMagic() {
    byte[] tar = new byte[512];
    System.arraycopy("ustar".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, tar, 257, 5);
    return Stream.of(
        new byte[] {0x50, 0x4b, 0x03, 0x04},
        new byte[] {0x50, 0x4b, 0x05, 0x06},
        new byte[] {0x50, 0x4b, 0x07, 0x08},
        new byte[] {0x1f, (byte) 0x8b},
        new byte[] {0x42, 0x5a, 0x68},
        new byte[] {(byte) 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00},
        new byte[] {0x37, 0x7a, (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c},
        new byte[] {0x52, 0x61, 0x72, 0x21, 0x1a, 0x07},
        new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe},
        new byte[] {0x7f, 0x45, 0x4c, 0x46},
        new byte[] {0x4d, 0x5a},
        new byte[] {(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xce},
        new byte[] {(byte) 0xce, (byte) 0xfa, (byte) 0xed, (byte) 0xfe},
        new byte[] {(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xcf},
        new byte[] {(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe},
        new byte[] {(byte) 0xbe, (byte) 0xba, (byte) 0xfe, (byte) 0xca},
        tar);
  }

  private void assertRejected(byte[] archive, String reasonCode) throws Exception {
    Path root = Files.createDirectories(tempDir.resolve(reasonCode + "/.oryxos"));
    SkillPackageImporter importer = new SkillPackageImporter(root, SkillLimits.defaults(), CLOCK);

    SkillImportException error =
        assertThrows(
            SkillImportException.class,
            () -> importer.prepare(new ByteArrayInputStream(archive), "unsafe.zip"));

    assertEquals(reasonCode, error.reasonCode());
    assertStagingEmpty(root);
  }
}
