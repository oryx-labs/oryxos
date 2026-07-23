package io.oryxos.web.controller.dto;

import java.util.List;

/**
 * POST /agents/{name}/generate-files 请求体：一句话需求 + 用户手动选的通知渠道（可空=不通知）+ 用户显式指定必启用的全局 Skill（可空=由模型自选），
 * 交大模型生成 AGENT.md（可含脚本/子指令）草稿（不落盘）。
 */
public record GenerateFilesRequest(String description, String notifyChannel, List<String> skills) {

  public GenerateFilesRequest {
    skills = skills == null ? List.of() : List.copyOf(skills);
  }
}
