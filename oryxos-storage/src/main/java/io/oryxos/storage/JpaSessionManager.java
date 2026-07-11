package io.oryxos.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.SessionManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SessionManager 的 JPA 实现。
 *
 * <p>session_id 拼接（channel:user:profile）只发生在本类 {@link #sessionId} 一处（H4④）——
 * 所有入口只提供三元组；两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的历史。 对话历史整体 JSON 序列化存 messages_json 一列，核心阶段不按条拆表。
 */
public class JpaSessionManager implements SessionManager {

  private final SessionRepository repository;
  private final ObjectMapper mapper = new ObjectMapper();

  public JpaSessionManager(SessionRepository repository) {
    this.repository = repository;
  }

  @Override
  public io.oryxos.core.session.Session getOrCreate(
      String channel, String userId, String profileName) {
    String id = sessionId(channel, userId, profileName);
    Optional<Session> existing = repository.findById(id);
    if (existing.isPresent()) {
      return restore(existing.get());
    }
    Session entity = new Session();
    entity.setSessionId(id);
    entity.setProfileName(profileName);
    entity.setChannel(channel);
    entity.setUserId(userId);
    entity.setStatus("active");
    repository.save(entity);
    return new io.oryxos.core.session.Session(id, profileName);
  }

  @Override
  public Optional<io.oryxos.core.session.Session> get(String sessionId) {
    return repository.findById(sessionId).map(this::restore);
  }

  @Override
  public void save(io.oryxos.core.session.Session session) {
    Session entity =
        repository
            .findById(session.sessionId())
            .orElseThrow(
                () -> new IllegalStateException("会话不存在，save 前必须先 getOrCreate: " + safeId(session)));
    entity.setMessagesJson(writeMessages(session.messages()));
    entity.setLastActiveAt(Instant.now());
    repository.save(entity);
  }

  /** 全库唯一的 session_id 拼接点。 */
  private static String sessionId(String channel, String userId, String profileName) {
    return channel + ":" + userId + ":" + profileName;
  }

  private io.oryxos.core.session.Session restore(Session entity) {
    return new io.oryxos.core.session.Session(
        entity.getSessionId(), entity.getProfileName(), readMessages(entity.getMessagesJson()));
  }

  private String writeMessages(List<Message> messages) {
    try {
      return mapper.writeValueAsString(messages);
    } catch (JsonProcessingException e) {
      // 历史写不进去等于会话丢失，必须显式失败而不是静默存空
      throw new IllegalStateException("对话历史序列化失败: " + e.getOriginalMessage(), e);
    }
  }

  private List<Message> readMessages(String messagesJson) {
    if (messagesJson == null || messagesJson.isBlank()) {
      return List.of();
    }
    try {
      return mapper.readValue(messagesJson, new TypeReference<List<Message>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("对话历史反序列化失败: " + e.getOriginalMessage(), e);
    }
  }

  private static String safeId(io.oryxos.core.session.Session session) {
    String id = session.sessionId();
    return id == null ? "" : id.replace('\r', '_').replace('\n', '_');
  }
}
