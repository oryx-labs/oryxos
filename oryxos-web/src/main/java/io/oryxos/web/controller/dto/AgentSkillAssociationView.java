package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.PublicSkillDescriptor;
import io.oryxos.core.skill.SkillAssociation;
import io.oryxos.core.skill.SkillValidationError;
import java.util.Locale;

/** Canonical Agent association response derived only from the link inode and public package. */
public record AgentSkillAssociationView(
    String agentName,
    String skillName,
    String description,
    String link,
    String target,
    String linkStatus,
    String skillStatus,
    boolean discoverable,
    SkillValidationError error) {

  public static AgentSkillAssociationView from(
      SkillAssociation association, PublicSkillDescriptor skill) {
    return new AgentSkillAssociationView(
        association.agentName(),
        association.skillName(),
        skill == null || skill.metadata() == null ? null : skill.metadata().description(),
        association.relativeLinkPath(),
        association.rawTarget(),
        association.linkStatus().name().toLowerCase(Locale.ROOT),
        association.skillStatus() == null
            ? null
            : association.skillStatus().name().toLowerCase(Locale.ROOT),
        association.discoverable(),
        association.error());
  }
}
