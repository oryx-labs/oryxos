package io.oryxos.core.notify;

/**
 * 通知渠道定义（跨模块值对象，31 节）：一个全局命名的通知出口。
 *
 * <p>{@code name} 全局唯一、Agent 按它引用；{@code type} 决定用哪个 {@code NotifyChannelAdapter}
 * （feishu/wecom/dingtalk/webhook）；{@code url} 是该渠道的 webhook 地址。放 core 是因为
 * web（CRUD）、oryxos-tool（notify 工具按名解析）、oryxos-storage（JPA 实现）三边都要认它，而三者都已依赖 core。
 */
public record NotifyChannelDef(String name, String type, String url, String description) {}
