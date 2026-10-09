package io.oryxos.core.fs;

import io.oryxos.core.memory.MemoryMdGuard;
import java.nio.file.Path;

/**
 * 写路径保留文件守卫的统一入口。
 *
 * <p>MEMORY.md、AdminConfig（channels.yaml / mcp_servers.yaml / oryxos.db）、Skill·Knowledge 内容、
 * AGENT.md 各有自己的守卫类，但「一次拒绝全部保留文件」这个策略原先只聚合在 {@code FileTools} 的私有 方法里，于是只有 FileTools
 * 系列工具受约束——{@code export_excel} 这类同样写盘的工具看不见它， 可以直接覆盖 MEMORY.md 与 channels.yaml。
 *
 * <p>把聚合放在守卫所在的模块，任何写文件的工具都能一行接入；新增写盘工具时也不必再去 FileTools 抄一遍守卫清单。
 */
public final class ReservedFileGuard {

  private ReservedFileGuard() {}

  /** 拒绝写路径触及任一保留文件；路径为 null/空白时不做判定。 */
  public static void rejectMutation(String path) {
    MemoryMdGuard.rejectMutation(path);
    AdminConfigFileGuard.rejectMutation(path);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(path);
    WorkspaceMutationGuard.rejectAgentMdDirectWrite(path);
  }

  /** 已解析路径版本，供拿到 {@link Path} 的调用方使用。 */
  public static void rejectMutation(Path path) {
    MemoryMdGuard.rejectMutation(path);
    AdminConfigFileGuard.rejectMutation(path);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(path);
    WorkspaceMutationGuard.rejectAgentMdDirectWrite(path);
  }
}
