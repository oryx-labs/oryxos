package io.oryxos.web.controller.dto;

import java.util.Map;

/** 生成结果视图：{相对路径 → 文件内容}，返回前端预览可改，满意再保存。 */
public record GeneratedFilesView(Map<String, String> files) {

  public GeneratedFilesView {
    files = files == null ? Map.of() : Map.copyOf(files);
  }
}
