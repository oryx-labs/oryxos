# 动态管理 Agent：一句话生成、上传即上线

上一节把"定义一个 Agent"的机制立住了：**一份 Skill 文件 + 运行时注册能力**，系统扫到就派生成一个会自己跑的 Agent。但入口还停在"登录服务器往目录里放文件"——业务系统用不上，管理平台也只能看不能管。这一节把最后一块拼上：把这套能力包成 `/api/v1/agents` 的增删改查，再加上一个"**一句话自动生成 Skill**"的能力——让运营在页面上说一句话，就造出一个会自己跑的 Agent。

---

## 一、本节目标：一次调用 / 一句话，造出一个会自己跑的 Agent

先把终点说清楚，一句话：

> **业务系统 POST 一次、或运营在页面上说一句话，就把一个 Agent 建出来——立刻可用、到点自己跑、全程不重启；对外只有"Agent"一个概念，内部落地成上一节那份 Skill。**

两个能验证的终态：

1. **上传即上线**：`POST /api/v1/agents` 带上 Agent 的定义（或直接一段 Skill 正文），返回 200 后**不重启**，这个 Agent 立刻出现在列表里、到 cron 点自己跑；
2. **一句话生成**：在管理台输入一句话——"每天早上九点查北京天气，把穿搭建议发到团队群"——系统用 LLM 把它**生成一份规范的 Skill**（frontmatter + 正文）给你预览，确认后一键创建。

对外暴露的资源就是 **Agent** 这一个概念。业务方心智里创建的是"一个每天推日报的 Agent"，不是"一份 Skill"——所以 API 收进来的是"Agent 的定义"，内部**落成上一节那份 `SKILL.md`**，再走 29 节那条"派生 → 注册"的路。四个管理端点挂在 26 节已有的 `AgentApiController` 上（那里已经有 `POST /agents/{name}/invoke`），另加一个"生成"端点：

![五个端点包装同一套底层能力](../../website/public/images/class-30-2.svg)

这组接口做完，26 节管理平台"只读"的限制同时解除：加一个"新建 Agent"入口，管理平台从"能看"升级成"真能管"。

## 二、围绕目标要做哪些

### 2.1 编排复用，不写第二套

创建一个 Agent，内部就三件事：**校验 → 写 Skill 文件 → 派生并注册**。没有一件是新能力——校验和"派生 + 注册"全是 29 节立好的（`deriveProfile` + `ProfileRegistry.register` + `AgentScheduler.registerProfile`）。新增的只有一个**编排者** `AgentLifecycleService`（归 `oryxos-core`），把这几步按顺序串起来。判断标准还是 29 节那句：**API 建的 Agent 和手写文件建的 Agent，行为必须一模一样**——保证这一点的唯一办法，就是两条路径走同一段代码（写完文件后，同样调 `deriveProfile → register`，不另写一套）。

比上一版更简单的一点：因为 29 节改成了 **Skill 单文件**（定时、工具、provider 都在 frontmatter 里），create 不再"写 Skill + 写 Profile 两份文件"，而是**只写一份 `SKILL.md`**，Profile 由 `deriveProfile` 现场派生、不落盘。少一份文件，就少一处可能不一致的地方。

### 2.2 一句话 → Skill：把自然语言变成规范定义

"一句话生成"是这一节唯一的新面孔。它本质就是一次 LLM 调用：把用户那句话，加上"OryxOS Skill 的格式说明"，让模型产出一份合规的 `SKILL.md`（frontmatter 齐全 + 正文清晰）。

关键是**两步、人在环里**，不要一句话直接上线：

1. `POST /api/v1/agents/generate` 收一句话 → LLM 生成 Skill 草稿 → **原样返回给前端预览**，不落盘、不注册；
2. 用户在页面上看一眼、可改（尤其 `schedules` 的 cron、`tools` 权限这种敏感项）→ 满意了再调 `POST /api/v1/agents` 正式创建。

为什么必须留这一步：LLM 生成的 cron 可能把时间理解错、`tools` 可能给多了权限——**这些得人过一眼**。而且这次生成本身也是一次 LLM 调用，照样落 `llm_calls` 审计（宪法原则五）。

### 2.3 删除与更新的语义，提前定死

- **删除**：先注销定时（29 节存的 `scheduledTasks` 句柄这时兑现价值）→ 再从 `ProfileRegistry` 移除 → 最后把 `SKILL.md` 移进归档目录 `.oryxos/archive/`——**不物理删**。这个 Agent 干过的事都在审计表里，定义文件也应可追溯。
- **更新**：改正文覆写文件即可（`ContextLoader` 每次重读，天然即时生效）；`schedules` 变了则**先注销旧句柄、再注册新的**，不然旧 cron 会跟新 cron 一起跑。

### 2.4 错误码沿用 26 节口径

name 已存在、Skill 字段非法、引用了不存在的 provider——都是 400，`code` 各自区分；查 / 改 / 删一个不存在的 Agent——404。一句话生成时若 LLM 产出的不是合法 Skill——也归 400（带可读原因），不发明新状态码；统一走 26 节的 `ApiResponse` 信封。

## 三、代码怎么写

**第一步：Controller，照旧很薄。** 在 26 节的 `AgentApiController` 上加五个方法：

```java
@RestController
@RequestMapping("/api/v1/agents")
public class AgentApiController {

    private final AgentLifecycleService lifecycle;

    @PostMapping("/generate")   // 一句话 → Skill 草稿（只生成、不落盘）
    public ApiResponse<String> generate(@RequestBody GenerateRequest req) {
        return ApiResponse.ok(lifecycle.generateSkill(req.sentence()));
    }

    @PostMapping                 // 正式创建：写 SKILL.md + 派生注册
    public ApiResponse<AgentView> create(@RequestBody CreateAgentRequest req) {
        return ApiResponse.ok(lifecycle.create(req));
    }

    @GetMapping("/{name}")
    public ApiResponse<AgentView> get(@PathVariable String name) {
        return ApiResponse.ok(lifecycle.get(name));
    }

    @PutMapping("/{name}")
    public ApiResponse<AgentView> update(@PathVariable String name,
                                         @RequestBody UpdateAgentRequest req) {
        return ApiResponse.ok(lifecycle.update(name, req));
    }

    @DeleteMapping("/{name}")
    public ApiResponse<Void> delete(@PathVariable String name) {
        lifecycle.delete(name);
        return ApiResponse.ok(null);
    }
}
```

创建请求体既可以给结构化字段，也可以直接给一段 Skill 正文——两者最终都拼成一份 `SKILL.md`：

```json
{
  "name": "weather-daily",
  "description": "每天早上查北京天气并推送穿搭建议",
  "tools": ["http_get", "notify"],
  "notify_channels": [{"type": "webhook", "url": "${TEAM_WEBHOOK_URL}"}],
  "schedules": [{"cron": "0 0 9 * * *", "zone": "Asia/Shanghai",
                 "message": "到点了，按技能说明执行。"}],
  "skill_content": "你是天气助手。被触发时：查北京天气，给出简短穿搭建议，用 notify 推送。"
}
```

**第二步：编排者 `AgentLifecycleService`。** 一次 `create` 从头到尾，中途失败要回滚，别在系统里留半个 Agent：

![POST /agents 编排流程：校验、写 Skill、派生注册、注册定时、200 OK](../../website/public/images/class-30-1.svg)

```java
@Service
public class AgentLifecycleService {

    /** 一句话 → Skill 草稿：一次 LLM 调用，只生成、不落盘（落 llm_calls 审计）。 */
    public String generateSkill(String sentence) {
        return providerService.complete(SKILL_AUTHOR_PROMPT, sentence);   // 返回 SKILL.md 文本
    }

    public AgentView create(CreateAgentRequest req) {
        if (profileRegistry.exists(req.name())) {
            throw new InvalidRequestException("Agent 已存在: " + req.name());   // → 400
        }
        Path skillFile = skillStore.write(req.name(), req.toSkillMarkdown());  // ① 写唯一一份文件
        try {
            Skill skill = skillLoader.load(skillFile);      // ② 解析
            Profile profile = deriveProfile(skill);         // ③ 派生（校验在 register 里，与启动同一套）
            profileRegistry.register(profile);              //    运行时注册（29 节）
            if (!profile.schedules().isEmpty()) {
                agentScheduler.registerProfile(profile);    // ④ 注册定时（29 节）
            }
            return AgentView.from(profile);
        } catch (RuntimeException e) {
            skillStore.delete(skillFile);                   // 中途失败：把已写的文件回滚掉
            throw e;
        }
    }

    public void delete(String name) {
        Profile profile = profileRegistry.get(name)
                .orElseThrow(() -> new AgentNotFoundException(name));   // → 404
        agentScheduler.unregisterProfile(profile);   // 先停定时（用 29 节留的句柄）
        profileRegistry.remove(name);                // 再移出索引
        skillStore.archive(name);                    // 文件移入 .oryxos/archive/，不物理删
    }
}
```

`update` 是组合：正文变了覆写文件（即时生效，不碰注册）；运行时绑定变了就重新 `deriveProfile` + 校验更新索引；`schedules` 变了就 `unregisterProfile` + `registerProfile` 一进一出。`UpdateAgentRequest` 字段与 Create 相同但全可选，只传要改的；`name` 在路径里、不可改。

**第三步：管理平台补上"管"。** 给 26 节 `oryxos-admin-ui` 的提示词追加一页：

```text
新增"Agent 管理"页：
- 表格列出 GET /api/v1/agents，每行带 查看 / 编辑 / 删除（删除前二次确认）；
- "一句话新建"：一个输入框，提交调 POST /api/v1/agents/generate 拿到 Skill 草稿，
  在可编辑文本框里预览（可改 cron / tools）→ 确认后调 POST /api/v1/agents 创建；
- 出错显示 ApiResponse.message。
```

> **对齐既有实现（上面是示意）：** `AgentApiController` 是 26 节已建的（已有 `POST /agents/{name}/invoke`），本节在它上面**加** generate/create/get/update/delete，不新建 controller；统一信封用 26 节的 `ApiResponse`（不是 ErrorBody）。`deriveProfile` / `ProfileRegistry.register` / `AgentScheduler.registerProfile` / `scheduledTasks` 句柄表都是 29 节交付的，本节直接调、不重写。`agentScheduler.unregisterProfile(profile)` 就是遍历 `profile.schedules()`、从 `scheduledTasks` 取出每条 `ScheduledFuture` 调 `cancel(false)` 再移除句柄——注销"定时触发"，不动 `taskLocks`。`generateSkill` 走既有 `ProviderService`（系统默认 provider）、复用而非新造模型调用路径。

**有几样先别做。** 认证鉴权（谁能建 Agent——内网假设，扩展阶段随 API Key / RBAC 补）、Agent 启用 / 停用状态位、创建时的 dry-run 试跑、Skill 版本历史、一句话生成的多轮追问式细化——都放扩展阶段。

**本节交付物**（Spec-Kit 拆解锚点）：

- **代码**：`AgentApiController` 五个端点（generate/create/get/update/delete）；`AgentLifecycleService`（create 编排含回滚 / get / update / delete / generateSkill）；`AgentScheduler.unregisterProfile`；`skillStore` 的 write/archive/delete；`AgentView` 与请求体 DTO。
- **测试（harness）**：`AgentLifecycleServiceTest`、`AgentApiControllerTest`、`AgentGenerateTest`（见下）。
- **目录**：`.oryxos/archive/`（删除归档，不物理删）。
- **前端**：管理平台新增"Agent 管理"页（列表 + 一句话新建 + 编辑 / 删除）。

## 四、怎么验收：把编排与失败路径固化成 harness

这一节的复杂度全在**编排顺序**和**失败回滚**，恰好是单测的主场——`skillStore` / `profileRegistry` / `agentScheduler` / `providerService` 全 mock，用 `InOrder` 钉顺序、用异常注入钉回滚：

| 测试类 | 覆盖的验收点 |
|---|---|
| `AgentLifecycleServiceTest` | create 按序执行；**name 冲突在第一步就拒、一个文件都不写**；**派生 / 注册失败时回滚已写的 Skill 文件**；delete 按"注销定时 → 移出索引 → 归档"顺序；update 改 schedules 时先 unregister 后 register |
| `AgentGenerateTest` | generate 返回一份能被 `SkillLoader` 解析的合法 Skill；**只生成、不落盘、不注册**；LLM 产出非法时 → 400、可读原因 |
| `AgentApiControllerTest`（standalone MockMvc） | 五端点薄转发；冲突 → 400、不存在 → 404，统一走 `ApiResponse` |

两个最值钱的：

```java
@Test
void 注册失败_必须回滚已写的Skill文件_不留半个Agent() {
    doThrow(new ProfileValidationException("bad")).when(profileRegistry).register(any());
    assertThrows(ProfileValidationException.class, () -> lifecycle.create(validRequest("half")));
    verify(skillStore).delete(any());                       // 已写的文件被删回去
    verify(agentScheduler, never()).registerProfile(any()); // 定时根本没走到
    assertFalse(profileRegistry.exists("half"));            // 系统里干干净净
}

@Test
void 删除必须先停定时_再动索引和文件() {
    lifecycle.delete("weather-daily");
    InOrder o = inOrder(agentScheduler, profileRegistry, skillStore);
    o.verify(agentScheduler).unregisterProfile(any());      // 顺序反了：定时还在跑、Profile 已没
    o.verify(profileRegistry).remove("weather-daily");      // ——触发即空指针
    o.verify(skillStore).archive("weather-daily");
}
```

第二个测试的注释说明了为什么顺序值得一个专门断言：先删索引后停定时，中间那个窗口里 cron 一触发就是空指针——这种时序 bug 靠人工验收几乎撞不上，只有测试能稳定钉住。

### 验收清单（每条都成立才算达标）

- **上传即上线**：`POST /api/v1/agents` 成功后不重启，`GET /api/v1/agents` 立刻可见；cron 临时设每分钟，到点真自己跑、webhook 收到（真链路）；
- **一句话生成**：`POST /api/v1/agents/generate` 把一句话变成能解析的 Skill 草稿、不落盘；页面预览可改后再创建；
- **文件落对地方**：创建后目检 `.oryxos/skills/` 有那份 `SKILL.md`，格式跟 29 节手写的一致；
- **删除可追溯**：DELETE 后文件在 `.oryxos/archive/`，历史 `llm_calls` / `tool_invocations` 仍查得到；
- **行为一致**：API 建的 Agent 与手写 Skill 建的，走同一段派生 / 注册代码（harness 钉死）；
- **管理平台**：界面走一遍"一句话新建 → 预览 → 看它自己跑 → 编辑 → 删除"；
- **不回退**：`mvn clean verify` 全绿。

到这里，技术方案里"业务系统通过 API / 一句话定义一个新 Agent 并让它定时自动运行"的完整闭环就合上了。底座（第一部分）和定义 Agent（第二部分）都齐了——下一节交付验收：用这套机制做出天气、日报两个真实 Agent，打包发布第一个版本。
