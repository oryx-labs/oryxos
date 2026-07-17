package io.oryxos.core.session;

import java.util.List;
import java.util.Optional;

/**
 * 会话管理契约（17 节前向定义仅 save，18 节按课件补全为三方法）。
 *
 * <p>session_id 由三元组 channel+user+profile 联合唯一生成，拼接只允许发生在实现内部一处（H4④）—— 所有入口（CLI 传 "cli"、Web 传
 * "web"、定时传 "scheduler"）只提供三元组，不自己拼字符串： 两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的历史。
 */
public interface SessionManager {

  /** 同一三元组幂等返回同一会话（含已恢复的历史）；未命中则新建 active 记录。 */
  Session getOrCreate(String channel, String userId, String profileName);

  Optional<Session> get(String sessionId);

  void save(Session session);

  /**
   * 归档一个会话（status→archived、记 archived_at）。26 节 DELETE /sessions/{id} 接线；sessions 的
   * status/archived_at 列自 18 节 data model 即在，此前未接对外入口。
   *
   * @return {@code true} 已归档，{@code false} 会话不存在（调用方据此返 404，核心层不依赖 Web 异常）
   */
  boolean archive(String sessionId);

  /**
   * 列出最近会话的摘要（按 last_active_at 倒序取前 {@code limit} 条），不含对话正文——供 27 节 GET /api/v1/sessions
   * 与管理台"会话"列表。返回摘要投影而非领域 {@link Session}：领域对象不带 channel/status/时间戳等展示字段。
   */
  List<SessionSummary> listRecent(int limit);
}
