package io.oryxos.storage;

import io.oryxos.core.cluster.CoordinationStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;

/**
 * 共享数据库协调实现（026 默认档，零新增运维组件）：CAS 三式——INSERT 捕唯一约束冲突（跨库统一 异常翻译，零方言）→ 条件 UPDATE 抢过期 → false；owner
 * 条件续租/释放。时间基准恒取数据库 CURRENT_TIMESTAMP，副本本地时钟只用于间隔调度（NTP 秒级漂移不影响正确性）。
 */
public class JpaCoordinationStore implements CoordinationStore {

  private final TurnLeaseRepository turnLeases;
  private final ChannelEventReceiptRepository receipts;
  private final ChannelLeaseRepository channelLeases;
  private final InstanceHeartbeatRepository instances;
  private final ScheduledTaskRepository scheduledTasks;
  private final WorkspaceVersionRepository workspaceVersions;
  private final KnowledgeBuildClaimRepository buildClaims;
  private final KnowledgeGenerationRepository generations;

  /** 抢过期 turn 时记下前任的未完结 execution（供补失败留痕）；单线程调用语义（认领在会话锁内）。 */
  private final ThreadLocal<Long> lastReclaimedExecution = new ThreadLocal<>();

  /** commitGeneration 的持有校验续租时长：仅覆盖提交自身的毫秒级窗口，提交末尾即释放。 */
  private static final long COMMIT_HOLD_SECONDS = 30L;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "注入的 Repository 是 Spring 共享 Bean，本就不应防御性拷贝。")
  public JpaCoordinationStore(
      TurnLeaseRepository turnLeases,
      ChannelEventReceiptRepository receipts,
      ChannelLeaseRepository channelLeases,
      InstanceHeartbeatRepository instances,
      ScheduledTaskRepository scheduledTasks,
      WorkspaceVersionRepository workspaceVersions,
      KnowledgeBuildClaimRepository buildClaims,
      KnowledgeGenerationRepository generations) {
    this.turnLeases = turnLeases;
    this.receipts = receipts;
    this.channelLeases = channelLeases;
    this.instances = instances;
    this.scheduledTasks = scheduledTasks;
    this.workspaceVersions = workspaceVersions;
    this.buildClaims = buildClaims;
    this.generations = generations;
  }

  @Override
  public boolean tryAcquireTurn(String sessionId, String owner, Duration ttl) {
    lastReclaimedExecution.remove();
    Instant now = dbNow();
    Instant until = now.plus(ttl);
    try {
      turnLeases.insertLease(sessionId, owner, until, now);
      return true;
    } catch (DataAccessException occupied) {
      rethrowUnlessConstraintViolation(occupied);
      // 已有持有者：记下过期前任的 execution（若有）再尝试抢过期
      Optional<TurnLeaseEntity> current = turnLeases.findBySessionId(sessionId);
      Long danglingExecution =
          current
              .filter(l -> l.getLeaseUntil().isBefore(now))
              .map(TurnLeaseEntity::getAgentExecutionId)
              .orElse(null);
      if (turnLeases.takeExpired(sessionId, owner, until, now) == 1) {
        lastReclaimedExecution.set(danglingExecution);
        return true;
      }
      return false;
    }
  }

  @Override
  public boolean renewTurn(String sessionId, String owner, Duration ttl) {
    Instant until = dbNow().plus(ttl);
    return turnLeases.renew(sessionId, owner, until) == 1;
  }

  @Override
  public void releaseTurn(String sessionId, String owner) {
    turnLeases.release(sessionId, owner);
  }

  @Override
  public void attachExecution(String sessionId, String owner, long agentExecutionId) {
    turnLeases.attachExecution(sessionId, owner, agentExecutionId);
  }

  @Override
  public Long lastReclaimedExecutionId() {
    return lastReclaimedExecution.get();
  }

  @Override
  public boolean claimFireTime(String scheduleId, Instant fireTime, String owner) {
    return scheduledTasks.claimFireTime(scheduleId, fireTime, owner) == 1;
  }

  @Override
  public boolean markReceipt(String receiptKey) {
    try {
      receipts.insertReceipt(receiptKey, dbNow());
      return true;
    } catch (DataAccessException duplicate) {
      rethrowUnlessConstraintViolation(duplicate);
      return false;
    }
  }

  @Override
  public void unmarkReceipt(String receiptKey) {
    receipts.deleteReceipt(receiptKey);
  }

  @Override
  public boolean tryAcquireChannel(String channelName, String owner, Duration ttl) {
    Instant now = dbNow();
    Instant until = now.plus(ttl);
    try {
      channelLeases.insertLease(channelName, owner, until);
      return true;
    } catch (DataAccessException occupied) {
      rethrowUnlessConstraintViolation(occupied);
      return channelLeases.takeExpired(channelName, owner, until, now) == 1;
    }
  }

  @Override
  public boolean renewChannel(String channelName, String owner, Duration ttl) {
    Instant until = dbNow().plus(ttl);
    return channelLeases.renew(channelName, owner, until) == 1;
  }

  @Override
  public void releaseChannel(String channelName, String owner) {
    channelLeases.release(channelName, owner);
  }

  @Override
  public void heartbeat(String instanceId, long epoch) {
    Instant now = dbNow();
    InstanceHeartbeat row =
        instances
            .findById(instanceId)
            .orElseGet(
                () -> {
                  InstanceHeartbeat fresh = new InstanceHeartbeat();
                  fresh.setInstanceId(instanceId);
                  fresh.setStartedAt(now);
                  return fresh;
                });
    row.setEpoch(epoch);
    row.setLastHeartbeatAt(now);
    instances.save(row);
  }

  @Override
  public List<InstanceInfo> listInstances() {
    return instances.findAll().stream()
        .map(
            i ->
                new InstanceInfo(
                    i.getInstanceId(), i.getEpoch(), i.getStartedAt(), i.getLastHeartbeatAt()))
        .toList();
  }

  @Override
  public List<TurnLeaseInfo> activeTurnLeases() {
    Instant now = dbNow();
    return turnLeases.findByLeaseUntilAfter(now).stream()
        .map(
            l ->
                new TurnLeaseInfo(
                    l.getSessionId(), l.getOwner(), l.getLeaseUntil(), l.getAcquiredAt()))
        .toList();
  }

  @Override
  public void purgeExpired(Duration receiptTtl, Duration instanceDeadAfter) {
    Instant now = dbNow();
    receipts.deleteOlderThan(now.minus(receiptTtl));
    instances.deleteOlderThan(now.minus(instanceDeadAfter));
  }

  @Override
  public void bumpWorkspaceVersion(String domain, String owner) {
    if (!WORKSPACE_DOMAINS.contains(domain)) {
      throw new IllegalArgumentException("未知的工作区域: " + domain);
    }
    workspaceVersions.bump(domain, owner, dbNow());
  }

  @Override
  public java.util.Map<String, Long> workspaceVersions() {
    return workspaceVersions.findAll().stream()
        .collect(
            java.util.stream.Collectors.toMap(
                WorkspaceVersionEntity::getDomain, WorkspaceVersionEntity::getVersion));
  }

  @Override
  public boolean tryAcquireIndexBuild(String kbName, long generation, String owner, Duration ttl) {
    Instant now = dbNow();
    Instant until = now.plus(ttl);
    try {
      buildClaims.insertClaim(kbName, owner, until, generation);
      return true;
    } catch (DataAccessException occupied) {
      rethrowUnlessConstraintViolation(occupied);
      return buildClaims.takeExpired(kbName, owner, until, generation, now) == 1;
    }
  }

  @Override
  public boolean renewIndexBuild(String kbName, String owner, Duration ttl) {
    Instant until = dbNow().plus(ttl);
    return buildClaims.renew(kbName, owner, until) == 1;
  }

  @Override
  public void releaseIndexBuild(String kbName, String owner) {
    buildClaims.release(kbName, owner);
  }

  @Override
  public boolean commitGeneration(String kbName, long generation, String owner) {
    Instant now = dbNow();
    // 校验仍持有（rowcount 语义的条件续租即校验）；已被接管则不提交、旧代不动
    if (buildClaims.renew(kbName, owner, now.plusSeconds(COMMIT_HOLD_SECONDS)) != 1) {
      return false;
    }
    KnowledgeGenerationEntity row =
        generations
            .findById(kbName)
            .orElseGet(
                () -> {
                  KnowledgeGenerationEntity fresh = new KnowledgeGenerationEntity();
                  fresh.setKbName(kbName);
                  return fresh;
                });
    row.setCommittedGeneration(generation);
    row.setUpdatedAt(now);
    generations.save(row);
    workspaceVersions.bump("knowledge", owner, now);
    buildClaims.release(kbName, owner);
    return true;
  }

  @Override
  public java.util.OptionalLong committedGeneration(String kbName) {
    return generations
        .findById(kbName)
        .map(g -> java.util.OptionalLong.of(g.getCommittedGeneration()))
        .orElse(java.util.OptionalLong.empty());
  }

  /** DB CURRENT_TIMESTAMP 的驱动类型适配：PG 给时间类型，SQLite 给 'YYYY-MM-DD HH:MM:SS'（UTC）字符串。 */
  private Instant dbNow() {
    Object raw = turnLeases.dbNowRaw();
    if (raw instanceof Instant instant) {
      return instant;
    }
    if (raw instanceof java.sql.Timestamp timestamp) {
      return timestamp.toInstant();
    }
    if (raw instanceof java.time.OffsetDateTime offset) {
      return offset.toInstant();
    }
    if (raw instanceof String text) {
      return java.time.LocalDateTime.parse(text.replace(' ', 'T'))
          .toInstant(java.time.ZoneOffset.UTC);
    }
    throw new IllegalStateException("无法识别的数据库时间类型: " + raw.getClass());
  }

  /**
   * 约束违规判定：PG 经 Spring 翻译为 DataIntegrityViolationException；SQLite community 方言把 SQLITE_CONSTRAINT
   * 包成 JpaSystemException（翻译缺口，契约测试实证）——按 cause 链 SQLState 23xxx 或 SQLITE_CONSTRAINT
   * 标识兜底。非约束违规的异常原样上抛（绝不吞真错误）。
   */
  private static void rethrowUnlessConstraintViolation(DataAccessException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof org.springframework.dao.DataIntegrityViolationException
          || cause instanceof org.hibernate.exception.ConstraintViolationException) {
        return;
      }
      if (cause instanceof java.sql.SQLException sql) {
        String state = sql.getSQLState();
        boolean integrityState = state != null && state.startsWith("23");
        boolean sqliteConstraint = String.valueOf(sql.getMessage()).contains("SQLITE_CONSTRAINT");
        if (integrityState || sqliteConstraint) {
          return;
        }
      }
    }
    throw e;
  }
}
