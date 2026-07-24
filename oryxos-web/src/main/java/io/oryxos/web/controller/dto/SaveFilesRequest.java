package io.oryxos.web.controller.dto;

import java.util.List;
import java.util.Map;

/** POST /agents/{name}/files 请求体：保存文件，并补齐创建页显式选择的公共 Skill。 */
public record SaveFilesRequest(Map<String, String> files, List<String> skills) {

  public SaveFilesRequest {
    files = files == null ? Map.of() : Map.copyOf(files);
    skills = skills == null ? List.of() : List.copyOf(skills);
  }
}
