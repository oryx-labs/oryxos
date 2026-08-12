# Quickstart: 管理台概览页动态数据接入

## Prerequisites

- OryxOS 已编译 (`mvn package -DskipTests`) 且已初始化工作区 (`oryxos init`)
- 至少配置了 1 个 Agent Profile、1 个 Provider
- 管理台可访问（`oryxos serve` 启动后访问 `http://localhost:8080/admin/`）
- 存在若干活跃或归档的会话记录

## Validation Scenarios

### Scenario 1: 概览页四项统计卡展示实时数据

1. 启动 OryxOS: `mvn -pl oryxos-boot spring-boot:run`
2. 浏览器打开 `http://localhost:8080/admin/`
3. 确认概览页 "Agent" 统计卡数值 = `curl -s http://localhost:8080/api/v1/profiles | jq '.data | length'`
4. 确认概览页 "内置 Tool" 统计卡数值 = `curl -s http://localhost:8080/api/v1/tools | jq '.data | length'`
5. 确认概览页 "Provider" 统计卡数值 = `curl -s http://localhost:8080/api/v1/info | jq '.data.providers | length'`
6. 确认概览页 "活跃会话" 统计卡数值 = `curl -s http://localhost:8080/api/v1/sessions/stats | jq '.data.active'`

**Expected**: 四项统计卡数值与各自 API 返回值一致，且不再显示 "当前为静态预览数据" 提示。

### Scenario 2: 某端点故障时概览页部分降级

1. 启动 OryxOS（不启动完整后端，或不部署 MCP server 让 Tool 注册报错不可行——改为在浏览器 DevTools 中手动阻止 `/api/v1/tools` 请求）
2. 刷新概览页

**Expected**: 
- "内置 Tool" 统计卡显示 "—" 或错误态
- 其余三项统计卡正常展示
- 整体概览页不白屏不崩溃

### Scenario 3: 新增/归档会话后统计实时反映

1. 查看当前活跃会话数: `curl http://localhost:8080/api/v1/sessions/stats`
2. 通过 Web API 创建一个会话并发送一条消息
3. 再次查看 `/api/v1/sessions/stats`，确认 active +1
4. 归档该会话后再次查看，确认 active -1、archived +1

### Scenario 4: 新注册 Profile 后概览自动更新

1. 查看当前 Agent 数: `curl http://localhost:8080/api/v1/profiles | jq '.data | length'`
2. 创建新 Agent Profile（通过管理台或 API）
3. 刷新概览页，确认 Agent 数 +1

## Run / Test Commands

```bash
# 启动后端
mvn -pl oryxos-boot spring-boot:run

# 验证会话统计端点
curl -s http://localhost:8080/api/v1/sessions/stats | jq .

# 验证概览四个数据源
curl -s http://localhost:8080/api/v1/profiles | jq '.data | length'   # Agent 数
curl -s http://localhost:8080/api/v1/tools | jq '.data | length'      # Tool 数
curl -s http://localhost:8080/api/v1/info | jq '.data.providers | length'  # Provider 数
curl -s http://localhost:8080/api/v1/sessions/stats | jq '.data.active'    # 活跃会话数

# 构建前端（如独立构建）
mvn -pl oryxos-web compile

# 全量质量门禁
mvn verify
```
