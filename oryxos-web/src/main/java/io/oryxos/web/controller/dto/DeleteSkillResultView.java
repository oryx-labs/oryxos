package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.DeleteResult;
import java.util.List;

public record DeleteSkillResultView(
    String skillName, boolean forced, List<String> affectedAgents, boolean archived) {
  public static DeleteSkillResultView from(DeleteResult result) {
    return new DeleteSkillResultView(
        result.skillName(), result.forced(), result.affectedAgents(), result.archived());
  }
}
