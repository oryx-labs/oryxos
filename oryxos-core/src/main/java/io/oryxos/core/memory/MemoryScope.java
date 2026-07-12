package io.oryxos.core.memory;

/** 长期记忆的两类分区：核心（始终完整在场）/ 归档（量大、超限截断）。 */
public enum MemoryScope {
  CORE,
  ARCHIVAL
}
