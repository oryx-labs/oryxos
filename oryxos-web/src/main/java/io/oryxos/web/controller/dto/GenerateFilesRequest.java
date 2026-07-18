package io.oryxos.web.controller.dto;

/** POST /agents/{name}/generate-files 请求体：一句话需求，交大模型生成 AGENT.md 草稿（不落盘）。 */
public record GenerateFilesRequest(String description) {}
