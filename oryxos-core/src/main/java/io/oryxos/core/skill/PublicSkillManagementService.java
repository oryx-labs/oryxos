package io.oryxos.core.skill;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Public Skill import, global state and archive lifecycle over the filesystem truth. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "NFC plus Locale.ROOT case folding is intentionally used only as a conservative collision key; the original validated name remains the identity.")
public final class PublicSkillManagementService {

  private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
  private static final String DISABLED = ".oryxos-disabled";

  private final Path workspaceRoot;
  private final Path publicRoot;
  private final Path archiveRoot;
  private final PublicSkillCatalog catalog;
  private final SkillAssociationService associations;
  private final SkillPackageImporter importer;
  private final SkillGraphCoordinator graph;
  private final SkillManagementEventLogger events;
  private final Clock clock;

  public PublicSkillManagementService(
      Path workspaceRoot,
      PublicSkillCatalog catalog,
      SkillAssociationService associations,
      SkillPackageImporter importer,
      SkillGraphCoordinator graph,
      SkillManagementEventLogger events) {
    this(workspaceRoot, catalog, associations, importer, graph, events, Clock.systemUTC());
  }

  PublicSkillManagementService(
      Path workspaceRoot,
      PublicSkillCatalog catalog,
      SkillAssociationService associations,
      SkillPackageImporter importer,
      SkillGraphCoordinator graph,
      SkillManagementEventLogger events,
      Clock clock) {
    this.workspaceRoot =
        Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
    this.publicRoot = this.workspaceRoot.resolve("skills");
    this.archiveRoot = this.workspaceRoot.resolve("archive/.skills");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.associations = Objects.requireNonNull(associations, "associations");
    this.importer = Objects.requireNonNull(importer, "importer");
    this.graph = Objects.requireNonNull(graph, "graph");
    this.events = Objects.requireNonNull(events, "events");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public List<PublicSkillDescriptor> list() {
    return catalog.list().stream()
        .map(skill -> skill.withLinkedAgents(associations.findLinkedAgents(skill.name())))
        .toList();
  }

  public PublicSkillDescriptor get(String skillName) {
    PublicSkillDescriptor skill = catalog.get(skillName);
    return skill.withLinkedAgents(associations.findLinkedAgents(skill.name()));
  }

  public PublicSkillDescriptor importZip(InputStream zip, String originalFilename) {
    PreparedSkill prepared = importer.prepare(zip, originalFilename);
    String skill = prepared.directoryName();
    try {
      PublicSkillDescriptor result =
          graph.withGraphWrite(
              () -> {
                requireNoNameConflict(skill);
                Path target = publicRoot.resolve(skill);
                requireSameFileStore(prepared.packageRoot(), publicRoot);
                atomicMove(prepared.packageRoot(), target);
                return get(skill);
              });
      events.record("import", "success", skill, null, List.of(), null);
      return result;
    } catch (SkillConflictException | SkillValidationException error) {
      events.record("import", "rejected", skill, null, List.of(), reason(error));
      throw error;
    } catch (RuntimeException error) {
      events.record("import", "failed", skill, null, List.of(), "IO_FAILURE");
      throw error;
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    } finally {
      importer.discard(prepared);
    }
  }

  public PublicSkillDescriptor setEnabled(String skillName, boolean enabled) {
    String skill = SkillName.parse(skillName).value();
    try {
      PublicSkillDescriptor result =
          graph.withGraphWrite(
              () -> {
                PublicSkillDescriptor current = catalog.get(skill);
                Path marker = publicRoot.resolve(skill).resolve(DISABLED);
                if (enabled) {
                  catalog.requireLoadable(skill);
                  Files.deleteIfExists(marker);
                } else if (!Files.exists(marker, NOFOLLOW)) {
                  Path temporary = marker.resolveSibling(DISABLED + ".tmp-" + UUID.randomUUID());
                  try {
                    Files.write(temporary, new byte[0], StandardOpenOption.CREATE_NEW);
                    atomicMove(temporary, marker);
                  } finally {
                    Files.deleteIfExists(temporary);
                  }
                }
                return get(skill);
              });
      events.record(enabled ? "enable" : "disable", "success", skill, null, List.of(), null);
      return result;
    } catch (SkillValidationException | NoSuchElementException error) {
      events.record(
          enabled ? "enable" : "disable", "rejected", skill, null, List.of(), reason(error));
      throw error;
    } catch (IOException error) {
      events.record(enabled ? "enable" : "disable", "failed", skill, null, List.of(), "IO_FAILURE");
      throw new UncheckedIOException("Skill state could not be changed", error);
    } catch (RuntimeException error) {
      events.record(enabled ? "enable" : "disable", "failed", skill, null, List.of(), "IO_FAILURE");
      throw error;
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public DeleteResult delete(String skillName, boolean force) {
    String skill = SkillName.parse(skillName).value();
    try {
      DeleteResult result =
          graph.withGraphWrite(
              () -> {
                catalog.get(skill);
                List<String> linked = associations.findLinkedAgents(skill);
                if (!force && !linked.isEmpty()) {
                  throw new SkillInUseException(skill, linked);
                }
                if (linked.isEmpty()) {
                  archive(skill, force, List.of());
                  return new DeleteResult(skill, force, List.of(), true);
                }
                return graph.withAgentsMutation(linked, () -> forceDeleteLocked(skill));
              });
      events.record(
          force ? "force_delete" : "delete", "success", skill, null, result.affectedAgents(), null);
      return result;
    } catch (SkillInUseException error) {
      events.record("delete", "rejected", skill, null, error.linkedAgents(), error.reasonCode());
      throw error;
    } catch (NoSuchElementException error) {
      events.record(
          force ? "force_delete" : "delete", "rejected", skill, null, List.of(), "NOT_FOUND");
      throw error;
    } catch (RuntimeException error) {
      events.record(
          force ? "force_delete" : "delete", "failed", skill, null, List.of(), "IO_FAILURE");
      throw error;
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private DeleteResult forceDeleteLocked(String skill) {
    List<String> current = associations.findLinkedAgents(skill);
    for (String agent : current) {
      SkillAssociation link =
          associations.list(agent).stream()
              .filter(candidate -> candidate.skillName().equals(skill))
              .findFirst()
              .orElseThrow(() -> new SkillConflictException("Association changed during delete"));
      if (link.linkStatus() != LinkStatus.VALID
          || !link.rawTarget().equals(SkillAssociationService.expectedTarget(skill).toString())) {
        throw new SkillConflictException("Association changed during delete");
      }
    }
    List<String> removed = new java.util.ArrayList<>();
    try {
      for (String agent : current) {
        associations.unlink(agent, skill);
        removed.add(agent);
      }
      archive(skill, true, current);
      return new DeleteResult(skill, true, current, true);
    } catch (RuntimeException error) {
      compensateLinks(skill, removed);
      throw error;
    }
  }

  private void compensateLinks(String skill, List<String> agents) {
    for (String agent : agents) {
      Path link = workspaceRoot.resolve("agents").resolve(agent).resolve("skills").resolve(skill);
      if (Files.exists(link, NOFOLLOW)) {
        continue;
      }
      try {
        Files.createSymbolicLink(link, SkillAssociationService.expectedTarget(skill));
      } catch (IOException ignored) {
        // Best effort only; never overwrite an external replacement.
      }
    }
  }

  private void archive(String skill, boolean forced, List<String> affectedAgents) {
    Path source = publicRoot.resolve(skill);
    if (!Files.exists(source, NOFOLLOW)) {
      throw new NoSuchElementException("Skill does not exist: " + skill);
    }
    try {
      requireRealDirectory(archiveRoot, "Skill archive root is unavailable");
      Path event = archiveRoot.resolve(clock.instant().toEpochMilli() + "-" + UUID.randomUUID());
      Files.createDirectory(event);
      Path metadata = event.resolve("archive.yml");
      Files.write(
          metadata, archiveYaml(skill, forced, affectedAgents), StandardOpenOption.CREATE_NEW);
      try {
        requireSameFileStore(source, event);
        atomicMove(source, event.resolve("package"));
      } catch (RuntimeException error) {
        Files.deleteIfExists(metadata);
        Files.deleteIfExists(event);
        throw error;
      }
    } catch (IOException error) {
      throw new UncheckedIOException("Skill package could not be archived", error);
    }
  }

  private byte[] archiveYaml(String skill, boolean forced, List<String> affectedAgents) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schemaVersion", 1);
    data.put("skill", skill);
    data.put("source", catalog.get(skill).source().name().toLowerCase(Locale.ROOT));
    data.put("forced", forced);
    data.put("deletedAt", Instant.now(clock).toString());
    data.put("affectedAgents", affectedAgents.stream().distinct().sorted().toList());
    data.put("originalRelativePath", "skills/" + skill);
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    return new Yaml(options).dump(data).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  }

  private void requireNoNameConflict(String skill) {
    requireRealDirectory(publicRoot, "Public Skill root is unavailable");
    String wanted = Normalizer.normalize(skill, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(publicRoot)) {
      for (Path entry : entries) {
        String existing = String.valueOf(entry.getFileName());
        String key = Normalizer.normalize(existing, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        if (key.equals(wanted)) {
          throw new SkillConflictException();
        }
      }
    } catch (SkillConflictException error) {
      throw error;
    } catch (IOException error) {
      throw new UncheckedIOException("Public Skill root could not be inspected", error);
    }
  }

  private static void requireSameFileStore(Path source, Path targetParent) {
    try {
      if (!Files.getFileStore(source).equals(Files.getFileStore(targetParent))) {
        throw new IllegalStateException("Atomic Skill move requires one filesystem");
      }
    } catch (IOException error) {
      throw new UncheckedIOException("Skill filesystem could not be inspected", error);
    }
  }

  private static void atomicMove(Path source, Path target) {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (FileAlreadyExistsException error) {
      throw new SkillConflictException();
    } catch (AtomicMoveNotSupportedException error) {
      throw new IllegalStateException("Atomic Skill move is unavailable", error);
    } catch (IOException error) {
      throw new UncheckedIOException("Atomic Skill move failed", error);
    }
  }

  private static void requireRealDirectory(Path path, String message) {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(message);
    }
    try {
      path.toRealPath();
    } catch (IOException error) {
      throw new UncheckedIOException(message, error);
    }
  }

  private static String reason(Throwable error) {
    if (error instanceof SkillValidationException validation) {
      return validation.code().name();
    }
    if (error instanceof SkillImportException imported) {
      return imported.reasonCode();
    }
    return error.getClass().getSimpleName();
  }
}
