package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentName;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Fixed, safely serialized metadata for one completed Skill archive event. */
public record ArchivedSkill(
    int schemaVersion,
    String agent,
    String skill,
    SkillSource source,
    Instant deletedAt,
    String originalRelativePath) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  private static final DateTimeFormatter EVENT_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

  public ArchivedSkill {
    if (schemaVersion != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported Skill archive schema: " + schemaVersion);
    }
    agent = AgentName.parse(agent).value();
    skill = requireDirectoryName(skill);
    source = Objects.requireNonNull(source, "source");
    deletedAt = Objects.requireNonNull(deletedAt, "deletedAt");
    String expectedPath = "agents/" + agent + "/skills/" + skill;
    if (!expectedPath.equals(originalRelativePath)) {
      throw new IllegalArgumentException("originalRelativePath does not match the archived Skill");
    }
  }

  public static ArchivedSkill create(
      String agent, String skill, SkillSource source, Instant deletedAt) {
    String safeAgent = AgentName.parse(agent).value();
    String safeSkill = requireDirectoryName(skill);
    return new ArchivedSkill(
        CURRENT_SCHEMA_VERSION,
        safeAgent,
        safeSkill,
        source,
        deletedAt,
        "agents/" + safeAgent + "/skills/" + safeSkill);
  }

  /** Uses time only for readability; UUID supplies uniqueness even within the same second. */
  public String eventDirectoryName(UUID eventId) {
    return EVENT_TIME.format(deletedAt) + "-" + Objects.requireNonNull(eventId, "eventId");
  }

  /** Serializes a fixed map, so user-controlled values can never introduce additional YAML keys. */
  public byte[] toYamlBytes() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("schemaVersion", schemaVersion);
    values.put("agent", agent);
    values.put("skill", skill);
    values.put("source", source.name().toLowerCase(Locale.ROOT));
    values.put("deletedAt", deletedAt.toString());
    values.put("originalRelativePath", originalRelativePath);

    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(false);
    options.setSplitLines(false);
    return new Yaml(options).dump(values).getBytes(StandardCharsets.UTF_8);
  }

  private static String requireDirectoryName(String value) {
    if (value == null
        || value.isBlank()
        || value.equals(".")
        || value.equals("..")
        || value.indexOf('/') >= 0
        || value.indexOf('\\') >= 0
        || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("skill must be one safe directory segment");
    }
    return value;
  }
}
