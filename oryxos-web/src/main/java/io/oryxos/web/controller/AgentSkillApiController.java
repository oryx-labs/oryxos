package io.oryxos.web.controller;

import io.oryxos.core.skill.SkillManagementService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.SetSkillEnabledRequest;
import io.oryxos.web.controller.dto.SkillDetailView;
import io.oryxos.web.controller.dto.SkillSummaryView;
import io.oryxos.web.controller.dto.SkillUploadRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
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

/** Thin HTTP adapter for managing the private Skill packages owned by one Agent. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "Core-stage management endpoints are internal-network APIs; the injected service is a"
            + " shared immutable collaborator.")
@RestController
@RequestMapping("/api/v1/agents/{agentName}/skills")
public final class AgentSkillApiController {

  private final SkillManagementService service;

  public AgentSkillApiController(SkillManagementService service) {
    this.service = service;
  }

  @Operation(summary = "List one Agent's managed Skills")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Managed Skill summaries"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Agent not found",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class)))
  })
  @GetMapping
  public ApiResponse<List<SkillSummaryView>> list(@PathVariable("agentName") String agentName) {
    List<SkillSummaryView> skills =
        service.list(agentName).stream()
            .map(SkillSummaryView::from)
            .sorted(Comparator.comparing(SkillSummaryView::directoryName))
            .toList();
    return ApiResponse.ok(skills);
  }

  @Operation(summary = "Get one managed Skill")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Managed Skill details"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Unsafe member name",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Agent or Skill not found",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class)))
  })
  @GetMapping("/{skillName}")
  public ApiResponse<SkillDetailView> get(
      @PathVariable("agentName") String agentName, @PathVariable("skillName") String skillName) {
    return ApiResponse.ok(
        SkillDetailView.from(service.get(agentName, requireSafeMember(skillName))));
  }

  @Operation(summary = "Import a managed Skill ZIP")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content =
          @Content(
              mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
              schema = @Schema(implementation = SkillUploadRequest.class)))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Imported Skill details"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Invalid upload or Skill package",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Agent not found",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "Skill name conflict",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "413",
        description = "Upload or expanded package too large",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        description = "Atomic publication or unexpected I/O failed",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class)))
  })
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<SkillDetailView> importSkill(
      @PathVariable("agentName") String agentName,
      @RequestPart(name = "file", required = false) MultipartFile file)
      throws IOException {
    if (file == null || file.isEmpty()) {
      throw new IllegalArgumentException("file part is required and must not be empty");
    }
    try (InputStream input = file.getInputStream()) {
      return ApiResponse.ok(
          SkillDetailView.from(service.importSkill(agentName, input, file.getOriginalFilename())));
    }
  }

  @Operation(summary = "Enable or disable one managed Skill")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Updated Skill details"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Invalid boolean, member name, package or catalog budget",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Agent or Skill not found",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class)))
  })
  @PutMapping("/{skillName}")
  public ApiResponse<SkillDetailView> setEnabled(
      @PathVariable("agentName") String agentName,
      @PathVariable("skillName") String skillName,
      @RequestBody(required = false) SetSkillEnabledRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("enabled is required and must be a JSON boolean");
    }
    String member = requireSafeMember(skillName);
    return ApiResponse.ok(
        SkillDetailView.from(service.setEnabled(agentName, member, request.requireEnabled())));
  }

  @Operation(summary = "Archive and remove one managed Skill")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Skill archived"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "Unsafe member name",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "Agent or Skill not found",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class))),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        description = "Archive could not be completed atomically",
        content =
            @Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = ApiResponse.class)))
  })
  @DeleteMapping("/{skillName}")
  public ApiResponse<Void> delete(
      @PathVariable("agentName") String agentName, @PathVariable("skillName") String skillName) {
    service.delete(agentName, requireSafeMember(skillName));
    return ApiResponse.ok(null);
  }

  private static String requireSafeMember(String value) {
    if (value == null
        || value.isBlank()
        || value.equals(".")
        || value.equals("..")
        || value.indexOf('/') >= 0
        || value.indexOf('\\') >= 0
        || value.codePoints().anyMatch(AgentSkillApiController::isUnsafeMemberCodePoint)) {
      throw new IllegalArgumentException("Skill name must be one safe directory segment");
    }
    return value;
  }

  private static boolean isUnsafeMemberCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isISOControl(codePoint)
        || type == Character.FORMAT
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }
}
