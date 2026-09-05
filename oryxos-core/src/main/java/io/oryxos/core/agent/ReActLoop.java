package io.oryxos.core.agent;

import io.oryxos.core.ToolResult;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import java.util.List;

/**
 * ReAct 主循环——Agent 的大脑（宪法 I：自实现，不用框架 Agent 封装）。
 *
 * <p>循环只做调度：转圈、判停、把每轮结果攒起来。拼上下文归 {@link PromptBuilder}、 调模型归 {@link ProviderService}、执行工具归 {@link
 * ToolExecutor}——循环里塞的东西越少越不容易出 bug。
 */
public class ReActLoop {

  /** 转满最大轮数的强制收尾答复（课件字面量，harness 断言点）。 */
  static final String MAX_ITERATIONS_REPLY = "达到最大轮数，已停止";

  /** 用户手动中断的收尾答复（进度流据此走取消态而非绿卡「回答」）。 */
  public static final String INTERRUPTED_REPLY = "已收到停止指令，推理已中断";

  private final PromptBuilder promptBuilder;
  private final ProviderService providerService;
  private final ToolExecutor toolExecutor;
  private final InterruptManager interruptManager;

  public ReActLoop(
      PromptBuilder promptBuilder, ProviderService providerService, ToolExecutor toolExecutor) {
    this(promptBuilder, providerService, toolExecutor, null);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "promptBuilder/toolExecutor/interruptManager 为 Runtime 装配的共享单例，"
              + "构造注入存同一引用正是意图（镜像既有 SuppressFBWarnings 模式）。")
  public ReActLoop(
      PromptBuilder promptBuilder,
      ProviderService providerService,
      ToolExecutor toolExecutor,
      InterruptManager interruptManager) {
    this.promptBuilder = promptBuilder;
    this.providerService = providerService;
    this.toolExecutor = toolExecutor;
    this.interruptManager = interruptManager;
  }

  public String run(Session session, String userMessage, Profile profile) {
    return run(session, userMessage, List.of(), profile, StreamListener.NOOP);
  }

  /**
   * 带流式观察者的运行（019）：listener 为 {@link StreamListener#NOOP} 时行为与原路径完全一致（走 {@code chat}）；否则 LLM 调用走
   * {@code chatStream} 逐段回调 token，工具执行前后回调 start/end。回调全部同步、 循环调度逻辑零变化（宪法 I 的定制点，宪法 VII 的同步模型不动摇）。
   */
  public String run(Session session, String userMessage, Profile profile, StreamListener listener) {
    return run(session, userMessage, List.of(), profile, listener);
  }

  /** 带入站图片等 media 的运行：media 写入本轮 user 消息，供 provider 映射为 multimodal UserMessage。 */
  public String run(
      Session session,
      String userMessage,
      List<Message.MediaPart> media,
      Profile profile,
      StreamListener listener) {
    session.appendUser(userMessage, media);
    // 最大轮数兜底（坑一）：模型可能反复要调工具永不收敛，转够强制退出
    for (int i = 0; i < profile.settings().maxIterations(); i++) {
      // 检查中断标志
      if (interruptManager != null && interruptManager.isInterrupted(session.sessionId())) {
        interruptManager.clear(session.sessionId());
        return INTERRUPTED_REPLY;
      }

      ProviderRequest prompt = promptBuilder.build(session, profile);
      // sessionId 随调用传递：llm_calls 审计按 session 关联；流式与否审计同口径（FR-012）
      ProviderResponse response =
          listener == StreamListener.NOOP
              ? providerService.chat(session.sessionId(), profile, prompt)
              : providerService.chatStream(session.sessionId(), profile, prompt, listener::onToken);
      // 先累积再判停（坑三）：每一轮都留痕，事后可审计、下一轮接得上
      session.appendAssistant(response);
      if (!response.hasToolCalls()) {
        return response.text() == null ? "" : response.text();
      }
      for (ToolCallRequest call : response.toolCalls()) {
        // 工具间隙再查一次，避免长工具链整段跑完才响应 /stop
        if (interruptManager != null && interruptManager.isInterrupted(session.sessionId())) {
          interruptManager.clear(session.sessionId());
          return INTERRUPTED_REPLY;
        }
        // 执行权只在 ToolExecutor（宪法 I/II）；失败结果同样回填，模型下一轮自行决定
        // 传 profile.name() 作为 Agent 名：记忆类工具据此落到本 Agent 专属 MEMORY.md（30 节）
        listener.onToolStart(call.name());
        ToolResult result = toolExecutor.execute(session.sessionId(), profile.name(), call);
        listener.onToolEnd(call.name(), result.success());
        session.appendToolResult(call, result);
      }
    }
    return MAX_ITERATIONS_REPLY;
  }
}
