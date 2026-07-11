# Contracts: Notify 出站通知

**Date**: 2026-07-11 | 消费方：20 节 ToolRegistry（收编 notify 工具）+ 24 节 Sandbox（检查位接线）+ 25 节定时 / 31 节 Demo（实际使用）

## 1. NotifyChannelAdapter（io.oryxos.tool.notify）——课件字面量签名

```java
public interface NotifyChannelAdapter {
    void send(NotifyTarget target, String content);   // 失败以异常表达
}

public record NotifyTarget(String channelType, Map<String, String> config) {}
```

中立性契约：接口与 record 的语汇不含任何一档实现特有的词；新增渠道档位只加实现类，签名与调用方不变（SC-005 自查项）。

## 2. WebhookNotifyAdapter（核心阶段唯一实现）

```java
public class WebhookNotifyAdapter implements NotifyChannelAdapter {
    public WebhookNotifyAdapter(RestClient restClient);
    // POST config["url"]，body {"content": content}，Content-Type: application/json
    // url 缺失 → IllegalArgumentException 点名；非 2xx/连接失败 → RestClient 异常上抛（不吞）
}
```

## 3. NotifyTools（io.oryxos.tool.builtin，OryxTool 形态——research D3）

```java
public class NotifyTools implements OryxTool {
    public NotifyTools(NotifyChannelAdapter adapter);
    // getName()="notify"；getDescription()="把一条消息推送到当前 Agent 配置好的通知渠道"
    // getInputSchema()：content 必填 / channel 可选
    // execute：解析参数 → resolveChannel（ProfileContext.current().notifyChannels()）
    //          → 沙箱检查位（24 节 Sandbox.enforce(HTTP_REQUEST, url) 接线，与 http_post 共享白名单）
    //          → adapter.send → ToolResult.ok("已推送")
}
```

渠道解析行为契约（clarify 1）：上下文缺失/未配置/类型不存在/content 缺失 → 失败 ToolResult 点名、零请求发出；channel 空白或 "default" → 第一个渠道；否则按 NotifyChannel.type 匹配。

## 4. 分批交付边界（课件"实现顺序说明"）

- 本节：上述全部类型 + harness 第一批（WebhookNotifyAdapterTest 全量、NotifyToolsTest 可测集）。
- 20 节：ToolRegistry 收编 notify、OryxOsRuntime 装配（本节不改 Runtime，工具 Map 维持空）。
- 23/24 节：Sandbox.enforce 真实现接入检查位 + NotifyToolsTest 补 InOrder 顺序回归（`@DisplayName("发送前必须先过白名单校验")`）。
- 27/28 节：串联全量验证。
