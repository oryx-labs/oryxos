# Data Model: Web Service 与第一版管理平台

无新持久化实体、无表变更。以下是对外 DTO、复用的信封、以及两处接口扩展。

## 复用（不改）

### ApiResponse（统一信封，io.oryxos.web.common）

`ApiResponse<T>`：`code`(int, 成功 0 / 错误取 HTTP 状态) · `message` · `data`(T) · `timestamp`。`ok(data)` / `error(code, message)`。成功与错误共用。

## 新增：请求/响应 DTO（record，io.oryxos.web.controller.dto）

| DTO | 用途 | 字段 |
|-----|------|------|
| `CreateSessionRequest` | POST /sessions | `profile`（必填）、`userId`（可空，缺省 "default"） |
| `MessageRequest` | POST /sessions/{id}/messages、invoke | `content`（必填，≤32KB） |
| `MessageResponse` | 发消息/invoke 回复 | `reply` |
| `SessionView` | GET /sessions/{id} | `sessionId`、`profile`、`messages`（最近≤100）、`status` |
| `ProfileView` | GET /profiles | `name`、`description`、`provider`、`tools`、`skills`…（从 Profile 投影，不含敏感字段） |
| `ToolView` | GET /tools | `name`、`description` |
| `InfoView` | GET /info | 运行信息 + `providers`（名称 + 已配置状态） |

DTO 一律 record；集合防御性拷贝或投影自既有对象，不回传内部实体。

## 端点 → 后端服务映射（Controller 只校验/包装/兜错）

| 端点 | 委托 |
|------|------|
| POST /sessions | `SessionManager.getOrCreate("web", userId, profile)` |
| POST /sessions/{id}/messages | `SessionManager.get(id)`→404；`AgentService.process(session, content)` |
| GET /sessions/{id} | `SessionManager.get(id)`→404；投影 SessionView（messages 取尾 100） |
| DELETE /sessions/{id} | **`SessionManager.archive(id)`**（改造点1） |
| POST /agents/{name}/invoke | `AgentService.process(临时会话, content)`（会话身份 channel="web"/user 匿名或直接无状态） |
| GET /profiles | `ProfileRegistry.all()`→ ProfileView 列表 |
| GET /memory | **`MemoryService.readAll()`**（改造点2） |
| GET /tools | `ToolRegistry.all()` / tools bean → ToolView 列表 |
| GET /health | 静态 ok |
| GET /info | 运行信息 + providerMap 名称/已配置状态 |

## 接口扩展（改造点，tasks 停点确认）

### SessionManager（core，18 节接口）+ 实现

```java
// 新增方法
void archive(String sessionId);   // 幂等：get→set status=archived、archived_at=now→save；不存在则抛 SessionNotFound（→404）
```

`JpaSessionManager.archive`：`repository.findById` → 实体 `setStatus("archived")` + `setArchivedAt(now)` → save。session_id 拼接仍只在内部（H4④，本方法只接收 id）。

### MemoryService（core，22 节接口）+ 实现

```java
// 新增方法
String readAll();   // 返回长期记忆全文（核心+归档拼接），委托 LongTermMemoryStore.load()
```

`MemoryServiceImpl.readAll`：`return store.load();`（无缓存，每次读）。

## GlobalExceptionHandler 扩展（既有类，io.oryxos.web）

新增映射（统一 `ApiResponse.error`）：`SessionNotFoundException`→404、`ProviderUnavailableException`→503、Agent 超时（如 `AgentTimeoutException` 或既有超时异常）→504、消息超限/非法→400；兜底 `Exception`→500 **只给统一话术、不含内部 message**。保留既有映射不回退。

> 新异常类型（`SessionNotFoundException`/`ProviderUnavailableException`/超时异常）若前序未定义，则本节在 oryxos-web 内新增为轻量运行时异常（属本节对外概念，交付物点名）。
