package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

final class SkillPackageTestSupport {

  private SkillPackageTestSupport() {}

  static ArchiveEntry file(String name, String content) {
    return file(name, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static ArchiveEntry file(String name, byte[] content) {
    return new ArchiveEntry(name, content.clone(), null, ZipEntry.DEFLATED);
  }

  static ArchiveEntry unixFile(String name, byte[] content, int unixMode) {
    return new ArchiveEntry(name, content.clone(), unixMode, ZipEntry.DEFLATED);
  }

  static ArchiveEntry directory(String name) {
    String directoryName = name.endsWith("/") ? name : name + "/";
    return new ArchiveEntry(directoryName, new byte[0], null, ZipEntry.STORED);
  }

  static byte[] zip(ArchiveEntry... entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(bytes)) {
      for (ArchiveEntry value : entries) {
        ZipArchiveEntry entry = new ZipArchiveEntry(value.name());
        entry.setMethod(value.method());
        if (value.unixMode() != null) {
          entry.setUnixMode(value.unixMode());
        }
        if (value.method() == ZipEntry.STORED) {
          CRC32 crc = new CRC32();
          crc.update(value.content());
          entry.setSize(value.content().length);
          entry.setCompressedSize(value.content().length);
          entry.setCrc(crc.getValue());
        }
        output.putArchiveEntry(entry);
        output.write(value.content());
        output.closeArchiveEntry();
      }
      output.finish();
    }
    return bytes.toByteArray();
  }

  static byte[] validSkillMarkdown(String name) {
    return ("---\n"
            + "name: "
            + name
            + "\n"
            + "description: Use this Skill for tests.\n"
            + "---\n\n"
            + "# Instructions\n\nDo the safe test work.\n")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  static byte[] markEncrypted(byte[] archive) {
    byte[] patched = archive.clone();
    patchFlag(patched, 0x04034b50, 6, 0x0001);
    patchFlag(patched, 0x02014b50, 8, 0x0001);
    return patched;
  }

  static byte[] markUnsupportedCompression(byte[] archive, int method) {
    byte[] patched = archive.clone();
    patchUnsignedShort(patched, 0x04034b50, 8, method);
    patchUnsignedShort(patched, 0x02014b50, 10, method);
    return patched;
  }

  static byte[] underreportUncompressedSize(byte[] archive, int size) {
    byte[] patched = archive.clone();
    patchUnsignedInt(patched, 0x04034b50, 22, size);
    patchUnsignedInt(patched, 0x02014b50, 24, size);
    return patched;
  }

  static byte[] replaceAsciiName(byte[] archive, String currentName, String replacement) {
    byte[] current = currentName.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] changed = replacement.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    if (current.length != changed.length) {
      throw new IllegalArgumentException("ZIP name replacement must preserve byte length");
    }
    byte[] patched = archive.clone();
    for (int index = 0; index <= patched.length - current.length; index++) {
      boolean matches = true;
      for (int offset = 0; offset < current.length; offset++) {
        if (patched[index + offset] != current[offset]) {
          matches = false;
          break;
        }
      }
      if (matches) {
        System.arraycopy(changed, 0, patched, index, changed.length);
        index += changed.length - 1;
      }
    }
    return patched;
  }

  static SkillLimits limits(
      long archive,
      long expanded,
      long file,
      long skillMarkdown,
      long frontmatter,
      int entries,
      int depth,
      int pathChars,
      int ratio) {
    return new SkillLimits(
        archive,
        expanded,
        file,
        skillMarkdown,
        frontmatter,
        8,
        entries,
        depth,
        pathChars,
        ratio,
        64,
        1024,
        12_000,
        Duration.ofHours(24));
  }

  static void assertStagingEmpty(Path oryxosRoot) throws IOException {
    Path staging = oryxosRoot.resolve(".staging/skill-import");
    if (!Files.exists(staging)) {
      return;
    }
    try (var events = Files.list(staging)) {
      assertEquals(0, events.count(), "failed imports must not leave staging events");
    }
  }

  private static void patchFlag(byte[] bytes, int signature, int offset, int flag) {
    for (int index : signatureOffsets(bytes, signature)) {
      int current = unsignedShort(bytes, index + offset);
      writeUnsignedShort(bytes, index + offset, current | flag);
    }
  }

  private static void patchUnsignedShort(byte[] bytes, int signature, int offset, int value) {
    for (int index : signatureOffsets(bytes, signature)) {
      writeUnsignedShort(bytes, index + offset, value);
    }
  }

  private static void patchUnsignedInt(byte[] bytes, int signature, int offset, long value) {
    for (int index : signatureOffsets(bytes, signature)) {
      int position = index + offset;
      bytes[position] = (byte) value;
      bytes[position + 1] = (byte) (value >>> 8);
      bytes[position + 2] = (byte) (value >>> 16);
      bytes[position + 3] = (byte) (value >>> 24);
    }
  }

  private static java.util.List<Integer> signatureOffsets(byte[] bytes, int signature) {
    java.util.List<Integer> offsets = new java.util.ArrayList<>();
    for (int index = 0; index <= bytes.length - 4; index++) {
      if ((bytes[index] & 0xff) == (signature & 0xff)
          && (bytes[index + 1] & 0xff) == ((signature >>> 8) & 0xff)
          && (bytes[index + 2] & 0xff) == ((signature >>> 16) & 0xff)
          && (bytes[index + 3] & 0xff) == ((signature >>> 24) & 0xff)) {
        offsets.add(index);
      }
    }
    return offsets;
  }

  private static int unsignedShort(byte[] bytes, int position) {
    return (bytes[position] & 0xff) | ((bytes[position + 1] & 0xff) << 8);
  }

  private static void writeUnsignedShort(byte[] bytes, int position, int value) {
    bytes[position] = (byte) value;
    bytes[position + 1] = (byte) (value >>> 8);
  }

  record ArchiveEntry(String name, byte[] content, Integer unixMode, int method) {
    ArchiveEntry {
      content = content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }
  }
}
