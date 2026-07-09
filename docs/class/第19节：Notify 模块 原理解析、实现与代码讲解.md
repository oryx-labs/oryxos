# Notify：原理解析、实现与代码讲解

CLI（18 节）给了 Agent 一个能亲手操作的入口，但目前这个入口只会"同步回话"——你问一句它答一句，答完就完了。定时模块（25 节）和日报 Agent 这类场景不一样：到点自动触发，没有人在等着看响应，Agent 必须**主动**把结果送到人能看到的地方。这节讲四件事：Notify 是什么、动手前该想清楚什么、代码怎么写、怎么用和怎么验。

---

## 一、Notify 是什么，干嘛用的

一句话：**入站有 Channel 负责"消息怎么进来"，Notify 补的是对称的另一半——"结果怎么主动送出去"。**

CLI 和 Web Service 都是"人推"：有人发起一次调用，Agent 处理完直接把响应返回给发起者，走的是同一条请求-响应链路，不需要额外的推送机制。但一旦触发源变成"到点自动"（定时查天气、每天汇总科技新闻），这条链路就断了——没有人在另一端等着接收响应，Agent 必须自己决定把结果送到哪、怎么送。

![入站 ChannelAdapter 与出站 NotifyChannelAdapter 对称关系](../../website/public/images/class-19-1.svg)

**如果没有这个模块会怎样。** 每个业务方定义 Agent 时都要自己在 Skill 里手写"调 `http_post` 打这个 webhook URL"，或者自己找一个企业微信/飞书的 MCP server 配上——每个 Skill 各写一份，重复且不统一。`NotifyTools` 就是要把"往外推一条消息"这件最常见的事统一掉——这也是后面 Memory（21、22 节）、Sandbox（23、24 节）会反复用到的"接口先行"设计习惯的第一次亮相。

---

## 二、动手前先想清楚几件事

**第一，先定接口，别先定实现。** 先抽一个不携带具体渠道细节的接口，表达"把一条内容送到某个通知目标"这个意图，不出现"企业微信""飞书"这类某一档实现特有的词。核心阶段只在接口后面挂一档实现，以后加新渠道只新增实现类，不改接口、不改调用方——这个"接口先行"的思路后面讲 Sandbox（23、24 节）时还会再遇到一次，是这门课里反复出现的同一套设计习惯。

**第二，核心阶段只做通用 webhook，不逐家接专用 API。** 企业微信、飞书、钉钉的群机器人都提供 webhook 地址，核心阶段用一个通用的 `WebhookNotifyAdapter` 就能覆盖大部分场景，不用去接每家的签名算法、AccessToken 刷新这些认证细节——那些留给扩展阶段按需要再加。

**第三，安全校验先占位，具体怎么做留给 Sandbox 那节。** `notify` 发出去的是一次 HTTP 请求，理应跟 `http_post` 一样过一层域名白名单，不能因为它是"往外推"就绕过去。但白名单具体怎么校验、这道墙怎么设计，是 23、24 节 Sandbox 模块要讲的内容——这里先按接口调用的方式接进去，细节到那两节再展开，不重复讲。

**第四，具体推到哪，配置在 Profile，不暴露在对话里。** webhook 地址是运行时配置，不是模型需要知道的信息。LLM 调用时大多数情况只传 `content` 就够了，`channel` 是可选参数，对应 Profile 里配好的 `notify_channels` 字段。

想清楚就这几句：接口表达意图，不表达实现；核心阶段只填通用 webhook 这一档；安全校验和审计走后面会讲到的统一机制，这里先接进去、不重复设计；具体推到哪是配置，不是对话内容。

> **实现顺序说明（授课顺序 ≠ 构建顺序）**：本节的 `NotifyChannelAdapter`/`NotifyTarget`/`WebhookNotifyAdapter` 三样可以立即实现、独立单测；但 `NotifyTools` 的完整接线有三个依赖在后面的课——`@Tool` 注册机制和 `ToolResult` 在 20 节、`Sandbox.enforce` 在 23/24 节。所以按文档拆任务时，`NotifyTools` 这个任务的完成时点在 24 节之后，27/28 节串联时做全量验证。`ProfileContext` 是 17 节 `AgentService` 已交付的 ThreadLocal，这里直接用。

---

## 三、代码怎么写

**`NotifyChannelAdapter` 接口。** 只有一个方法：

```java
package io.oryxos.tool.notify;

public interface NotifyChannelAdapter {
    void send(NotifyTarget target, String content);
}
```

```java
package io.oryxos.tool.notify;

import java.util.Map;

public record NotifyTarget(String channelType, Map<String, String> config) {
}
```

`NotifyTarget` 只有 `channelType` 和一份 `config`，具体是 webhook 地址还是别的认证信息，由实现类自己去解释——接口签名里不出现任何一档实现特有的词。

**`WebhookNotifyAdapter`（核心阶段唯一实现）。**

```java
package io.oryxos.tool.notify;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WebhookNotifyAdapter implements NotifyChannelAdapter {

    private final RestClient restClient;

    public WebhookNotifyAdapter(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public void send(NotifyTarget target, String content) {
        String url = target.config().get("url");
        restClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("content", content))
                .retrieve()
                .toBodilessEntity();
    }
}
```

**`NotifyTools`（内置 Tool，归 `oryxos-tool`）。**

```java
package io.oryxos.tool.builtin;

import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.notify.NotifyTarget;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class NotifyTools {

    private final Sandbox sandbox;
    private final NotifyChannelAdapter adapter;
    private final ProfileContext profileContext;

    public NotifyTools(Sandbox sandbox, NotifyChannelAdapter adapter, ProfileContext profileContext) {
        this.sandbox = sandbox;
        this.adapter = adapter;
        this.profileContext = profileContext;
    }

    @Tool(description = "把一条消息推送到当前 Agent 配置好的通知渠道")
    public ToolResult notify(String content, String channel) {
        NotifyTarget target = profileContext.resolveNotifyChannel(channel);
        sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.config().get("url")));
        adapter.send(target, content);
        return ToolResult.success("已推送");
    }
}
```

一行行看关键的三步：`profileContext.resolveNotifyChannel(channel)` 从当前 Profile 的 `notify_channels` 字段里找到对应配置——`ProfileContext` 就是 17 节 `AgentService` 在入口处放好的那个 ThreadLocal，工具执行时从它知道"当前是哪个 Agent"——`channel` 参数不传时用第一个/默认渠道；`sandbox.enforce(...)` 这一步先接进去，具体这道白名单怎么校验、`Sandbox` 接口怎么设计，23、24 节会专门展开，`notify` 到时候不需要自己重新实现一套；`adapter.send(...)` 才是真正发出去的地方，核心阶段这里注入的就是 `WebhookNotifyAdapter`。

![NotifyTools 接口设计：核心阶段 WebhookNotifyAdapter，扩展阶段新增专用渠道](../../website/public/images/class-19-2.svg)

**Profile 配置示例：**

```yaml
notify_channels:
  - type: webhook
    url: ${TEAM_WEBHOOK_URL}
```

**本节交付物**（Spec-Kit 拆解锚点）：

- 代码：`NotifyChannelAdapter` 接口、`NotifyTarget`、`WebhookNotifyAdapter`、`NotifyTools`（`notify` 内置 Tool，完整接线依赖 20/24 节）
- 配置：Profile 新增 `notify_channels` 字段（type + 渠道特定配置如 url）

---

## 四、怎么用，做完怎么验

配好 `notify_channels` 之后，Agent 在对话里自己决定要不要调，不需要人工干预：

```text
用户：每天早上帮我看看天气，穿搭建议直接发到我们群里
Agent：好的（之后每天到点自动查天气 → 调 notify 推送到配置好的群）
```

也可以在对话里直接测试：

```text
用户：把"测试消息"推送一下
Agent：（调用 notify(content="测试消息")）已推送
```

做完对着下面几条验：

- `notify` 调用真的能把消息送到配置好的 webhook，群里能收到。
- 故意把 `notify_channels` 的 `url` 配成不在白名单里的域名，确认 `Sandbox.enforce(...)` 会拦下来，`notify` 调用失败并且 `tool_invocations` 里能查到这次失败记录和失败原因。
- `notify_channels` 留空时的行为要明确：不是静默失败，而是清楚报错"没有配置通知渠道"，避免 Agent 以为推送成功了但其实什么都没发出去。
- 接口中立性自查：合上代码，把 `NotifyChannelAdapter.send(NotifyTarget, String)` 这个签名读一遍，换成企业微信官方 SDK 的实现，需要改接口吗？答案应该是不需要，`NotifyTarget` 的 `channelType + config` 已经足够表达"送到哪个目标、带什么配置"，具体怎么送是实现类自己的事。
- 回归：`http_post` 原有的单元测试仍然全绿——`notify` 是新加的 Tool，没有改任何已有代码。

Notify 补上的是"Agent 说完话还能主动送出去"这个出口。有了它，25 节的定时模块和 31 节的天气、日报 Agent 才有地方把结果真正交出去，不然到点跑完一整套 ReAct 循环，结果却只能烂在 Session 里没人看到。
