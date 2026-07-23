package io.oryxos.core.agent;

import io.oryxos.core.OryxTool;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 每轮 Prompt 组装者：四部分按固定顺序拼成一段文本（课件：拼成"一段"Prompt）。
 *
 * <p>①system（ContextLoader 供给 + 日期时间行）②长期记忆（22 节 Memory 就位前恒空跳过） ③会话历史（只留最近 N 轮，一轮 = 一条 user
 * 消息及其后全部消息）④可用工具——不进文本， 经 {@link ProviderRequest#availableTools()} 传递，schema 挂载由 16 节
 * ToolSchemaAdapter 单点负责。
 */
public class PromptBuilder {

  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final ContextLoader contextLoader;
  private final Map<String, OryxTool> tools;
  private final MemoryService memoryService;
  private final Clock clock;

  public PromptBuilder(ContextLoader contextLoader, Map<String, OryxTool> tools) {
    this(contextLoader, tools, null, Clock.systemDefaultZone());
  }

  public PromptBuilder(ContextLoader contextLoader, Map<String, OryxTool> tools, Clock clock) {
    this(contextLoader, tools, null, clock);
  }

  /** 22 节起：注入 MemoryService，长期记忆段由它供给；memoryService 为 null 时该段留空（向后兼容旧构造）。 */
  public PromptBuilder(
      ContextLoader contextLoader,
      Map<String, OryxTool> tools,
      MemoryService memoryService,
      Clock clock) {
    this.contextLoader = contextLoader;
    this.tools = Map.copyOf(tools);
    this.memoryService = memoryService;
    this.clock = clock;
  }

  public ProviderRequest build(Session session, Profile profile) {
    // ①+② systemPrompt：不随对话变的部分——ContextLoader 供给 + 日期时间行 + 长期记忆
    StringBuilder system = new StringBuilder();
    // 每次重新经 ContextLoader 读文件（无缓存铁律在 ContextLoader 内保证）
    system.append(contextLoader.load(profile));
    // 模型自己不知道今天几号——定时场景里的"今天"全靠这一行
    system.append("当前日期时间：").append(DATE_TIME.format(ZonedDateTime.now(clock))).append('\n');
    // 长期记忆：经门面注入（核心区全量 + 归档区截断后）；未装配 Memory 时该段留空
    if (memoryService != null) {
      system.append(memoryService.buildContext(session)).append('\n');
    }
    // ③ 会话历史：结构化透传（不再拍平成文本），保留 assistant tool_calls / tool tool_call_id 配对——
    //    多步 ReAct 里模型才能看出工具已调过、继续下一步（31 节修复）；仍只留最近 N 轮（坑二）
    List<Message> history = recentTurns(session, profile.settings().maxHistoryTurns());
    // ④ 工具列表经 availableTools 传递，Provider 侧翻译成 Function Calling 格式
    return new ProviderRequest(system.toString(), history, resolveTools(profile));
  }

  /** 截断以轮为界整体保留/丢弃：一轮 = 一条 user 消息及其后到下一条 user 消息前的全部消息。 */
  private static List<Message> recentTurns(Session session, int maxHistoryTurns) {
    List<Message> messages = session.messages();
    List<Integer> turnStarts = new ArrayList<>();
    for (int i = 0; i < messages.size(); i++) {
      if (Message.ROLE_USER.equals(messages.get(i).role())) {
        turnStarts.add(i);
      }
    }
    if (turnStarts.size() <= maxHistoryTurns) {
      return messages;
    }
    int from = turnStarts.get(turnStarts.size() - maxHistoryTurns);
    return messages.subList(from, messages.size());
  }

  private List<OryxTool> resolveTools(Profile profile) {
    List<OryxTool> resolved = new ArrayList<>();
    for (String name : profile.tools()) {
      OryxTool tool = tools.get(name);
      if (tool != null) {
        resolved.add(tool);
      }
    }
    return resolved;
  }
}
