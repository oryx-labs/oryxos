package io.oryxos.core.session;

/**
 * 会话管理最小契约（17 节前向定义）：AgentService 正常结束后持久化会话。
 *
 * <p>18 节交付完整形态：getOrCreate(channel, userId, profileName)、session_id 拼接规则 （只允许在 SessionManager
 * 内拼接，H4④）与 JPA 存储实现。
 */
public interface SessionManager {

  void save(Session session);
}
