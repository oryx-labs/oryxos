package io.oryxos.core.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded, no-follow validation shared by workspace discovery and imported Skill packages. */
public final class SkillContentValidator {

  private static final String ENTRYPOINT = "SKILL.md";
  private static final String DISABLED_MARKER = ".oryxos-disabled";
  private static final String ORIGIN_FILE = ".oryxos-origin.yml";
  private static final String RESERVED_PREFIX = ".oryxos-";
  private static final char BACKSLASH = '\\';
  private static final int MAX_ORIGIN_BYTES = 4096;
  private static final int MAGIC_PREFIX_BYTES = 512;
  private static final Set<String> BLOCKED_EXTENSIONS =
      Set.of(
          ".zip", ".jar", ".war", ".ear", ".tar", ".tgz", ".gz", ".bz2", ".xz", ".7z", ".rar",
          ".class", ".so", ".dylib", ".dll", ".exe");

  /** Immutable package statistics; reserved OryxOS sidecars are deliberately excluded. */
  public record ContentStats(List<String> resources, int fileCount, long totalBytes) {
    public ContentStats {
      resources =
          resources == null
              ? List.of()
              : resources.stream().sorted(SkillContentValidator::compareCodePoints).toList();
      if (fileCount < 0 || totalBytes < 0) {
        throw new IllegalArgumentException("content statistics must not be negative");
      }
      if (fileCount != resources.size()) {
        throw new IllegalArgumentException("fileCount must equal the resource count");
      }
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "The canonical constructor stores a sorted unmodifiable list whose String elements are immutable.")
    public List<String> resources() {
      return resources;
    }
  }

  public ContentStats validate(Path skillDir, SkillLimits limits) {
    if (skillDir == null || limits == null) {
      throw new IllegalArgumentException("skillDir and limits are required");
    }
    String rootName = safeMember(skillDir.getFileName());
    BasicFileAttributes rootAttributes = readAttributes(skillDir, rootName);
    if (rootAttributes.isSymbolicLink()) {
      throw failure(SkillValidationCode.LINK_NOT_ALLOWED, rootName + " must not be a link");
    }
    if (!rootAttributes.isDirectory()) {
      throw failure(
          SkillValidationCode.SPECIAL_FILE_NOT_ALLOWED, rootName + " must be a directory");
    }

    Path entry = skillDir.resolve(ENTRYPOINT);
    BasicFileAttributes entryAttributes;
    try {
      entryAttributes =
          Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      throw failure(SkillValidationCode.MISSING_ENTRYPOINT, ENTRYPOINT + " is missing");
    } catch (IOException e) {
      throw failure(SkillValidationCode.CONTENT_UNREADABLE, ENTRYPOINT + " cannot be inspected");
    }
    if (entryAttributes.isSymbolicLink()) {
      throw failure(SkillValidationCode.LINK_NOT_ALLOWED, ENTRYPOINT + " must not be a link");
    }
    if (!entryAttributes.isRegularFile()) {
      throw failure(
          SkillValidationCode.SPECIAL_FILE_NOT_ALLOWED, ENTRYPOINT + " must be a regular file");
    }
    try {
      if (!FilesystemEntryNames.isStoredAs(entry, ENTRYPOINT)) {
        throw failure(
            SkillValidationCode.MISSING_ENTRYPOINT,
            ENTRYPOINT + " must use its canonical filename");
      }
    } catch (IOException error) {
      throw failure(SkillValidationCode.CONTENT_UNREADABLE, ENTRYPOINT + " cannot be inspected");
    }

    try {
      Path rootReal = skillDir.toRealPath();
      ScanVisitor visitor = new ScanVisitor(skillDir, rootReal, limits);
      Files.walkFileTree(skillDir, visitor);
      return visitor.contentStats();
    } catch (SkillValidationException e) {
      throw e;
    } catch (IOException e) {
      throw failure(
          SkillValidationCode.CONTENT_UNREADABLE, rootName + " content cannot be inspected");
    }
  }

  private static final class ScanVisitor extends SimpleFileVisitor<Path> {

    private final Path root;
    private final Path rootReal;
    private final SkillLimits limits;
    private final List<String> resources = new ArrayList<>();
    private int entries;
    private int fileCount;
    private long contentBytes;
    private long expandedBytes;

    private ScanVisitor(Path root, Path rootReal, SkillLimits limits) {
      this.root = root;
      this.rootReal = rootReal;
      this.limits = limits;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
        throws IOException {
      if (!dir.equals(root)) {
        String relative = relative(dir);
        if (isReservedName(requiredFileName(dir, relative))) {
          throw failure(
              SkillValidationCode.RESERVED_FILE_INVALID,
              safeMessage(relative) + " is reserved for regular files at the Skill root");
        }
        checkEntry(dir, attributes, true);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
      String relative = relative(file);
      String filename = requiredFileName(file, relative);
      boolean trustedRootSidecar =
          isReservedName(filename) && root.relativize(file).getNameCount() == 1;
      checkEntry(file, attributes, !trustedRootSidecar);
      if (attributes.isSymbolicLink()) {
        throw failure(
            SkillValidationCode.LINK_NOT_ALLOWED, safeMessage(relative) + " must not be a link");
      }
      if (!attributes.isRegularFile()) {
        throw failure(
            SkillValidationCode.SPECIAL_FILE_NOT_ALLOWED,
            safeMessage(relative) + " must be a regular file");
      }
      ensureContained(file, relative);
      long size = attributes.size();
      if (isReservedName(filename)) {
        validateReservedFile(file, relative, attributes);
        return FileVisitResult.CONTINUE;
      }
      if (ENTRYPOINT.equals(relative) && size > limits.maxSkillMarkdownBytes()) {
        throw failure(
            SkillValidationCode.SKILL_MARKDOWN_TOO_LARGE, ENTRYPOINT + " exceeds the size limit");
      }
      if (size > limits.maxFileBytes()) {
        throw failure(
            SkillValidationCode.FILE_TOO_LARGE, safeMessage(relative) + " exceeds the size limit");
      }
      expandedBytes = addBytes(expandedBytes, size, relative);
      if (expandedBytes > limits.maxExpandedBytes()) {
        throw failure(
            SkillValidationCode.PACKAGE_TOO_LARGE, "Skill content exceeds the size limit");
      }

      rejectBlockedContent(file, relative, filename);
      resources.add(relative);
      fileCount++;
      contentBytes = addBytes(contentBytes, size, relative);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException error) {
      throw failure(
          SkillValidationCode.CONTENT_UNREADABLE,
          safeMessage(relative(file)) + " cannot be inspected");
    }

    private void checkEntry(
        Path path, BasicFileAttributes attributes, boolean countsTowardPackageBudget)
        throws IOException {
      String relative = relative(path);
      if (countsTowardPackageBudget) {
        entries++;
        if (entries > limits.maxEntries()) {
          throw failure(SkillValidationCode.TOO_MANY_ENTRIES, "Skill has too many entries");
        }
      }
      Path relativePath = root.relativize(path);
      if (relativePath.getNameCount() > limits.maxDepth()) {
        throw failure(
            SkillValidationCode.PATH_TOO_DEEP, safeMessage(relative) + " is nested too deeply");
      }
      if (relative.codePointCount(0, relative.length()) > limits.maxPathChars()) {
        throw failure(
            SkillValidationCode.PATH_TOO_LONG, safeMessage(relative) + " path is too long");
      }
      if (relative.indexOf(BACKSLASH) >= 0
          || relative.codePoints().anyMatch(SkillContentValidator::isUnsafePathCodePoint)) {
        throw failure(
            SkillValidationCode.INVALID_PATH, "Skill path contains unsupported characters");
      }
      if (attributes.isSymbolicLink()) {
        throw failure(
            SkillValidationCode.LINK_NOT_ALLOWED, safeMessage(relative) + " must not be a link");
      }
      if (!attributes.isDirectory() && !attributes.isRegularFile()) {
        throw failure(
            SkillValidationCode.SPECIAL_FILE_NOT_ALLOWED,
            safeMessage(relative) + " must be a regular file or directory");
      }
      ensureContained(path, relative);
    }

    private void ensureContained(Path path, String relative) throws IOException {
      Path real = path.toRealPath();
      if (!real.startsWith(rootReal)) {
        throw failure(
            SkillValidationCode.OUTSIDE_SKILL_ROOT,
            safeMessage(relative) + " resolves outside the Skill root");
      }
    }

    private void validateReservedFile(Path file, String relative, BasicFileAttributes attributes) {
      if (root.relativize(file).getNameCount() != 1) {
        throw failure(
            SkillValidationCode.RESERVED_FILE_INVALID,
            safeMessage(relative) + " is reserved for the Skill root");
      }
      if (DISABLED_MARKER.equals(relative)) {
        if (attributes.size() != 0) {
          throw failure(
              SkillValidationCode.RESERVED_FILE_INVALID,
              DISABLED_MARKER + " must be an empty regular file");
        }
        return;
      }
      if (ORIGIN_FILE.equals(relative)) {
        validateOrigin(file, attributes.size());
        return;
      }
      throw failure(
          SkillValidationCode.RESERVED_FILE_INVALID,
          safeMessage(relative) + " is an unknown reserved file");
    }

    private ContentStats contentStats() {
      return new ContentStats(resources, fileCount, contentBytes);
    }

    private String relative(Path path) {
      Path relative = root.relativize(path);
      String value = relative.toString().replace('\\', '/');
      return value.isEmpty() ? safeMember(root.getFileName()) : value;
    }
  }

  private static void validateOrigin(Path file, long size) {
    if (size > MAX_ORIGIN_BYTES) {
      throw failure(
          SkillValidationCode.RESERVED_FILE_INVALID, ORIGIN_FILE + " exceeds the 4096-byte limit");
    }
    String text;
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = Files.newByteChannel(file, options);
        InputStream input = Channels.newInputStream(channel)) {
      byte[] bytes = input.readNBytes(MAX_ORIGIN_BYTES + 1);
      if (bytes.length > MAX_ORIGIN_BYTES) {
        throw failure(
            SkillValidationCode.RESERVED_FILE_INVALID,
            ORIGIN_FILE + " exceeds the 4096-byte limit");
      }
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw failure(
          SkillValidationCode.RESERVED_FILE_INVALID, ORIGIN_FILE + " must be valid UTF-8");
    } catch (IOException e) {
      throw failure(SkillValidationCode.RESERVED_FILE_INVALID, ORIGIN_FILE + " cannot be read");
    }

    Map<Object, Object> origin;
    try {
      origin = new SkillMetadataReader().parseYaml(text, 4, MAX_ORIGIN_BYTES, ORIGIN_FILE);
    } catch (SkillValidationException e) {
      throw failure(SkillValidationCode.RESERVED_FILE_INVALID, ORIGIN_FILE + " is invalid");
    }
    if (!origin
            .keySet()
            .equals(Set.of("schemaVersion", "sourceType", "originalFilename", "importedAt"))
        || !(origin.get("schemaVersion") instanceof Number schemaVersion)
        || schemaVersion.longValue() != SkillOrigin.CURRENT_SCHEMA_VERSION
        || schemaVersion.doubleValue() != SkillOrigin.CURRENT_SCHEMA_VERSION
        || !"upload".equals(origin.get("sourceType"))
        || !(origin.get("originalFilename") instanceof String filename)
        || !isSafeFilename(filename)
        || !isValidInstant(origin.get("importedAt"))) {
      throw failure(SkillValidationCode.RESERVED_FILE_INVALID, ORIGIN_FILE + " is invalid");
    }
  }

  private static boolean isValidInstant(Object value) {
    if (value instanceof Date) {
      return true;
    }
    if (!(value instanceof String text)) {
      return false;
    }
    try {
      Instant.parse(text);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static boolean isSafeFilename(String value) {
    return !value.isBlank()
        && value.codePointCount(0, value.length()) <= 255
        && !value.equals(".")
        && !value.equals("..")
        && value.indexOf('/') < 0
        && value.indexOf('\\') < 0
        && value.codePoints().noneMatch(SkillContentValidator::isUnsafeTextCodePoint);
  }

  private static boolean isUnsafeTextCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isISOControl(codePoint)
        || type == Character.FORMAT
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }

  private static void rejectBlockedContent(Path file, String relative, String filename) {
    String lower = filename.toLowerCase(Locale.ROOT);
    if (BLOCKED_EXTENSIONS.stream().anyMatch(lower::endsWith)) {
      throw failure(
          SkillValidationCode.UNSUPPORTED_FILE_TYPE,
          safeMessage(relative) + " has a blocked file type");
    }
    byte[] prefix = readPrefix(file, relative);
    if (hasBlockedMagic(prefix)) {
      throw failure(
          SkillValidationCode.UNSUPPORTED_FILE_TYPE,
          safeMessage(relative) + " has blocked binary content");
    }
  }

  private static byte[] readPrefix(Path file, String relative) {
    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = Files.newByteChannel(file, options);
        InputStream input = Channels.newInputStream(channel)) {
      return input.readNBytes(MAGIC_PREFIX_BYTES);
    } catch (IOException e) {
      throw failure(
          SkillValidationCode.CONTENT_UNREADABLE, safeMessage(relative) + " cannot be inspected");
    }
  }

  private static boolean hasBlockedMagic(byte[] bytes) {
    return startsWith(bytes, 0x50, 0x4b, 0x03, 0x04)
        || startsWith(bytes, 0x50, 0x4b, 0x05, 0x06)
        || startsWith(bytes, 0x50, 0x4b, 0x07, 0x08)
        || startsWith(bytes, 0x1f, 0x8b)
        || startsWith(bytes, 0x42, 0x5a, 0x68)
        || startsWith(bytes, 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00)
        || startsWith(bytes, 0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c)
        || startsWith(bytes, 0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)
        || startsWith(bytes, 0xca, 0xfe, 0xba, 0xbe)
        || startsWith(bytes, 0x7f, 0x45, 0x4c, 0x46)
        || startsWith(bytes, 0x4d, 0x5a)
        || startsWith(bytes, 0xfe, 0xed, 0xfa, 0xce)
        || startsWith(bytes, 0xce, 0xfa, 0xed, 0xfe)
        || startsWith(bytes, 0xfe, 0xed, 0xfa, 0xcf)
        || startsWith(bytes, 0xcf, 0xfa, 0xed, 0xfe)
        || startsWith(bytes, 0xbe, 0xba, 0xfe, 0xca)
        || startsWithAt(bytes, 257, 0x75, 0x73, 0x74, 0x61, 0x72);
  }

  private static boolean startsWith(byte[] bytes, int... expected) {
    return startsWithAt(bytes, 0, expected);
  }

  private static boolean startsWithAt(byte[] bytes, int offset, int... expected) {
    if (bytes.length < offset + expected.length) {
      return false;
    }
    for (int i = 0; i < expected.length; i++) {
      if ((bytes[offset + i] & 0xff) != expected[i]) {
        return false;
      }
    }
    return true;
  }

  private static long addBytes(long current, long added, String relative) {
    try {
      return Math.addExact(current, added);
    } catch (ArithmeticException e) {
      throw failure(
          SkillValidationCode.PACKAGE_TOO_LARGE, safeMessage(relative) + " exceeds the size limit");
    }
  }

  private static BasicFileAttributes readAttributes(Path path, String safeName) {
    try {
      return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      throw failure(SkillValidationCode.CONTENT_UNREADABLE, safeName + " is missing");
    } catch (IOException e) {
      throw failure(SkillValidationCode.CONTENT_UNREADABLE, safeName + " cannot be inspected");
    }
  }

  private static String requiredFileName(Path path, String relative) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      throw failure(SkillValidationCode.INVALID_PATH, safeMessage(relative) + " has no file name");
    }
    return fileName.toString();
  }

  static int compareCodePoints(String left, String right) {
    int leftIndex = 0;
    int rightIndex = 0;
    while (leftIndex < left.length() && rightIndex < right.length()) {
      int leftCodePoint = left.codePointAt(leftIndex);
      int rightCodePoint = right.codePointAt(rightIndex);
      int comparison = Integer.compare(leftCodePoint, rightCodePoint);
      if (comparison != 0) {
        return comparison;
      }
      leftIndex += Character.charCount(leftCodePoint);
      rightIndex += Character.charCount(rightCodePoint);
    }
    return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
  }

  private static boolean isUnsafePathCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return codePoint == 0
        || Character.isISOControl(codePoint)
        || type == Character.FORMAT
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }

  private static boolean isReservedName(String name) {
    return name.toLowerCase(Locale.ROOT).startsWith(RESERVED_PREFIX);
  }

  private static String safeMember(Path path) {
    return safeMember(path == null ? null : path.toString());
  }

  private static String safeMember(String value) {
    if (value == null || value.isBlank()) {
      return "Skill";
    }
    StringBuilder sanitizedBuilder = new StringBuilder(value.length());
    value
        .codePoints()
        .forEach(
            codePoint ->
                sanitizedBuilder.appendCodePoint(
                    isUnsafePathCodePoint(codePoint) ? '_' : codePoint));
    String sanitized = sanitizedBuilder.toString();
    int codePoints = sanitized.codePointCount(0, sanitized.length());
    return codePoints <= 512
        ? sanitized
        : sanitized.substring(0, sanitized.offsetByCodePoints(0, 512));
  }

  private static String safeMessage(String value) {
    return safeMember(value);
  }

  private static SkillValidationException failure(SkillValidationCode code, String message) {
    return new SkillValidationException(code, message);
  }
}
