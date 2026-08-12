# Data Model: 管理台概览页动态数据接入

## 新增实体

### SessionStats（值对象 / Record）

存在于 `oryxos-core` 作为 `SessionManager` 接口的返回值类型，不独立持久化。

| 字段 | 类型 | 说明 |
|------|------|------|
| `active` | `int` | 活跃会话数（`status = 'active'`） |
| `archived` | `int` | 归档会话数（`status = 'archived'`） |
| `total` | `int` | 总会话数（`active + archived`） |

Java 定义：
```java
public record SessionStats(int active, int archived) {
    public int total() { return active + archived; }
}
```

### SessionStatsView（Web DTO）

存在于 `oryxos-web` 的 `dto/` 包，从 `SessionStats` 转换后返回给前端。

| 字段 | 类型 | 说明 |
|------|------|------|
| `active` | `int` | 活跃会话数 |
| `archived` | `int` | 归档会话数 |
| `total` | `int` | 总会话数 |

Java 定义：
```java
public record SessionStatsView(int active, int archived, int total) {}
```

## 已有实体变更

### SessionRepository（JPA 接口）

新增方法：
```java
long countByStatus(String status);
```

Spring Data JPA 自动生成 `SELECT COUNT(*) FROM sessions WHERE status = ?`。

### SessionManager（核心接口）

新增方法：
```java
SessionStats stats();
```

### JpaSessionManager（实现）

新增 `stats()` 方法实现：
```java
@Override
public SessionStats stats() {
    int active = (int) repository.countByStatus("active");
    int archived = (int) repository.countByStatus("archived");
    return new SessionStats(active, archived);
}
```

## 前端数据模型

### OverviewStats（App.vue 响应式状态）

`overview` 从静态对象改为 `reactive()` 对象。每个 stat 有独立的加载/错误控制：

```javascript
const overview = reactive({
  tagline: '装在你自己基础设施上的分布式 AI Agent 操作系统...',
  status: '运行中',
  version: 'v0.1.0 · 开发预览',
  stats: {
    agents: { value: null, loading: true, error: null },
    tools: { value: null, loading: true, error: null },
    sessions: { value: null, loading: true, error: null },
    providers: { value: null, loading: true, error: null },
  },
})

// 加载完成后 derived:
const overviewStatsCards = computed(() => [
  { label: 'Agent', value: overview.stats.agents, hint: '...' },
  { label: '内置 Tool', value: overview.stats.tools, hint: '...' },
  { label: '活跃会话', value: overview.stats.sessions, hint: '...' },
  { label: 'Provider', value: overview.stats.providers, hint: '...' },
])
```
