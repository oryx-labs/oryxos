package io.oryxos.web.controller;

import io.oryxos.core.skill.PublicSkillCatalog;
import io.oryxos.core.skill.PublicSkillDescriptor;
import io.oryxos.core.skill.SkillAssociation;
import io.oryxos.core.skill.SkillAssociationManager;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.AgentSkillAssociationView;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Canonical HTTP adapter for actual Agent-to-public-Skill association links. */
@RestController
@RequestMapping("/api/v1/agents/{agentName}/skills")
public final class AgentSkillApiController {

  private final SkillAssociationManager associations;
  private final PublicSkillCatalog publicSkills;

  public AgentSkillApiController(
      SkillAssociationManager associations, PublicSkillCatalog publicSkills) {
    this.associations = associations;
    this.publicSkills = publicSkills;
  }

  @Operation(summary = "List actual public Skill links for one Agent")
  @GetMapping
  public ApiResponse<List<AgentSkillAssociationView>> list(
      @PathVariable("agentName") String agentName) {
    return ApiResponse.ok(associations.list(agentName).stream().map(this::view).toList());
  }

  @Operation(summary = "Associate a public Skill using the canonical relative link")
  @PutMapping("/{skillName}")
  public ApiResponse<AgentSkillAssociationView> associate(
      @PathVariable("agentName") String agentName, @PathVariable("skillName") String skillName) {
    return ApiResponse.ok(view(associations.associate(agentName, skillName)));
  }

  @Operation(summary = "Remove only a verified canonical association link")
  @DeleteMapping("/{skillName}")
  public ApiResponse<AgentSkillAssociationView> unlink(
      @PathVariable("agentName") String agentName, @PathVariable("skillName") String skillName) {
    return ApiResponse.ok(view(associations.unlink(agentName, skillName)));
  }

  private AgentSkillAssociationView view(SkillAssociation association) {
    PublicSkillDescriptor skill;
    try {
      skill = publicSkills.get(association.skillName());
    } catch (NoSuchElementException error) {
      skill = null;
    }
    return AgentSkillAssociationView.from(association, skill);
  }
}
