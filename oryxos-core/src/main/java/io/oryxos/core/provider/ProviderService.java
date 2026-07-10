package io.oryxos.core.provider;

import io.oryxos.core.profile.Profile;

/**
 * Provider 调用契约：按 Profile 路由到对应模型、发起一次调用、结果原样返回。
 *
 * <p>契约放 core 是依赖倒置（宪法 v1.1.0 模块条款）：ReActLoop（core）只认此接口， 具体协议转换由 oryxos-provider 的 {@code
 * SpringAiProviderServiceImpl} 实现——core 不依赖任何 Spring AI 类型。
 */
public interface ProviderService {

  /**
   * 发起一次模型调用；不论成败都落 llm_calls 审计（宪法 V），失败先落账再上抛。
   *
   * @param sessionId 会话标识，审计按 session 关联（只透传，不在此生成）
   */
  ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request);
}
