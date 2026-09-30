package io.oryxos.core.capability;

/**
 * Per-turn capability overlay (mirrors ProfileContext discipline). Flow AGENT nodes set this before
 * {@code processStateless}; ContextLoader filters skill/knowledge disclosure against it.
 */
public final class CapabilityAssemblyContext {

  private static final ThreadLocal<CapabilityAssembly> CURRENT = new ThreadLocal<>();

  private CapabilityAssemblyContext() {}

  public static void set(CapabilityAssembly assembly) {
    if (assembly == null || assembly.isEmpty()) {
      CURRENT.remove();
    } else {
      CURRENT.set(assembly);
    }
  }

  public static CapabilityAssembly current() {
    CapabilityAssembly a = CURRENT.get();
    return a == null ? CapabilityAssembly.empty() : a;
  }

  public static void clear() {
    CURRENT.remove();
  }
}
