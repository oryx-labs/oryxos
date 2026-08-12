package io.oryxos.core.agent;

/**
 * ReAct 循环达到 {@code max_iterations} 上限时抛——通常是反复调用工具失败（权限不足、模型幻觉等）耗尽全部轮数。
 *
 * <p>这是一个可恢复错误：session 中的对话与工具调用记录保留在现场，供排查迭代不收敛的原因； Agent 状态不受影响。
 */
public class AgentMaxIterationsExceededException extends RuntimeException {

  public AgentMaxIterationsExceededException(String message) {
    super(message);
  }
}
