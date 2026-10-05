package io.oryxos.core.cluster;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 多副本协调存储契约（026，跨模块契约放 core——依赖倒置）：一条 CAS 认领原语（插入捕唯一约束冲突 + 条件更新抢过期 + owner 条件续租/释放）套四类载体——turn
 * 租约、调度到点、事件回执、渠道属主，外加 实例心跳。默认实现基于共享数据库（JpaCoordinationStore，零新增运维组件）；Redis 等第二实现留缝不做。
 *
 * <p>时间判定一律以数据库时间为准（SELECT CURRENT_TIMESTAMP，两库通用），不是各副本本地时钟—— NTP 秒级漂移不影响正确性。全部方法的布尔返回值 = CAS
 * rowcount 语义（true 恰表示本调用赢得/保有）。
 */
public interface CoordinationStore {

  /** turn 租约认领：新插入或抢过期成功返回 true；被他人持有且未过期返回 false（调用方轮询等待）。 */
  boolean tryAcquireTurn(String sessionId, String owner, Duration ttl);

  /** turn 续租（fencing）：owner 仍持有则延长并返回 true；返回 false = 已被回收，执行方必须立即中止。 */
  boolean renewTurn(String sessionId, String owner, Duration ttl);

  /** turn 释放：只删自己的（owner 条件）。 */
  void releaseTurn(String sessionId, String owner);

  /** 悬空轮留痕辅助：把当前轮关联的 agent_executions id 写入租约行（无 execution 的路径不调用）。 */
  void attachExecution(String sessionId, String owner, long agentExecutionId);

  /**
   * 抢过期 turn 时返回前任租约的未完结 execution id（供补失败留痕）；由 tryAcquireTurn 的抢过期 路径内部记录，调用方经此取回。无前任或前任无
   * execution 返回 null。
   */
  Long lastReclaimedExecutionId();

  /**
   * 调度到点认领（恰好一次）：fireTime MUST 是 CronTrigger 计算的理论触发时刻（各副本对同一 cron 必然同值）——绝非墙钟。条件更新 rowcount==1 返回
   * true = 本副本执行本次到点。
   */
  boolean claimFireTime(String scheduleId, Instant fireTime, String owner);

  /** 入站事件判重：首见插入回执返回 true；唯一约束冲突返回 false = 重复，丢弃。 */
  boolean markReceipt(String receiptKey);

  /**
   * 撤销一条回执登记，与 {@link #markReceipt} 对称。
   *
   * <p>契约：撤销后同一 {@code receiptKey} 再次 {@link #markReceipt} 必须重新返回 true ——
   * 这是「占用之后没能给出答复」时把重推机会还给平台的前提。
   */
  void unmarkReceipt(String receiptKey);

  /** 渠道属主认领（独连型渠道，如企微）：语义同 tryAcquireTurn。 */
  boolean tryAcquireChannel(String channelName, String owner, Duration ttl);

  boolean renewChannel(String channelName, String owner, Duration ttl);

  void releaseChannel(String channelName, String owner);

  /** 实例心跳 upsert（instance_id 主键；epoch=进程启动毫秒）。 */
  void heartbeat(String instanceId, long epoch);

  /** 全部实例（含可能已死的——存活判定由调用方按 last_heartbeat_at 距今与 TTL 比较）。 */
  List<InstanceInfo> listInstances();

  /** 现役 turn 持有（谁在处理哪个会话，运维可见性）。 */
  List<TurnLeaseInfo> activeTurnLeases();

  /** 惰性清理：批删超龄回执与死实例行；心跳循环顺手调用。 */
  void purgeExpired(Duration receiptTtl, Duration instanceDeadAfter);

  /** 027：工作区版本号的合法域（表恒 4 行，运行期只 UPDATE 不增删行）。 */
  Set<String> WORKSPACE_DOMAINS = Set.of("agents", "skills", "personas", "knowledge");

  /**
   * 027 工作区版本总线：递增某域版本号（DB 侧 version = version + 1 原子自增）。 MUST 在文件落盘成功之后调用（读到新版本号 ⇒ 必能读到新内容，配合共享卷
   * close-to-open 一致性）。 未知域抛 IllegalArgumentException。
   */
  void bumpWorkspaceVersion(String domain, String owner);

  /** 027：一次读取全部域的当前版本号（轮询每 tick 调一次，单查询 4 行）。 */
  Map<String, Long> workspaceVersions();

  /** 027 知识索引构建认领：语义同 tryAcquireChannel（互斥 + 抢过期）；generation 为本次构建目标代次。 */
  boolean tryAcquireIndexBuild(String kbName, long generation, String owner, Duration ttl);

  /** 027：构建期间按批续租（fencing）。false = 认领已失（被接管），调用方必须立即中止并丢弃本代。 */
  boolean renewIndexBuild(String kbName, String owner, Duration ttl);

  /** 027：释放认领（只删自己的；仅供中止/异常清理路径——成功提交由 commitGeneration 顺带释放）。 */
  void releaseIndexBuild(String kbName, String owner);

  /**
   * 027 条件提交代次：仍持有认领才生效（校验 claim rowcount → 写 committed_generation → bump knowledge 域 → 释放
   * claim）。false = 认领已失，未提交、旧代未动。旧代片段清理（deleteGenerationsBelow）由调用方在提交成功后执行——
   * 垃圾回收语义，中断残留由下次重建清理，不需要事务性。
   */
  boolean commitGeneration(String kbName, long generation, String owner);

  /** 027：读某库已提交代次；empty = 尚无已提交代次（首建前，检索返回空与现状口径一致）。 */
  OptionalLong committedGeneration(String kbName);

  /** 实例信息（运维查询载体）。 */
  record InstanceInfo(String instanceId, long epoch, Instant startedAt, Instant lastHeartbeatAt) {}

  /** 现役轮次持有信息。 */
  record TurnLeaseInfo(String sessionId, String owner, Instant leaseUntil, Instant acquiredAt) {}
}
