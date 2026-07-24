package io.oryxos.web.controller;

import io.oryxos.core.skill.PublicSkillCatalog;
import io.oryxos.core.skill.SkillAssociation;
import io.oryxos.core.skill.SkillAssociationManager;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.AgentSkillAssociationView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Deprecated reverse-route adapter; it delegates to the canonical link service. */
@Deprecated(forRemoval = false)
@RestController
@RequestMapping("/api/v1/skills/{skillName}/agents")
public final class SkillAssociationApiController {

  private final SkillAssociationManager associations;
  private final PublicSkillCatalog publicSkills;

  public SkillAssociationApiController(
      SkillAssociationManager associations, PublicSkillCatalog publicSkills) {
    this.associations = associations;
    this.publicSkills = publicSkills;
  }

  @PutMapping("/{agentName}")
  public ApiResponse<AgentSkillAssociationView> associate(
      @PathVariable String skillName, @PathVariable String agentName) {
    return ApiResponse.ok(view(associations.associate(agentName, skillName)));
  }

  @DeleteMapping("/{agentName}")
  public ApiResponse<AgentSkillAssociationView> dissociate(
      @PathVariable String skillName, @PathVariable String agentName) {
    return ApiResponse.ok(view(associations.unlink(agentName, skillName)));
  }

  private AgentSkillAssociationView view(SkillAssociation association) {
    return AgentSkillAssociationView.from(association, publicSkills.get(association.skillName()));
  }
}
