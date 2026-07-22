package io.oryxos.core.skill;

import java.nio.file.Path;
import java.util.Objects;

/** Fully validated Skill package retained in same-filesystem staging until atomic publication. */
public record PreparedSkill(
    Path stagingEventDir,
    Path packageRoot,
    String directoryName,
    SkillMetadata metadata,
    SkillOrigin origin,
    SkillContentValidator.ContentStats contentStats) {

  public PreparedSkill {
    stagingEventDir = absolutePath(stagingEventDir, "stagingEventDir");
    packageRoot = absolutePath(packageRoot, "packageRoot");
    if (!packageRoot.startsWith(stagingEventDir) || packageRoot.equals(stagingEventDir)) {
      throw new IllegalArgumentException("packageRoot must be contained by its staging event");
    }
    if (directoryName == null || directoryName.isBlank()) {
      throw new IllegalArgumentException("directoryName must not be blank");
    }
    directoryName = directoryName.strip();
    metadata = Objects.requireNonNull(metadata, "metadata");
    Path packageFileName = packageRoot.getFileName();
    if (!directoryName.equals(metadata.name())
        || packageFileName == null
        || !directoryName.equals(packageFileName.toString())) {
      throw new IllegalArgumentException("prepared Skill identity is inconsistent");
    }
    origin = Objects.requireNonNull(origin, "origin");
    contentStats = Objects.requireNonNull(contentStats, "contentStats");
  }

  private static Path absolutePath(Path path, String field) {
    Objects.requireNonNull(path, field);
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException(field + " must be absolute");
    }
    return path.normalize();
  }
}
