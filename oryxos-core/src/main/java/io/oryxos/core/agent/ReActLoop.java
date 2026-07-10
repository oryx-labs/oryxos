package io.oryxos.core.agent;

import io.oryxos.core.ToolResult;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.session.Session;

/**
 * ReAct 主循环——Agent 的大脑（宪法 I：自实现，不用框架 Agent 封装）。
 *
 * <p>循环只做调度：转圈、判停、把每轮结果攒起来。拼上下文归 {@link PromptBuilder}、 调模型归 {@link ProviderService}、执行工具归 {@link
 * ToolExecutor}——循环里塞的东西越少越不容易出 bug。
 */
public class ReActLoop {

  /** 转满最大轮数的强制收尾答复（课件字面量，harness 断言点）。 */
  static final String MAX_ITERATIONS_REPLY = "达到最大轮数，已停止";

  private final PromptBuilder promptBuilder;
  private final ProviderService providerService;
  private final ToolExecutor toolExecutor;

  public ReActLoop(
      PromptBuilder promptBuilder, ProviderService providerService, ToolExecutor toolExecutor) {
    this.promptBuilder = promptBuilder;
    this.providerService = providerService;
    this.toolExecutor = toolExecutor;
  }

  public String run(Session session, String userMessage, Profile profile) {
    session.appendUser(userMessage);
    // 最大轮数兜底（坑一）：模型可能反复要调工具永不收敛，转够强制退出
    for (int i = 0; i < profile.settings().maxIterations(); i++) {
      ProviderRequest prompt = promptBuilder.build(session, profile);
      // sessionId 随调用传递：llm_calls 审计按 session 关联
      ProviderResponse response = providerService.chat(session.sessionId(), profile, prompt);
      // 先累积再判停（坑三）：每一轮都留痕，事后可审计、下一轮接得上
      session.appendAssistant(response);
      if (!response.hasToolCalls()) {
        return response.text() == null ? "" : response.text();
      }
      for (ToolCallRequest call : response.toolCalls()) {
        // 执行权只在 ToolExecutor（宪法 I/II）；失败结果同样回填，模型下一轮自行决定
        ToolResult result = toolExecutor.execute(session.sessionId(), call);
        session.appendToolResult(call.name(), result);
      }
    }
    return MAX_ITERATIONS_REPLY;
  }
}
