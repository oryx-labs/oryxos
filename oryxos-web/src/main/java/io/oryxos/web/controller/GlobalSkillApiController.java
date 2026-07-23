package io.oryxos.web.controller;

import io.oryxos.core.skill.GlobalSkillManagementService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.PublicSkillDetailView;
import io.oryxos.web.controller.dto.PublicSkillSummaryView;
import io.oryxos.web.controller.dto.UpdateSkillContentRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Workspace-wide public Skill CRUD and Agent association API. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "The shared auth filter protects this management endpoint; the injected service is an"
            + " intentional shared Spring collaborator.")
@RestController
@RequestMapping("/api/v1/skills")
public final class GlobalSkillApiController {

  private final GlobalSkillManagementService service;

  public GlobalSkillApiController(GlobalSkillManagementService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse<List<PublicSkillSummaryView>> list() {
    return ApiResponse.ok(
        service.list().stream()
            .map(
                descriptor ->
                    PublicSkillSummaryView.from(
                        descriptor, service.associatedAgents(descriptor.directoryName())))
            .toList());
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<PublicSkillDetailView> importSkill(
      @RequestPart(name = "file", required = false) MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("file part is required and must not be empty");
    }
    try (InputStream input = file.getInputStream()) {
      var descriptor = service.importSkill(input, file.getOriginalFilename());
      return ApiResponse.ok(detail(descriptor.directoryName()));
    }
  }

  @GetMapping("/{skillName}")
  public ApiResponse<PublicSkillDetailView> get(@PathVariable String skillName) {
    return ApiResponse.ok(detail(skillName));
  }

  @PutMapping("/{skillName}")
  public ApiResponse<PublicSkillDetailView> update(
      @PathVariable String skillName,
      @RequestBody(required = false) UpdateSkillContentRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("content is required");
    }
    service.updateContent(skillName, request.content());
    return ApiResponse.ok(detail(skillName));
  }

  @DeleteMapping("/{skillName}")
  public ApiResponse<Void> delete(@PathVariable String skillName) {
    service.delete(skillName);
    return ApiResponse.ok(null);
  }

  @PutMapping("/{skillName}/agents/{agentName}")
  public ApiResponse<PublicSkillDetailView> associate(
      @PathVariable String skillName, @PathVariable String agentName) {
    service.associate(skillName, agentName);
    return ApiResponse.ok(detail(skillName));
  }

  @DeleteMapping("/{skillName}/agents/{agentName}")
  public ApiResponse<PublicSkillDetailView> dissociate(
      @PathVariable String skillName, @PathVariable String agentName) {
    service.dissociate(skillName, agentName);
    return ApiResponse.ok(detail(skillName));
  }

  private PublicSkillDetailView detail(String skillName) {
    return PublicSkillDetailView.from(
        service.get(skillName),
        service.getContent(skillName),
        service.associatedAgents(skillName),
        service.availableAgents());
  }
}
