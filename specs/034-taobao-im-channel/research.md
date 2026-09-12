# Research: 034 淘宝/天猫店铺客服

**Date**: 2026-09-12  
**Status**: PATH LOCKED — **仍不可 BUILD**（缺入驻/`cloudAppId`/权限包真机核验）  
**对照**: [030](../030-cn-c-im-roadmap/plan.md)、[plan](./plan.md)

## 目标

官方直连：**买家 ↔ 店铺客服会话**由 Agent 收发文本。天猫与淘宝同 TOP → 单模块 `taobao`。

## 已排除的「假完成」路径

| 能力 | 为何不算店铺客服 IM |
|------|---------------------|
| TMC 交易/退款/物流主题 | 业务事件，不是聊天会话 |
| `taobao.jindoucloud.message.send` | 千牛**系统通知/服务号类目**；≠ 回买家旺旺 |
| 旺旺「亮灯」JSAPI（`openChat`） | 客户端唤起聊天窗，不是服务端收发 |
| 通用云旺 OpenIM（`openim.*.push`） | 独立 IM 账号体系，**不等于**手淘买家找店客服默认会话 |
| Cookie / 协议模拟千牛 | ❌ Won’t（违规） |

## 锁定真路径：智能客服（客服语料）+ 奇门

公开文档（飞猪/淘宝「客服机器人 / AI 封闭环境」方案，约 2025）：

| 项 | 钉死内容 | 来源 |
|----|----------|------|
| 官方场景 | **客服语料开放场景**（奇门官方集成） | [飞猪方案文](https://open.fliggy.com/docs/doc.htm?articleId=122203&docType=1&treeId=843) |
| 入站 API 名 | `qimen.taobao.message.chatrobot.sync` | 同上 + [apiId=72219](https://developer.alibaba.com/docs/api.htm?apiId=72219) |
| 运行时网关 | `POST https://mappcloud-gw.taobao.com/invokeChatRobot/conversation?cloudAppId=…&cloudEnv=test\|online&path=…` | 飞猪方案文 |
| 入站载荷 | `body` = 机器人协议 **event JSON**；`param` 含 `method` / `sign` / `open_id` / `main_user_open_id` / `timestamp` | 同上 |
| 验签 | 用网关 `param.sign`；**忽略**奇门自带 sign | 飞猪方案文明确写明 |
| 入站文本样例 | `event.body.contentType=1`，`content={"text":"你好"}`；`sender.role=buyer`，`receivers[].role=customService`；`header.type=1`（bc 单聊） | apiId=72219 示例 |
| 出站（异步） | `taobao.message.chatrobot.async`（聊天机器人异步 action）→ TOP `eco.taobao.com` / `gw.api.taobao.com` | [apiId=48631](https://developer.alibaba.com/docs/api.htm?apiId=48631) |
| 出站要点 | `actions[]` 回填上行 `header.action_mode` / `type` / `request_id` / `tenant_id`；`body.content_type=1` 文本；`auth_param.request_token` 由数据平台颁发 | 同上 |
| 入驻身份 | **ISV / 智能客服解决方案**：先做 AI/云应用 → 联系业务小二拿 **`cloudAppId`** → 奇门关联官方场景 → 自测/发布/授权 | 飞猪方案 + [奇门官方场景流程](https://open.taobao.com/doc.htm?docId=106849&docType=1) |
| 切流 | 消息按**商家切流**进机器人；非任意 AppKey 即开即用 | 飞猪方案文 |

候选 B（千牛服务市场「客服工具」插件）与上表**同源或更重 ISV 上架形态**，不另开第二套协议；OS 完成定义仍是直连奇门/TOP，不经晓多等二次聚合。

## chatId / 用户标识（设计草案，未编码）

- 买家键：优先 `open_uid` / SecurityUID（脱敏改造后禁止 nick 唯一键）。  
- 会话草案：`taobao:{sellerOpenId|main_user_open_id}:buyer:{buyerOpenUid}`（编码以入驻后字段为准）。  
- `channelType=bc` 表示旺旺买家-客服单聊。

## 沙箱 / 域名（`http.allowed_domains` 候选）

- `mappcloud-gw.taobao.com`（运行时切流）  
- `qimen.api.taobao.com`（奇门路由，若仍走经典 qm）  
- `gw.api.taobao.com` / `eco.taobao.com`（TOP 出站 async）

## 准入缺口（未勾前禁止 BUILD）

- [x] 书面确认入驻身份形态：**ISV 智能客服 / 客服语料官方场景**（非裸 TOP 店铺 App）  
- [x] 钉死入站接口名 + 公开样例 event（文本）  
- [x] 钉死出站：`taobao.message.chatrobot.async`（同步 HTTP 体内是否也可回写：文档未钉死，**MVP 以 async 为准**）  
- [ ] 拿到开发者/`cloudAppId`：权限包可见、沙箱联调  
- [ ] 会话窗与限流（公开文未写死条数/小时窗；真机或小二确认后 fail-loud）  
- [ ] `request_token` / 商家授权 session 生命周期写进 Setup  
- [ ] 与「仅订购中台」边界：OS 仍直连奇门/官方，不经二次聚合  

## 当前结论

| 项 | 说明 |
|----|------|
| 成熟度 | 🔶→接近 ✅ Ready（**路径已钉死**） |
| 电商刚需 | ✅ P1 |
| 可马上编码 | ❌ — 缺入驻与真机字段 |
| 对内话术 | 淘宝不是「没有开放」，而是「会话面绑在智能客服/奇门 ISV 方案上」；切勿用 TMC 订单消息冒充已接客服 IM |

## 建议下一动作（产品）

1. 有开放平台账号：申请/确认「客服语料 / 智能客服」类目，找小二要 `cloudAppId`。  
2. 无账号：034 **暂停 BUILD**；路线图下一刀改为 **W2 服务号客服** 或 **032 支付宝 research 收口**（均可纯文档推进）。  
3. 你点头「034 BUILD」前，本目录只允许改 research/plan，不建 `oryxos-channel-taobao`。
