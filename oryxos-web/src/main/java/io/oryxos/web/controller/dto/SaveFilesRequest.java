package io.oryxos.web.controller.dto;

import java.util.Map;

/** POST /agents/{name}/files 请求体：保存（可能被用户改过的）一组 Agent 文件，写入即生效。 */
public record SaveFilesRequest(Map<String, String> files) {

  public SaveFilesRequest {
    files = files == null ? Map.of() : Map.copyOf(files);
  }
}
