package io.oryxos.core.agent;

import io.oryxos.core.OryxTool;
import io.oryxos.core.context.ContextLoader;
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
  private final Clock clock;

  public PromptBuilder(ContextLoader contextLoader, Map<String, OryxTool> tools) {
    this(contextLoader, tools, Clock.systemDefaultZone());
  }

  public PromptBuilder(ContextLoader contextLoader, Map<String, OryxTool> tools, Clock clock) {
    this.contextLoader = contextLoader;
    this.tools = Map.copyOf(tools);
    this.clock = clock;
  }

  public ProviderRequest build(Session session, Profile profile) {
    StringBuilder content = new StringBuilder();
    // ① system：每次重新经 ContextLoader 读文件（无缓存铁律在 ContextLoader 内保证）
    content.append(contextLoader.load(profile));
    // 模型自己不知道今天几号——定时场景里的"今天"全靠这一行
    content.append("当前日期时间：").append(DATE_TIME.format(ZonedDateTime.now(clock))).append('\n');
    // ② 长期记忆：22 节 Memory 就位前恒空跳过，四段顺序框架先立好
    // ③ 会话历史：只留最近 N 轮（坑二——不截断，转几轮 context 就撑爆了）
    content.append("对话历史：\n");
    for (Message message : recentTurns(session, profile.settings().maxHistoryTurns())) {
      content.append(message.role()).append(": ").append(message.content()).append('\n');
    }
    // ④ 工具列表经 availableTools 传递，Provider 侧翻译成 Function Calling 格式
    return new ProviderRequest(content.toString(), resolveTools(profile));
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
