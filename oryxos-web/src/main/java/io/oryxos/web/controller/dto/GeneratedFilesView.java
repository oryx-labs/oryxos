package io.oryxos.web.controller.dto;

import io.oryxos.core.agent.GeneratedAgentDraft;
import java.util.List;
import java.util.Map;

/** 生成结果视图：{相对路径 → 文件内容}，返回前端预览可改，满意再保存。 */
public record GeneratedFilesView(
    Map<String, String> files,
    List<String> requiredSkills,
    List<String> suggestedSkills,
    List<String> bindingSkills) {

  public GeneratedFilesView {
    files = files == null ? Map.of() : Map.copyOf(files);
    requiredSkills = requiredSkills == null ? List.of() : List.copyOf(requiredSkills);
    suggestedSkills = suggestedSkills == null ? List.of() : List.copyOf(suggestedSkills);
    bindingSkills = bindingSkills == null ? List.of() : List.copyOf(bindingSkills);
  }

  public static GeneratedFilesView from(GeneratedAgentDraft draft) {
    return new GeneratedFilesView(
        draft.files(), draft.requiredSkills(), draft.suggestedSkills(), draft.bindingSkills());
  }
}
