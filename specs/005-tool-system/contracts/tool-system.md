# Contracts: Tool 体系

**Date**: 2026-07-11 | 消费方：17 节 ToolExecutor/PromptBuilder（asMap/过滤）、22 节 MemoryTools（注册）、24 节 WhitelistSandbox（替换 Permissive）、29 节运行时注册

## 1. ToolRegistry（io.oryxos.tool）

```java
public class ToolRegistry {
    public void register(OryxTool tool);              // 重名 → IllegalStateException 点名
    public void registerAnnotated(Object bean);       // @Tool 方法扫描 → schema 生成 → 包装注册
    public boolean contains(String name);
    public Optional<OryxTool> get(String name);
    public Collection<OryxTool> all();
    public Map<String, OryxTool> asMap();             // 供 OryxOsRuntime tools() bean
    public List<OryxTool> filterByNames(List<String> names);  // Profile.tools 过滤，不多不少
}
```

## 2. Sandbox（io.oryxos.tool.sandbox，前向——24 节交付 WhitelistSandbox）

```java
public interface Sandbox { void enforce(SandboxAction action); }
public record SandboxAction(ActionType type, String target) {}
public enum ActionType { FILE_READ, FILE_WRITE, SHELL_COMMAND, HTTP_REQUEST }
public class SandboxViolationException extends RuntimeException {}
```

行为契约：内置工具方法体**第一行** enforce；违规异常传播 → ToolExecutor 转失败结果 + success=false 审计。

## 3. 内置工具（io.oryxos.tool.builtin，@Tool 注解管道）

`FileTools(Sandbox)` / `ShellTools(Sandbox)` / `HttpTools(Sandbox, RestClient)`——方法名即工具名字面量（@Tool(name=...)）：read_file/write_file/list_dir/shell/http_get/http_post。

## 4. MCP（io.oryxos.tool.mcp）

```java
public class McpClientService {
    public McpClientService(McpConfigLoader loader, Function<McpServerConfig, McpSyncClient> factory);
    public void connectAll(ToolRegistry registry);   // 失联 WARN 跳过；好 server 照常注册
}
public class McpToolAdapter implements OryxTool { /* listTools 条目映射；callTool 转发；isError→失败可重试 */ }
```

## 5. OryxOsRuntime 接线（17/19 节 plan 预告的改造）

`tools()` bean：`Map.of()` → `toolRegistry.asMap()`；新增 Sandbox（Permissive，24 节换）/ RestClient / ToolRegistry bean（注册内置 3 类 + notify + MCP connectAll）。
