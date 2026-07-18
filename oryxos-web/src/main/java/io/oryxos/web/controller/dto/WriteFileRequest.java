package io.oryxos.web.controller.dto;

/** POST /workspace/file 请求体：写某个工作区文件（path 相对 .oryxos，越界由控制器 400 挡住）。 */
public record WriteFileRequest(String path, String content) {}
