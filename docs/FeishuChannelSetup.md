# 飞书 IM 入站渠道接入指南

本文是 OryxOS 飞书入站渠道（017 特性）的部署操作手册：从飞书开放平台建应用，到 OryxOS 配置上线的完整步骤。验收场景见 `specs/017-feishu-im-channel/quickstart.md`。

> 架构速览：OryxOS 以**长连接**（WebSocket）主动连接飞书开放平台接收消息事件——免公网回调地址、免验签，服务器只需**出方向**可达 `open.feishu.cn:443`，契合内网部署。一个飞书自建应用固定绑定一个 Agent；多 Agent 即建多个应用。

## 一、飞书侧：创建企业自建应用

1. 打开 <https://open.feishu.cn/>，用企业账号登录，进入「**开发者后台**」。
2. 「**创建应用**」→ 选「**企业自建应用**」（不是商店应用）。
3. 填应用名称（即员工看到的机器人名字，如「运维小欧」）、描述、图标。
4. 进入应用详情 →「**基础信息 → 凭证与基础信息**」，记下 **App ID**（`cli_` 开头）与 **App Secret**。
   - ⚠️ 这两个值只经**环境变量**注入 OryxOS，禁止写入任何配置文件或提交历史（宪法 VI）。

## 二、飞书侧：添加机器人能力

「**应用能力 → 添加应用能力**」→ 添加「**机器人**」。没有此步应用无法收发消息、不能进群。

## 三、飞书侧：开通权限

「**开发配置 → 权限管理**」，开通：

| 权限 | 用途 |
|------|------|
| `im:message:send_as_bot`（以应用的身份发消息） | 发送回答 |
| `im:message.p2p_msg:readonly`（读取用户发给机器人的单聊消息） | 私聊问答 |
| `im:message.group_at_msg:readonly`（接收群聊中 @ 机器人消息事件） | 群聊 @ 问答 |

补充说明：

- 第四步添加事件时页面会列出所需权限并提供「申请开通」入口，从那里一键开通亦可。
- 「获取机器人信息」类权限如可见建议一并开通——OryxOS 启动时调 `GET /bot/v3/info` 获取机器人 open_id 用于群聊 @ 判定；获取失败会自动降级（按 `mentioned_type=bot` 判定）并 WARN，不阻塞上线。

## 四、飞书侧：配置长连接事件订阅（关键）

1. 「**开发配置 → 事件与回调**」。
2. 订阅方式选「**使用长连接接收事件**」——**不要**选「将事件发送至开发者服务器」（Webhook 模式，需公网回调，本渠道不支持）。长连接模式**无需**配置请求地址 / Verification Token / Encrypt Key。
3. 「已订阅事件」→「**添加事件**」→ 搜索并添加「**接收消息 `im.message.receive_v1`**」。
4. 提示缺权限则按提示申请（对应第三步）。

## 五、飞书侧：发布应用版本

权限与事件配置**必须随版本发布才生效**：

1. 「**应用发布 → 版本管理与发布**」→「创建版本」。
2. 填版本号与说明；「**可用范围**」测试期可仅设自己与测试同事，之后再扩全员。
3. 提交发布。企业自建应用通常需**企业管理员在飞书管理后台审批**（自己是管理员则直接通过审批消息即可）。

> 之后每次修改权限或事件订阅，都要**重新创建版本并发布**才生效——「配好了但没反应」十有八九是忘了这一步。

## 六、飞书侧：把机器人用起来

- **私聊**：飞书搜索机器人名字（或工作台找到应用）→ 直接发消息。
- **群聊**：测试群 → 群设置 →「**群机器人**」→「添加机器人」→ 选择应用；之后 `@机器人 + 问题` 触发。群里**不 @** 机器人的消息 OryxOS 完全不读、不留任何记录。

## 七、OryxOS 侧：配置与启动

1. **凭证走环境变量**（推荐落在仅属主可读的 env 文件，由启动 shell source；勿写进仓库）：

   ```bash
   install -m 600 /dev/null ~/.oryxos-feishu.env
   # 用编辑器写入两行：
   #   export FEISHU_APP_ID=cli_xxxxxxxx
   #   export FEISHU_APP_SECRET=xxxxxxxx
   source ~/.oryxos-feishu.env
   ```

2. **渠道绑定** `.oryxos/channels.yaml`（模板见 `config/channels.yaml.example`；文件权限会被自动收紧为 `rw-------`）：

   ```yaml
   channels:
     - name: ops-feishu
       type: feishu
       app_id: ${FEISHU_APP_ID}
       app_secret: ${FEISHU_APP_SECRET}
       agent: ops-agent        # .oryxos/agents/ 下真实存在的 Agent 目录名
       enabled: true
   ```

3. **启动并核对渠道状态**：

   ```bash
   java -jar oryxos-boot/target/oryxos-boot-*.jar serve
   curl -s localhost:8080/api/v1/channels/status
   # 期望 state: CONNECTED；ERROR 时 error 字段点名原因（缺凭证 / Agent 不存在 / 连接失败）
   ```

4. **免重启管理**（可选）：`/api/v1/channels` 提供 CRUD——新增 / 改绑 / 停用渠道即时生效，无需重启进程；凭证在接口出入参中始终保持 `${ENV}` 字面量或掩码，不回显明文。

## 八、常见问题

| 现象 | 排查 |
|------|------|
| 发消息机器人没反应 | 先看 `/api/v1/channels/status`。CONNECTED 仍无响应 → 事件未订阅或**版本未发布**（回第四、五步）；改配置后要重新发布版本 |
| 群里 @ 没反应、私聊正常 | `im:message.group_at_msg:readonly` 未开通或未随版本发布 |
| status 为 ERROR：`app_secret 未配置或环境变量未解析` | 启动 shell 里没有 `FEISHU_APP_SECRET`；`source` env 文件后重启或经 REST 重建渠道 |
| status 为 ERROR：`绑定的 Agent xxx 不存在` | `channels.yaml` 的 `agent` 必须是 `.oryxos/agents/` 下的目录名 |
| 长连接建立失败 | 确认出方向可达 `open.feishu.cn:443`（HTTPS + WebSocket）；无需任何入方向端口 |
| 收到「当前仅支持文本提问」 | 语音 / 文件等非图类型仍不在范围；**图片已支持**（入站下载后走 Vision）。若仍出现：确认事件含 image，且渠道进程可出站访问 `open.feishu.cn` |
| 多个 Agent 接入 | 一应用一 Agent；再建一个飞书应用 + `channels.yaml` 加一个条目 |

## 九、审计口径

- 私聊会话：`sessions` 表 `channel='feishu'`，`session_id = feishu:<open_id>:<agent>`。
- 群聊问答：不建持久会话，`llm_calls` / `tool_invocations` 的 `session_id` 前缀为 `feishu-group:`。
- 执行历史：`agent_executions.source = 'feishu'`；REST `GET /api/v1/agents/{name}/executions` 可查。
