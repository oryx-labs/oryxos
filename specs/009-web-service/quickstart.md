# Quickstart: 验证 Web Service 与管理台

## 前置

- 已完成 16~25 节；`class-26` 分支；`mvn`、`node/npm` 可用。
- `export DEEPSEEK_API_KEY=...`（本节排除 OpenAiAutoConfiguration 后，serve 只需这一个 key）。

## 自动化验证（harness — mvn test 全绿即通过）

```bash
# 只跑本节 Web 层单测（切片，不依赖模型）
mvn test -pl oryxos-web -am -Dtest='SessionApiControllerTest,GlobalExceptionHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false

# 全量门禁（实现完成的定义）——含前端构建（frontend-maven-plugin）
mvn clean verify
```

**预期**全绿。关键回归：

- `SessionApiControllerTest`：消息超 32KB→400；会话不存在→404；正常请求 `verify(agentService).process(...)` 恰一次。
- `GlobalExceptionHandlerTest`：各异常→约定状态码、响应体统一 `ApiResponse`；**500 响应不含内部异常 message**（断言 body not contains 连接串）。
- `WebSmokeIT`（`@Tag("integration")`，默认跳）：真实上下文起，`/health`/`/info`/`/profiles`/`/tools` 可达（JPA 仓库扫描 >0）。
- 既有 `SandboxWhitelistControllerTest`/`SandboxWhitelistWebMvcTest` 保持绿（共用 ApiResponse 信封）。

## 启动 Web Service + 管理台（一个进程两张脸）

```bash
mvn clean package -DskipTests          # frontend-maven-plugin 已把 Vue 管理台 build 进 static/admin
export DEEPSEEK_API_KEY=your-key
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080

curl localhost:8080/api/v1/health                 # {code:0,...}
curl -X POST localhost:8080/api/v1/sessions -H 'Content-Type: application/json' -d '{"profile":"default"}'
open  http://localhost:8080/admin                  # 管理台（Vue）五页
open  http://localhost:8080/swagger-ui             # 接口文档
```

## 人工项（harness 判不了，见课件"五、做完怎么验"）

1. 真模型发消息：`POST /sessions/{id}/messages` 真跑一轮、审计有账。
2. CLI 与 REST 同源：`oryxos chat` 聊过的 session，`GET /sessions/{id}` 查得到同一份。
3. 503/504 故障注入：断 Provider 拿 503、构造超 60s 拿 504，服务不崩。
4. 并发压：200 并发 invoke，虚拟线程扛得住。
5. 管理台：五页渲染真实数据、无写入口、与首页同视觉；错误页显示 message 不白屏。
6. 启动只需一个 provider key：只配 `DEEPSEEK_API_KEY`，serve 正常起（不索要 spring.ai.openai.api-key）。
