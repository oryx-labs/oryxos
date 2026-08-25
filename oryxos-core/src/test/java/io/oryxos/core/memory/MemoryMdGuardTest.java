package io.oryxos.core.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MemoryMdGuardTest {

  @Test
  @DisplayName("叶子名为 MEMORY.md 时拒绝")
  void rejectsLeafMemoryMd() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MemoryMdGuard.rejectMutation(".oryxos/agents/demo/MEMORY.md"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MemoryMdGuard.rejectMutation("agents/demo/memory.md"));
  }

  @Test
  @DisplayName("中间路径段为 MEMORY.md 时也拒绝（防建成目录）")
  void rejectsMemoryMdAsAncestorSegment() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MemoryMdGuard.rejectMutation(".oryxos/agents/demo/MEMORY.md/child.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MemoryMdGuard.rejectMutation("agents/demo/Memory.md/nested/x.md"));
  }

  @Test
  @DisplayName("普通路径放行")
  void allowsOrdinaryPaths() {
    assertDoesNotThrow(() -> MemoryMdGuard.rejectMutation("agents/demo/notes.md"));
    assertDoesNotThrow(() -> MemoryMdGuard.rejectMutation("memory/backup.txt"));
    assertDoesNotThrow(() -> MemoryMdGuard.rejectMutation(null));
    assertDoesNotThrow(() -> MemoryMdGuard.rejectMutation("  "));
  }
}
