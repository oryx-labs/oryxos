package io.oryxos.core.agent;

import java.time.Instant;

/** 一次 Run 的不可变、按序活动事件。payloadJson 已是服务端安全处理后的展示载荷。 */
public record AgentRunEvent(
    long runId, long sequence, String type, Instant createdAt, String payloadJson) {}
