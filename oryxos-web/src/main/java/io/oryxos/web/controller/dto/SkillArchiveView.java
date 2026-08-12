package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.SkillArchive;
import java.time.Instant;

public record SkillArchiveView(String name, String archivedPath, Instant archivedAt) {
  public static SkillArchiveView from(SkillArchive archive) {
    return new SkillArchiveView(
        archive.name(), archive.archivedPath().toString(), archive.archivedAt());
  }
}
