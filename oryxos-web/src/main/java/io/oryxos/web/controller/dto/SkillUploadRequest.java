package io.oryxos.web.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** OpenAPI-only shape for the required multipart Skill ZIP part. */
@Schema(
    name = "SkillUploadRequest",
    description = "Multipart request containing one Skill ZIP archive",
    requiredProperties = "file")
public record SkillUploadRequest(
    @Schema(
            description = "Skill ZIP archive",
            type = "string",
            format = "binary",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String file) {}
