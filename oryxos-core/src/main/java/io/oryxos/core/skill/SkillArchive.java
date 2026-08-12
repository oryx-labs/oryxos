package io.oryxos.core.skill;

import java.nio.file.Path;
import java.time.Instant;

/** Result of moving one complete installed Skill directory into the archive. */
public record SkillArchive(String name, Path archivedPath, Instant archivedAt) {}
