package io.oryxos.core.skill;

import io.oryxos.core.context.MarkdownFrontmatter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Stages and validates one untrusted ZIP before management code atomically publishes it. */
public final class SkillPackageImporter {

  private static final String ENTRYPOINT = "SKILL.md";
  private static final String DISABLED_MARKER = ".oryxos-disabled";
  private static final String ORIGIN_FILE = ".oryxos-origin.yml";
  private static final String STAGING_DIRECTORY = ".staging";
  private static final String IMPORT_DIRECTORY = "skill-import";
  private static final String CURRENT_DIRECTORY = ".";
  private static final String PARENT_DIRECTORY = "..";
  private static final String ROOT_PATH_PREFIX = "/";
  private static final char BACKSLASH = '\\';
  private static final int MAGIC_PREFIX_BYTES = 512;
  private static final int MAX_ORIGIN_BYTES = 4096;
  private static final int MAX_FILENAME_CHARS = 255;
  private static final Pattern DRIVE_PATH = Pattern.compile("[A-Za-z]:.*");
  private static final Set<String> BLOCKED_EXTENSIONS =
      Set.of(
          ".zip", ".jar", ".war", ".ear", ".tar", ".tgz", ".gz", ".bz2", ".xz", ".7z", ".rar",
          ".class", ".so", ".dylib", ".dll", ".exe");

  private final Path oryxosRoot;
  private final SkillLimits limits;
  private final Clock clock;
  private final SkillMetadataReader metadataReader;
  private final SkillContentValidator contentValidator;

  public SkillPackageImporter(Path oryxosRoot, SkillLimits limits) {
    this(oryxosRoot, limits, Clock.systemUTC());
  }

  SkillPackageImporter(Path oryxosRoot, SkillLimits limits, Clock clock) {
    this.oryxosRoot = Objects.requireNonNull(oryxosRoot, "oryxosRoot").toAbsolutePath().normalize();
    this.limits = Objects.requireNonNull(limits, "limits");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.metadataReader = new SkillMetadataReader();
    this.contentValidator = new SkillContentValidator();
  }

  public PreparedSkill prepare(InputStream upload, String originalFilename) {
    Objects.requireNonNull(upload, "upload");
    Path stagingRoot = ensureStagingRoot();
    Path event = null;
    try {
      event = stagingRoot.resolve(UUID.randomUUID().toString());
      Files.createDirectory(event);
      Path archive = event.resolve("upload.zip");
      long archiveBytes = copyUpload(upload, archive);
      Path unpacked = Files.createDirectory(event.resolve("unpacked"));
      extractArchive(archive, unpacked, archiveBytes);
      NormalizedPackage normalized = normalizePackage(event, unpacked);
      SkillMetadata metadata = readMetadata(normalized.agentDir(), normalized.packageRoot());
      SkillContentValidator.ContentStats contentStats = validateContent(normalized.packageRoot());
      SkillOrigin origin =
          new SkillOrigin(
              SkillOrigin.CURRENT_SCHEMA_VERSION,
              SkillSource.UPLOAD,
              sanitizeOriginalFilename(originalFilename),
              clock.instant());
      writeOrigin(normalized.packageRoot(), origin);
      return new PreparedSkill(
          event, normalized.packageRoot(), metadata.name(), metadata, origin, contentStats);
    } catch (SkillImportException e) {
      deleteQuietly(event, stagingRoot);
      throw e;
    } catch (SkillValidationException e) {
      deleteQuietly(event, stagingRoot);
      throw importValidation(e);
    } catch (UncheckedIOException e) {
      deleteQuietly(event, stagingRoot);
      throw e;
    } catch (IOException e) {
      deleteQuietly(event, stagingRoot);
      throw new UncheckedIOException("Skill package I/O failed", e);
    } catch (RuntimeException e) {
      deleteQuietly(event, stagingRoot);
      throw e;
    }
  }

  public void discard(PreparedSkill prepared) {
    Objects.requireNonNull(prepared, "prepared");
    Path stagingRoot = stagingRootPath();
    Path event = prepared.stagingEventDir().toAbsolutePath().normalize();
    if (!stagingRoot.equals(event.getParent())
        || !prepared.packageRoot().toAbsolutePath().normalize().startsWith(event)) {
      throw new IllegalArgumentException("Prepared Skill does not belong to this staging root");
    }
    if (!Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    verifyStagingRoot(stagingRoot);
    if (!Files.exists(event, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      deleteTreeNoFollow(event, stagingRoot);
    } catch (IOException e) {
      throw new UncheckedIOException("Skill staging cleanup failed", e);
    }
  }

  public void cleanupOrphans() {
    Path stagingRoot = stagingRootPath();
    if (!Files.exists(stagingRoot, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    verifyStagingRoot(stagingRoot);
    Instant cutoff = clock.instant().minus(limits.stagingTtl());
    try (DirectoryStream<Path> events = Files.newDirectoryStream(stagingRoot)) {
      for (Path event : events) {
        BasicFileAttributes attributes =
            Files.readAttributes(event, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
          deleteTreeNoFollow(event, stagingRoot);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Skill staging cleanup failed", e);
    }
  }

  private Path ensureStagingRoot() {
    try {
      if (!Files.exists(oryxosRoot, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectories(oryxosRoot);
      }
      BasicFileAttributes rootAttributes =
          Files.readAttributes(oryxosRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (rootAttributes.isSymbolicLink() || !rootAttributes.isDirectory()) {
        throw unsafeStaging();
      }
      Path rootReal = oryxosRoot.toRealPath();
      Path staging = createSafeDirectory(oryxosRoot, STAGING_DIRECTORY, rootReal);
      Path imports = createSafeDirectory(staging, IMPORT_DIRECTORY, rootReal);
      Path stagingReal = staging.toRealPath();
      Path importsReal = imports.toRealPath();
      if (!rootReal.equals(stagingReal.getParent())
          || !stagingReal.equals(importsReal.getParent())) {
        throw unsafeStaging();
      }
      return imports;
    } catch (SkillImportException e) {
      throw e;
    } catch (IOException e) {
      throw new UncheckedIOException("Skill staging is unavailable", e);
    }
  }

  private static Path createSafeDirectory(Path parent, String childName, Path rootReal)
      throws IOException {
    Path child = parent.resolve(childName);
    try {
      Files.createDirectory(child);
    } catch (FileAlreadyExistsException ignored) {
      // Validated immediately below without following links.
    }
    BasicFileAttributes attributes =
        Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (attributes.isSymbolicLink()
        || !attributes.isDirectory()
        || !child.toRealPath().startsWith(rootReal)) {
      throw unsafeStaging();
    }
    return child;
  }

  private long copyUpload(InputStream upload, Path archive) throws IOException {
    Set<OpenOption> options =
        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    long total = 0;
    byte[] buffer = new byte[8192];
    try (SeekableByteChannel channel = Files.newByteChannel(archive, options);
        OutputStream output = Channels.newOutputStream(channel)) {
      int read;
      while ((read = upload.read(buffer)) != -1) {
        if (read == 0) {
          continue;
        }
        total = addExact(total, read, "ARCHIVE_TOO_LARGE", "Skill archive is too large");
        if (total > limits.maxArchiveBytes()) {
          throw tooLarge("ARCHIVE_TOO_LARGE", "Skill archive is too large");
        }
        output.write(buffer, 0, read);
      }
    }
    if (total == 0) {
      throw new SkillImportException("EMPTY_ARCHIVE", "Skill archive is empty");
    }
    return total;
  }

  private void extractArchive(Path archive, Path unpacked, long archiveBytes) {
    CanonicalPathRegistry canonicalPaths = new CanonicalPathRegistry();
    ExtractionBudget budget = new ExtractionBudget(archiveBytes);
    ZipFile opened;
    try {
      opened = ZipFile.builder().setPath(archive).get();
    } catch (IOException | IllegalArgumentException e) {
      throw new SkillImportException("INVALID_ARCHIVE", "Skill archive is invalid");
    }
    try (ZipFile zip = opened) {
      Enumeration<ZipArchiveEntry> entries = zip.getEntries();
      int entryCount = 0;
      while (entries.hasMoreElements()) {
        ZipArchiveEntry entry = entries.nextElement();
        entryCount++;
        if (entryCount > limits.maxEntries()) {
          throw tooLarge("TOO_MANY_ENTRIES", "Skill archive has too many entries");
        }
        EntryPath entryPath = validateEntry(zip, entry, canonicalPaths);
        if (entryPath.directory()) {
          createDirectoryPath(unpacked, entryPath.segments());
        } else {
          materializeFile(zip, entry, unpacked, entryPath, budget);
        }
      }
      if (entryCount == 0) {
        throw new SkillImportException("EMPTY_ARCHIVE", "Skill archive is empty");
      }
    } catch (SkillImportException e) {
      throw e;
    } catch (ZipException e) {
      throw new SkillImportException("INVALID_ARCHIVE", "Skill archive is invalid");
    } catch (IOException e) {
      throw new UncheckedIOException("Skill package I/O failed", e);
    }
  }

  private EntryPath validateEntry(
      ZipFile zip, ZipArchiveEntry entry, CanonicalPathRegistry canonicalPaths) {
    byte[] rawName = entry.getRawName();
    if (rawName != null) {
      for (byte value : rawName) {
        if (value == 0 || value == '\\') {
          throw invalidEntryPath();
        }
      }
    }
    if (entry.getGeneralPurposeBit().usesEncryption()) {
      throw new SkillImportException("ENCRYPTED_ENTRY", "Encrypted ZIP entries are not supported");
    }
    int method = entry.getMethod();
    boolean supportedMethod = method == ZipEntry.STORED || method == ZipEntry.DEFLATED;
    if (!supportedMethod) {
      throw unsupportedCompression();
    }
    if (!zip.canReadEntryData(entry)) {
      throw unsupportedCompression();
    }

    boolean directory = entry.isDirectory() || entry.getName().endsWith("/");
    int unixMode = entry.getUnixMode();
    if (entry.isUnixSymlink()) {
      throw new SkillImportException("LINK_NOT_ALLOWED", "ZIP links are not allowed");
    }
    if (unixMode != 0) {
      int fileType = unixMode & UnixStat.FILE_TYPE_FLAG;
      int requiredType = directory ? UnixStat.DIR_FLAG : UnixStat.FILE_FLAG;
      if (fileType != requiredType) {
        throw new SkillImportException(
            "UNSUPPORTED_ENTRY_TYPE", "ZIP special-file entries are not allowed");
      }
    }

    EntryPath path = normalizeEntryPath(entry.getName(), directory);
    canonicalPaths.register(path);
    return path;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "NFC normalization and Locale.ROOT case folding deliberately reject canonically equivalent ZIP paths; they do not authenticate identities or grant access.")
  private EntryPath normalizeEntryPath(String rawName, boolean directory) {
    if (rawName == null || rawName.isEmpty()) {
      throw invalidEntryPath();
    }
    if (rawName.startsWith(ROOT_PATH_PREFIX) || DRIVE_PATH.matcher(rawName).matches()) {
      throw invalidEntryPath();
    }
    if (rawName.indexOf(BACKSLASH) >= 0) {
      throw invalidEntryPath();
    }
    if (rawName
        .codePoints()
        .anyMatch(codePoint -> codePoint == 0 || Character.isISOControl(codePoint))) {
      throw invalidEntryPath();
    }
    String withoutTrailingSlash =
        directory && rawName.endsWith("/") ? rawName.substring(0, rawName.length() - 1) : rawName;
    String[] rawSegments = withoutTrailingSlash.split("/", -1);
    List<String> segments = new ArrayList<>(rawSegments.length);
    List<String> sourceSegments = new ArrayList<>(rawSegments.length);
    for (String rawSegment : rawSegments) {
      if (rawSegment.isEmpty() || isDotSegment(rawSegment)) {
        throw invalidEntryPath();
      }
      if (rawSegment.codePoints().anyMatch(SkillPackageImporter::isUnsafePathCodePoint)) {
        throw invalidEntryPath();
      }
      String segment = Normalizer.normalize(rawSegment, Normalizer.Form.NFC);
      if (segment.isEmpty() || isDotSegment(segment)) {
        throw invalidEntryPath();
      }
      if (segment.codePoints().anyMatch(SkillPackageImporter::isUnsafePathCodePoint)) {
        throw invalidEntryPath();
      }
      if (segment.toLowerCase(Locale.ROOT).startsWith(".oryxos-")) {
        throw new SkillImportException(
            "RESERVED_ENTRY", "ZIP archive contains an OryxOS reserved entry");
      }
      sourceSegments.add(rawSegment);
      segments.add(segment);
    }
    if (segments.isEmpty()) {
      throw invalidEntryPath();
    }
    String normalized = String.join("/", segments);
    if (segments.size() > limits.maxDepth()) {
      throw new SkillImportException("PATH_TOO_DEEP", "ZIP entry path is too deep");
    }
    if (normalized.codePointCount(0, normalized.length()) > limits.maxPathChars()) {
      throw new SkillImportException("PATH_TOO_LONG", "ZIP entry path is too long");
    }
    String filename = segments.get(segments.size() - 1).toLowerCase(Locale.ROOT);
    if (!directory && BLOCKED_EXTENSIONS.stream().anyMatch(filename::endsWith)) {
      throw unsupportedFileType();
    }
    String canonical = normalized.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    return new EntryPath(
        normalized, List.copyOf(segments), List.copyOf(sourceSegments), canonical, directory);
  }

  private void createDirectoryPath(Path root, List<String> segments) throws IOException {
    Path current = root;
    for (String segment : segments) {
      current = current.resolve(segment).normalize();
      ensureContained(root, current);
      try {
        Files.createDirectory(current);
      } catch (FileAlreadyExistsException ignored) {
        BasicFileAttributes attributes =
            Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
          throw new SkillImportException("DUPLICATE_ENTRY", "ZIP entry paths conflict");
        }
      }
    }
  }

  private void materializeFile(
      ZipFile zip, ZipArchiveEntry entry, Path root, EntryPath entryPath, ExtractionBudget budget)
      throws IOException {
    List<String> parentSegments = entryPath.segments().subList(0, entryPath.segments().size() - 1);
    createDirectoryPath(root, parentSegments);
    Path target = root.resolve(entryPath.normalized()).normalize();
    ensureContained(root, target);
    Set<OpenOption> options =
        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    byte[] prefix = new byte[MAGIC_PREFIX_BYTES];
    int prefixSize = 0;
    long fileBytes = 0;
    CRC32 crc = new CRC32();
    try (InputStream input = zip.getInputStream(entry);
        SeekableByteChannel channel = Files.newByteChannel(target, options);
        OutputStream output = Channels.newOutputStream(channel)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (read == 0) {
          continue;
        }
        fileBytes = addExact(fileBytes, read, "FILE_TOO_LARGE", "Skill file is too large");
        long fileLimit =
            ENTRYPOINT.equals(entryPath.segments().get(entryPath.segments().size() - 1))
                ? Math.min(limits.maxFileBytes(), limits.maxSkillMarkdownBytes())
                : limits.maxFileBytes();
        if (fileBytes > fileLimit) {
          String code =
              ENTRYPOINT.equals(entryPath.segments().get(entryPath.segments().size() - 1))
                  ? "SKILL_MARKDOWN_TOO_LARGE"
                  : "FILE_TOO_LARGE";
          throw tooLarge(code, "Skill file is too large");
        }
        budget.add(read);
        int prefixCopy = Math.min(read, MAGIC_PREFIX_BYTES - prefixSize);
        if (prefixCopy > 0) {
          System.arraycopy(buffer, 0, prefix, prefixSize, prefixCopy);
          prefixSize += prefixCopy;
        }
        crc.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    } catch (FileAlreadyExistsException e) {
      throw new SkillImportException("DUPLICATE_ENTRY", "ZIP entry paths conflict");
    }
    if (entry.getCrc() >= 0 && entry.getCrc() != crc.getValue()) {
      throw new SkillImportException("INVALID_ARCHIVE", "Skill archive is invalid");
    }
    byte[] actualPrefix = java.util.Arrays.copyOf(prefix, prefixSize);
    if (hasBlockedMagic(actualPrefix)) {
      throw unsupportedFileType();
    }
  }

  private NormalizedPackage normalizePackage(Path event, Path unpacked) throws IOException {
    List<Path> topLevel;
    try (var entries = Files.list(unpacked)) {
      topLevel =
          entries.sorted(Comparator.comparing(SkillPackageImporter::packageMemberName)).toList();
    }
    if (topLevel.isEmpty()) {
      throw invalidPackageShape();
    }

    Path sourceRoot;
    String directoryName;
    Path rootEntrypoint = unpacked.resolve(ENTRYPOINT);
    if (Files.isRegularFile(rootEntrypoint, LinkOption.NOFOLLOW_LINKS)) {
      sourceRoot = unpacked;
      directoryName = readFlatPackageName(rootEntrypoint);
    } else if (topLevel.size() == 1
        && Files.isDirectory(topLevel.get(0), LinkOption.NOFOLLOW_LINKS)) {
      sourceRoot = topLevel.get(0);
      directoryName = packageMemberName(sourceRoot);
    } else {
      throw invalidPackageShape();
    }

    Path expectedEntrypoint = sourceRoot.resolve(ENTRYPOINT).normalize();
    if (!Files.isRegularFile(expectedEntrypoint, LinkOption.NOFOLLOW_LINKS)
        || countEntrypoints(sourceRoot, expectedEntrypoint) != 1) {
      throw invalidPackageShape();
    }

    Path agentDir = Files.createDirectory(event.resolve("prepared-agent"));
    Path skillsDir = Files.createDirectory(agentDir.resolve("skills"));
    Path target = skillsDir.resolve(directoryName).normalize();
    if (!skillsDir.equals(target.getParent())) {
      throw invalidPackageShape();
    }
    if (sourceRoot.equals(unpacked)) {
      Files.createDirectory(target);
      for (Path child : topLevel) {
        Files.move(child, target.resolve(packageMemberName(child)));
      }
      Files.delete(unpacked);
    } else {
      Files.move(sourceRoot, target);
      Files.delete(unpacked);
    }
    return new NormalizedPackage(agentDir, target);
  }

  private String readFlatPackageName(Path entrypoint) {
    MarkdownFrontmatter.Parsed parsed =
        MarkdownFrontmatter.read(
            entrypoint, limits.maxSkillMarkdownBytes(), limits.maxFrontmatterBytes());
    Map<Object, Object> manifest =
        metadataReader.parseYaml(
            parsed.yaml(),
            limits.maxYamlNestingDepth(),
            toIntLimit(limits.maxFrontmatterBytes()),
            ENTRYPOINT);
    Object rawName = manifest.get("name");
    if (!(rawName instanceof String name) || !SkillMetadata.isValidName(name)) {
      throw new SkillValidationException(SkillValidationCode.INVALID_NAME, "Skill name is invalid");
    }
    return name;
  }

  private static long countEntrypoints(Path root, Path expected) throws IOException {
    try (var paths = Files.walk(root)) {
      return paths
          .filter(path -> ENTRYPOINT.equals(packageMemberName(path)))
          .peek(
              path -> {
                if (!path.normalize().equals(expected)) {
                  throw invalidPackageShape();
                }
              })
          .count();
    }
  }

  private SkillMetadata readMetadata(Path agentDir, Path packageRoot) {
    try {
      return metadataReader.read(agentDir, packageRoot, limits);
    } catch (SkillValidationException e) {
      throw importValidation(e);
    }
  }

  private SkillContentValidator.ContentStats validateContent(Path packageRoot) {
    try {
      return contentValidator.validate(packageRoot, limits);
    } catch (SkillValidationException e) {
      throw importValidation(e);
    }
  }

  private static void writeOrigin(Path packageRoot, SkillOrigin origin) throws IOException {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("schemaVersion", origin.schemaVersion());
    values.put("sourceType", "upload");
    values.put("originalFilename", origin.originalFilename());
    values.put("importedAt", origin.importedAt().toString());
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(false);
    options.setSplitLines(false);
    String yaml = new Yaml(options).dump(values);
    byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_ORIGIN_BYTES) {
      throw new SkillImportException("ORIGIN_INVALID", "Skill origin metadata is invalid");
    }
    Path originPath = packageRoot.resolve(ORIGIN_FILE);
    Set<OpenOption> writeOptions =
        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = Files.newByteChannel(originPath, writeOptions)) {
      java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "NFC is used only to stabilize display-only audit provenance before path separators and unsafe text are removed; the value never authorizes filesystem access.")
  private static String sanitizeOriginalFilename(String originalFilename) {
    String candidate = originalFilename == null ? "" : originalFilename.replace('\\', '/');
    int separator = candidate.lastIndexOf('/');
    if (separator >= 0) {
      candidate = candidate.substring(separator + 1);
    }
    candidate = Normalizer.normalize(candidate.strip(), Normalizer.Form.NFC);
    StringBuilder sanitized = new StringBuilder(Math.min(candidate.length(), MAX_FILENAME_CHARS));
    int retainedCodePoints = 0;
    for (int offset = 0; offset < candidate.length() && retainedCodePoints < MAX_FILENAME_CHARS; ) {
      int codePoint = candidate.codePointAt(offset);
      int type = Character.getType(codePoint);
      boolean unsafe =
          Character.isISOControl(codePoint)
              || type == Character.FORMAT
              || type == Character.LINE_SEPARATOR
              || type == Character.PARAGRAPH_SEPARATOR;
      sanitized.appendCodePoint(unsafe ? '_' : codePoint);
      retainedCodePoints++;
      offset += Character.charCount(codePoint);
    }
    String result = sanitized.toString().strip();
    if (result.isEmpty() || isDotSegment(result)) {
      return "upload.zip";
    }
    return result;
  }

  private void verifyStagingRoot(Path stagingRoot) {
    try {
      Path staging = oryxosRoot.resolve(STAGING_DIRECTORY).normalize();
      if (!stagingRoot.equals(staging.resolve(IMPORT_DIRECTORY).normalize())) {
        throw unsafeStaging();
      }
      BasicFileAttributes workspaceAttributes =
          Files.readAttributes(oryxosRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      BasicFileAttributes stagingAttributes =
          Files.readAttributes(staging, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      BasicFileAttributes importAttributes =
          Files.readAttributes(stagingRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      Path workspaceReal = oryxosRoot.toRealPath();
      Path stagingReal = staging.toRealPath();
      Path importReal = stagingRoot.toRealPath();
      if (workspaceAttributes.isSymbolicLink()
          || !workspaceAttributes.isDirectory()
          || stagingAttributes.isSymbolicLink()
          || !stagingAttributes.isDirectory()
          || importAttributes.isSymbolicLink()
          || !importAttributes.isDirectory()
          || !workspaceReal.equals(stagingReal.getParent())
          || !stagingReal.equals(importReal.getParent())) {
        throw unsafeStaging();
      }
    } catch (SkillImportException e) {
      throw e;
    } catch (IOException e) {
      throw new UncheckedIOException("Skill staging is unavailable", e);
    }
  }

  private Path stagingRootPath() {
    return oryxosRoot.resolve(STAGING_DIRECTORY).resolve(IMPORT_DIRECTORY).normalize();
  }

  private static void deleteTreeNoFollow(Path target, Path stagingRoot) throws IOException {
    Path normalizedTarget = target.toAbsolutePath().normalize();
    Path normalizedStaging = stagingRoot.toAbsolutePath().normalize();
    if (normalizedTarget.equals(normalizedStaging)
        || !normalizedTarget.startsWith(normalizedStaging)
        || !normalizedStaging.equals(normalizedTarget.getParent())) {
      throw new IOException("unsafe staging cleanup target");
    }
    if (!Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        normalizedTarget,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
              throws IOException {
            requireCleanupContainment(dir, normalizedTarget, normalizedStaging);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            requireCleanupContainment(file, normalizedTarget, normalizedStaging);
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
            if (error instanceof NoSuchFileException) {
              return FileVisitResult.CONTINUE;
            }
            throw error;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException error)
              throws IOException {
            if (error != null && !(error instanceof NoSuchFileException)) {
              throw error;
            }
            requireCleanupContainment(dir, normalizedTarget, normalizedStaging);
            Files.deleteIfExists(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void requireCleanupContainment(Path path, Path event, Path staging)
      throws IOException {
    Path normalized = path.toAbsolutePath().normalize();
    if (!normalized.startsWith(event) || !normalized.startsWith(staging)) {
      throw new IOException("unsafe staging cleanup path");
    }
  }

  private void deleteQuietly(Path event, Path stagingRoot) {
    if (event == null) {
      return;
    }
    try {
      verifyStagingRoot(stagingRoot);
      deleteTreeNoFollow(event, stagingRoot);
    } catch (IOException | RuntimeException ignored) {
      // Preserve the original safe domain rejection; orphan cleanup retries later.
    }
  }

  private static void ensureContained(Path root, Path path) {
    if (!path.startsWith(root)) {
      throw invalidEntryPath();
    }
  }

  private static String packageMemberName(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      throw invalidPackageShape();
    }
    return fileName.toString();
  }

  private static SkillImportException importValidation(SkillValidationException error) {
    String code = error.code().name();
    if (Set.of(
            SkillValidationCode.SKILL_MARKDOWN_TOO_LARGE,
            SkillValidationCode.FRONTMATTER_TOO_LARGE,
            SkillValidationCode.YAML_CODE_POINTS_EXCEEDED,
            SkillValidationCode.TOO_MANY_ENTRIES,
            SkillValidationCode.FILE_TOO_LARGE,
            SkillValidationCode.PACKAGE_TOO_LARGE)
        .contains(error.code())) {
      return new SkillPackageTooLargeException(code, error.getMessage());
    }
    return new SkillImportException(code, error.getMessage());
  }

  private static SkillPackageTooLargeException tooLarge(String code, String message) {
    return new SkillPackageTooLargeException(code, message);
  }

  private static SkillImportException invalidEntryPath() {
    return new SkillImportException("INVALID_ENTRY_PATH", "ZIP entry path is invalid");
  }

  private static SkillImportException unsupportedCompression() {
    return new SkillImportException(
        "UNSUPPORTED_COMPRESSION", "ZIP entry compression is not supported");
  }

  private static boolean isDotSegment(String value) {
    return CURRENT_DIRECTORY.equals(value) || PARENT_DIRECTORY.equals(value);
  }

  private static SkillImportException duplicateEntry() {
    return new SkillImportException("DUPLICATE_ENTRY", "ZIP entry paths must be unique");
  }

  private static SkillImportException invalidPackageShape() {
    return new SkillImportException(
        "INVALID_PACKAGE_SHAPE", "ZIP must contain exactly one Skill package");
  }

  private static SkillImportException unsupportedFileType() {
    return new SkillImportException(
        "UNSUPPORTED_FILE_TYPE", "Skill package contains a blocked file type");
  }

  private static SkillImportException unsafeStaging() {
    return new SkillImportException("STAGING_UNSAFE", "Skill staging path is unsafe");
  }

  private static long addExact(long current, long added, String code, String message) {
    try {
      return Math.addExact(current, added);
    } catch (ArithmeticException e) {
      throw tooLarge(code, message);
    }
  }

  private static int toIntLimit(long value) {
    return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
  }

  private static boolean isUnsafePathCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return codePoint == 0
        || Character.isISOControl(codePoint)
        || type == Character.FORMAT
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
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
    for (int index = 0; index < expected.length; index++) {
      if ((bytes[offset + index] & 0xff) != expected[index]) {
        return false;
      }
    }
    return true;
  }

  private record EntryPath(
      String normalized,
      List<String> segments,
      List<String> sourceSegments,
      String canonicalKey,
      boolean directory) {}

  private static final class CanonicalPathRegistry {

    private final Map<String, String> sourceSpellingByCanonicalPrefix = new java.util.HashMap<>();
    private final Set<String> explicitPaths = new java.util.HashSet<>();

    private void register(EntryPath path) {
      for (int length = 1; length <= path.segments().size(); length++) {
        String normalizedPrefix = String.join("/", path.segments().subList(0, length));
        String canonicalPrefix = canonicalPathKey(normalizedPrefix);
        String sourcePrefix = String.join("/", path.sourceSegments().subList(0, length));
        String previous =
            sourceSpellingByCanonicalPrefix.putIfAbsent(canonicalPrefix, sourcePrefix);
        if (previous != null && !previous.equals(sourcePrefix)) {
          throw duplicateEntry();
        }
      }
      if (!explicitPaths.add(path.canonicalKey())) {
        throw duplicateEntry();
      }
    }

    private static String canonicalPathKey(String path) {
      return path.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }
  }

  private record NormalizedPackage(Path agentDir, Path packageRoot) {}

  private final class ExtractionBudget {

    private final long archiveBytes;
    private long expandedBytes;

    private ExtractionBudget(long archiveBytes) {
      this.archiveBytes = archiveBytes;
    }

    private void add(long bytes) {
      expandedBytes =
          addExact(
              expandedBytes, bytes, "EXPANDED_TOO_LARGE", "Expanded Skill package is too large");
      if (expandedBytes > limits.maxExpandedBytes()) {
        throw tooLarge("EXPANDED_TOO_LARGE", "Expanded Skill package is too large");
      }
      long ratioBudget =
          archiveBytes > Long.MAX_VALUE / limits.maxExpansionRatio()
              ? Long.MAX_VALUE
              : archiveBytes * limits.maxExpansionRatio();
      if (expandedBytes > ratioBudget) {
        throw tooLarge("EXPANSION_RATIO_EXCEEDED", "Skill package expansion ratio is too large");
      }
    }
  }
}
