# 动态管理 Agent：上传能力、一句话生成、即上线

上一节把两件东西立住了：**Skill（可复用能力，Anthropic 完整形态）** 和 **Agent（一份 Profile：身份 + provider + 定时 + 允许的 tool）**，外加运行时注册能力（`ProfileRegistry.register/remove`、`SkillRegistry.register`、`AgentScheduler.registerProfile`）。但入口还停在"登录服务器往目录里放文件、还得重启才生效"——业务系统够不着、管理平台只能看不能管。这一节把最后一块拼上：让工作区目录**实时生效**（丢文件即上线、免重启），再把这两类资源都包成 REST 端点、加上"**一句话自动生成**"和一个**工作区文件浏览器**——让运营在页面上说一句话就装能力 / 造 Agent，或直接往目录丢文件，两条路殊途同归。

---

## 一、本节目标：两类资源都能动态管、免重启

先把终点说清楚。上一节定死了"**Skill ≠ Agent**"，所以对外要管的是**两类资源**，别再糊成一个：

> **能力（Skill）可以上传即装、Agent（Profile）可以一次调用即上线；两者都能通过 API / 页面增删改查、全程不重启；一句话既能生成一个能力、也能生成一个 Agent——但生成前分清你要的是哪一个。**

三个能验证的终态：

1. **上传能力即上线**：`POST /api/v1/skills` 传一个 Skill 目录（`SKILL.md` + 可选 `scripts/`），返回 200 后**不重启**——它立刻进 `SkillRegistry`、出现在每个 Agent 的 `<available_skills>` 索引里，下一次哪个 Agent 用得上就 `use_skill` 加载。
2. **建 Agent 即上线**：`POST /api/v1/agents` 带上 Agent 定义（身份 + provider + 定时 + notify），返回 200 后**不重启**，这个 Agent 立刻出现在列表里、到 cron 点自己跑。
3. **一句话生成**：在管理台说一句话——生成**能力**（"写一个能把 CSV 转成图表的能力"）或生成**Agent**（"每天早上九点查北京天气，把穿搭建议发到团队群"）——系统用 LLM 产出草稿给你预览，改完再一键创建。

**为什么必须分两类资源、而不是一个 `/agents` 全包**：上一节的核心决定就是"能力是能力、Agent 是 Agent"。能力被 N 个 Agent 共享——上传一次，全体可用；Agent 是"谁、何时、以什么身份跑"。把它们塞进一个端点，等于把上一节刚拆干净的两件事又焊回去。

这组接口做完，26 节管理平台"只读"的限制同时解除：加"能力管理""Agent 管理""工作区（文件浏览器）"三页，管理平台从"能看"升级成"真能管"。

## 二、围绕目标要做哪些

### 2.1 一个目录、两条录入路径：上传 + 实时扫描

Agent 和能力**只有一个真相源**——工作区目录（`.oryxos/profiles/` 放 Agent，`.oryxos/skills/` 放能力）。往里放东西有**两条录入路径，但殊途同归**：

1. **API 上传**：`POST /api/v1/agents`（或 `/skills`）——本质就是校验 + **把文件写进这个目录**；
2. **手工丢文件**：scp / git pull / 编辑器直接往目录里写、改、删。

关键在于**目录被实时监听、不只是启动扫一次**——本节新增一个 `WorkspaceWatcher`（启动先全量扫一遍，之后用 JDK `WatchService` 实时监听目录变更），它是**唯一的注册入口**：任何文件 新增 / 改 / 删 → 校验 → 调 29 节的 `ProfileRegistry.register/remove`（或 `SkillRegistry`、`AgentScheduler`）。于是：

- **上传即上线 = 丢文件即上线**：两条路都只是"写文件进目录"，监听器实时拾取、注册，**全程免重启**；
- **一段注册代码**：API 上传写完文件后同步调它、监听器事件也调它——同一个 `register(file)`。这正是 29 节"API 建的和文件建的行为一模一样"落到实处；
- **删除对称**：从目录移除 / 归档文件 → 监听器注销。

> 这把 29 节列在"先别做"里的**文件监听热加载**提前到本节做——因为"动态管理"的本质就是"**改目录即改运行时**"，没有实时监听，"上传即上线"就名不副实。`WorkspaceWatcher` 是个后台守护线程（跟 25 节 `AgentScheduler` 的调度线程同类，是基础设施守护线程，不是把异步编程模型引进请求链路，不违反宪法七）。

### 2.2 两组端点，编排全复用 29 节

没有一件是新能力——注册 / 注销 / 校验全是 29 节立好的。新增的只有两个**编排者**，把步骤按顺序串起来、失败回滚。

![两类资源各一组端点 + 工作区文件浏览器：能力 /skills、Agent /agents、workspace，都汇到"写文件进目录 → register(file)"](../../website/public/images/class-30-2.svg)

**能力（Skill）端点** → `SkillLifecycleService`：

| 端点 | 内部动作 |
|---|---|
| `POST /api/v1/skills` | 校验目录合法（`SkillLoader` 能解析、`name`/`description` 齐）→ 写进 `.oryxos/skills/<name>/` → `SkillRegistry.register` |
| `GET /api/v1/skills` / `GET /{name}` | 查能力库（L1 元数据 + 正文预览） |
| `DELETE /api/v1/skills/{name}` | 从 `SkillRegistry` 移除 → 目录移进 `.oryxos/archive/`（不物理删） |

**Agent（Profile）端点** → `AgentLifecycleService`：

| 端点 | 内部动作 |
|---|---|
| `POST /api/v1/agents` | 校验（provider 存在、tool 已注册）→ 写 Profile YAML → `ProfileRegistry.register` → 有 `schedules` 则 `AgentScheduler.registerProfile` |
| `GET /api/v1/agents` / `GET /{name}` | 查已定义的 Agent |
| `PUT /api/v1/agents/{name}` | 改身份 / provider / notify 覆写即可；`schedules` 变则**先注销旧句柄、再注册新的** |
| `DELETE /api/v1/agents/{name}` | 注销定时（29 节存的 `scheduledTasks` 句柄兑现）→ `ProfileRegistry.remove` → Profile 归档 |
| `POST /api/v1/agents/{name}/invoke` | 26 节已有的无状态调用，不变 |

两组端点各转发到自己的编排者（`AgentLifecycleService` / `SkillLifecycleService`），底层都落到 §2.1 那个 `register(file)`——校验、注册、注销全是 29 节立好的，不另写一套。

### 2.3 一句话生成：先分清生成能力还是 Agent

"一句话生成"是这一节唯一的新面孔，本质是一次 LLM 调用：把用户那句话 + 对应的格式说明，让模型产出合规草稿。**关键是先分清生成物**，两者格式和用途完全不同：

- **生成 Agent（Profile）**：`POST /api/v1/agents/generate`，输入"每天早上九点查天气推群" → 产出一份 **Profile 草稿**（identity.prompt + provider + `schedules` + notify + tool 白名单）。这是"谁、何时、干什么"。
- **生成能力（Skill）**：`POST /api/v1/skills/generate`，输入"写一个把 CSV 转图表的能力" → 产出一份 **`SKILL.md` 草稿**（`name` + `description` + 正文；复杂逻辑还可提示配 `scripts/`）。这是"一项可复用专长"。

两者都**两步、人在环里**，不要一句话直接上线：

1. `generate` 收一句话 → LLM 生成草稿 → **原样返回给前端预览**，不落盘、不注册；
2. 用户看一眼、可改（尤其 Agent 的 `schedules` cron、tool 白名单，或 Skill 里脚本的权限）→ 满意了再调对应的 `POST` 正式创建。

为什么必须留这一步：LLM 生成的 cron 可能把时间理解错、tool 可能给多了权限——**得人过一眼**。而且生成本身也是一次 LLM 调用，照样落 `llm_calls` 审计（宪法原则五）。

### 2.4 删除与更新的语义，提前定死

- **删 Agent**：先注销定时（29 节存的句柄这时兑现价值）→ 从 `ProfileRegistry` 移除 → Profile YAML 移进 `.oryxos/archive/`——**不物理删**（它干过的事都在审计表里，定义也应可追溯）。
- **删能力**：从 `SkillRegistry` 移除 → 目录移进 `.oryxos/archive/`。注意能力可能正被某些 Agent 用着——核心阶段简单处理：删掉后索引里没了、`use_skill` 未知 name 报错即可（"删一个在用的能力"的引用检查放扩展）。
- **更新 Agent**：改身份 / provider 覆写 Profile 即可；`schedules` 变了则**先注销旧句柄、再注册新的**，不然旧 cron 会跟新 cron 一起跑。
- **更新能力**：覆写 `SKILL.md` 即可——`use_skill` 每次现取正文，天然即时生效（跟 17 节 `ContextLoader` 无缓存同理）。
- **手工删也对称**：不走 API、直接把目录里的文件删掉 / 移走，`WorkspaceWatcher` 收到 `ENTRY_DELETE` 一样注销——API 删除只是"删文件 + 归档"的规整版，底层注销是同一段代码。

### 2.5 错误码沿用 26 节口径

name 已存在、Profile / Skill 字段非法、引用了不存在的 provider——都是 400，`code` 各自区分；查 / 改 / 删一个不存在的资源——404。一句话生成时若 LLM 产出的不是合法草稿——也归 400（带可读原因），不发明新状态码；统一走 26 节的 `ApiResponse` 信封。

## 三、代码怎么写

**Controller，照旧很薄。** 两个 controller，各转发到自己的编排者。以 Agent 侧为例（在 26 节的 `AgentApiController` 上加方法）：

```java
@RestController
@RequestMapping("/api/v1/agents")
public class AgentApiController {

    private final AgentLifecycleService lifecycle;

    @PostMapping("/generate")   // 一句话 → Profile 草稿（只生成、不落盘）
    public ApiResponse<String> generate(@RequestBody GenerateRequest req) {
        return ApiResponse.ok(lifecycle.generateAgent(req.sentence()));
    }

    @PostMapping                 // 正式创建：写 Profile YAML + 注册
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

Agent 创建请求体 = 一份 Profile 的字段（**不含 `skills` 白名单**——全体能力共享，见 29 节；`tools` 里默认带上 `use_skill` 才能加载能力）：

```json
{
  "name": "weather-daily",
  "description": "每天早上查北京天气并推送穿搭建议",
  "identity": {"agent_name": "天气小欧",
               "prompt": "你是天气助手。被触发时：查北京天气，给出简短穿搭建议，用 notify 推送。"},
  "provider": {"name": "deepseek", "model": "deepseek-chat"},
  "tools": ["http_get", "notify", "use_skill"],
  "notify_channels": [{"type": "webhook", "url": "${TEAM_WEBHOOK_URL}"}],
  "schedules": [{"id": "weather-morning", "cron": "0 0 9 * * *",
                 "zone": "Asia/Shanghai", "message": "到点了，按你的身份说明执行。"}]
}
```

**编排者 `AgentLifecycleService`。** 一次 `create` 从头到尾、中途失败要回滚，别在系统里留半个 Agent：

![POST /agents 编排流程：校验、写 Profile、注册、注册定时、200 OK](../../website/public/images/class-30-1.svg)

```java
@Service
public class AgentLifecycleService {

    /** 一句话 → Profile 草稿：一次 LLM 调用，只生成、不落盘（落 llm_calls 审计）。 */
    public String generateAgent(String sentence) {
        return providerService.complete(AGENT_AUTHOR_PROMPT, sentence);   // 返回 Profile YAML 文本
    }

    public AgentView create(CreateAgentRequest req) {
        if (profileRegistry.exists(req.name())) {
            throw new InvalidRequestException("Agent 已存在: " + req.name());   // → 400
        }
        Path profileFile = profileStore.write(req.name(), req.toProfileYaml());  // ① 只做一件事：写文件进目录
        try {
            return AgentView.from(register(profileFile));   // ② 走跟监听器完全同一段注册代码
        } catch (RuntimeException e) {
            profileStore.delete(profileFile);               // 中途失败：把已写的文件回滚掉
            throw e;
        }
    }

    /** 注册单个 Profile 文件——API 上传写完文件后同步调它、WorkspaceWatcher 监听到变更也调它。同一段代码。 */
    Profile register(Path profileFile) {
        Profile profile = profileLoader.load(profileFile);   // 解析（校验在 ProfileRegistry.register 里，与启动同一套）
        profileRegistry.register(profile);                   // 运行时注册（29 节）
        if (!profile.schedules().isEmpty()) {
            agentScheduler.registerProfile(profile);         // 注册定时（29 节）
        }
        return profile;
    }

    public void delete(String name) {
        Profile profile = profileRegistry.get(name)
                .orElseThrow(() -> new AgentNotFoundException(name));   // → 404
        agentScheduler.unregisterProfile(profile);   // 先停定时（用 29 节留的句柄）
        profileRegistry.remove(name);                // 再移出索引
        profileStore.archive(name);                  // Profile 移入 .oryxos/archive/，不物理删
    }
}
```

`SkillLifecycleService` 是对称的一套(`install` 写目录 + `register(dir)`、`delete` 移除 + 归档、`generateSkill` 生成 `SKILL.md` 草稿)，因为不涉及定时，比 Agent 侧还简单。

**目录监听器 `WorkspaceWatcher`（本节把"实时扫描"落地）。** 它就是第二条录入路径的执行者——启动全量扫、之后实时监听，**变更事件调的是上面那个同款 `register(file)`**：

```java
// 后台守护线程：跟 AgentScheduler 的调度线程同类，不把异步引进请求链路
void onFileChanged(Path file, Kind kind) {
    try {
        if (kind == ENTRY_DELETE)      unregisterByFile(file);       // 手工删文件 → 注销
        else                            lifecycle.register(file);     // 手工新增/改 → 同一段注册代码
    } catch (RuntimeException e) {
        LOG.warn("工作区文件 {} 变更处理失败，跳过：{}", file, e.getMessage());  // 单个坏文件不拖垮监听
    }
}
```

一句话：**API 上传和手工丢文件，最后都汇到 `register(file)` 这一个方法**——这就是"两条录入路径、一套行为"的技术保证。

**工作区文件浏览器（本节新端点）。** 管理台要能"看目录里有哪些 Agent / 能力，还能钻进去看文件"，就得有一组**只读**的工作区浏览端点（`WorkspaceApiController`）：

- `GET /api/v1/workspace/tree`：返回 `.oryxos/` 下 `profiles/`、`skills/`、`archive/` 的目录树（Agent 是单个 Profile 文件、能力是目录带 `SKILL.md`/`scripts/`）；
- `GET /api/v1/workspace/file?path=...`：读某个文件的文本内容。**必做防目录穿越**：把 `path` 解析成绝对路径后校验必须落在 `.oryxos/` 内（`normalize()` 后 `startsWith(root)`），否则 400——这是唯一的安全要点。

**管理平台补上"管"。** 给 26 节 `oryxos-admin-ui` 追加三页：

```text
新增"能力管理"页：
- 表格列出 GET /api/v1/skills，每行 查看正文 / 删除；
- "一句话新建能力"：输入框 → POST /api/v1/skills/generate 拿 SKILL.md 草稿 →
  可编辑预览 → 确认后 POST /api/v1/skills 上传（= 写进 .oryxos/skills/，监听器即上线）。
新增"Agent 管理"页：
- 表格列出 GET /api/v1/agents，每行 查看 / 编辑 / 删除（删前二次确认）；
- "一句话新建 Agent"：输入框 → POST /api/v1/agents/generate 拿 Profile 草稿 →
  可编辑预览（改 cron / tools）→ 确认后 POST /api/v1/agents 创建；
- 出错显示 ApiResponse.message。
新增"工作区"页（文件浏览器）：
- 左侧目录树 GET /api/v1/workspace/tree：profiles/（Agent 列表）、skills/（能力列表，可展开看目录内文件）、archive/；
- 点一个文件 → GET /api/v1/workspace/file 在右侧只读展示内容（Profile YAML / SKILL.md / 脚本）；
- 只读，本节不做在线编辑（写操作走 Agent/能力管理页的表单，语义更清楚）。
```

> **对齐既有实现（上面是示意）：** `AgentApiController` 是 26 节已建的（已有 `POST /agents/{name}/invoke`），本节在它上面**加** generate/create/get/update/delete，另新建 `SkillApiController` 与 `WorkspaceApiController`（只读文件浏览）；统一信封用 26 节的 `ApiResponse`。`ProfileRegistry.register/remove/exists`、`SkillRegistry.register/remove`、`AgentScheduler.registerProfile`、`scheduledTasks` 句柄表都是 29 节交付的，本节直接调、不重写。`agentScheduler.unregisterProfile(profile)` 遍历 `profile.schedules()`、从 `scheduledTasks` 取每条 `ScheduledFuture` 调 `cancel(false)` 再移除句柄——注销定时触发，不动 `taskLocks`。`generateAgent`/`generateSkill` 走既有 `ProviderService`（系统默认 provider、落 `llm_calls` 审计）。`WorkspaceWatcher` 用 JDK `java.nio.file.WatchService`，装配层起一个守护线程（跟 `ThreadPoolTaskScheduler` 同类），初始全量扫 + 之后监听。

**有几样先别做。** 认证鉴权（谁能建 Agent / 传能力 / 读文件——内网假设，扩展阶段随 API Key / RBAC 补）、文件浏览器的在线编辑（本版只读）、Agent 启用 / 停用状态位、创建时的 dry-run 试跑、Skill / Profile 版本历史、"删一个在用能力"的引用检查、一句话生成的多轮追问式细化——都放扩展阶段。

**本节交付物**（Spec-Kit 拆解锚点）：

- **代码**：`AgentApiController` 五个端点 + `SkillApiController`（install/list/get/delete/generate）+ `WorkspaceApiController`（tree/file，只读、防目录穿越）；`AgentLifecycleService`（create 含回滚 / `register(file)` / get / update / delete / generateAgent）；`SkillLifecycleService`（install / delete / generateSkill）；**`WorkspaceWatcher`（启动全量扫 + 实时监听目录变更 → 同一段 `register(file)`）**；`AgentScheduler.unregisterProfile`；`profileStore`/`skillStore` 的 write/archive/delete；`AgentView`/`SkillView`/`FileNode` 与请求体 DTO。
- **测试（harness）**：`AgentLifecycleServiceTest`、`SkillLifecycleServiceTest`、`WorkspaceWatcherTest`、`WorkspaceApiControllerTest`、`AgentApiControllerTest`、`SkillApiControllerTest`、`GenerateTest`（见下）。
- **目录**：`.oryxos/archive/`（删除归档，不物理删）。
- **前端**：管理平台新增"能力管理""Agent 管理""工作区（文件浏览器）"三页。

## 四、怎么验收：把编排与失败路径固化成 harness

复杂度全在**编排顺序**和**失败回滚**，恰好是单测主场——`profileStore`/`skillStore`/`profileRegistry`/`skillRegistry`/`agentScheduler`/`providerService` 全 mock，用 `InOrder` 钉顺序、用异常注入钉回滚：

| 测试类 | 覆盖的验收点 |
|---|---|
| `AgentLifecycleServiceTest` | create 按序执行；**name 冲突在第一步就拒、一个文件都不写**；**注册失败时回滚已写的 Profile**；**create 与 watcher 走同一段 `register(file)`**；delete 按"注销定时 → 移出索引 → 归档"顺序；update 改 schedules 时先 unregister 后 register |
| `SkillLifecycleServiceTest` | install 写目录 + 进 `SkillRegistry`；非法目录（缺 name/description）→ 400、不落盘；delete 从索引移除 + 归档 |
| `WorkspaceWatcherTest` | 往目录**手工丢一个 Profile 文件** → 监听事件触发 `register(file)`、Agent 出现在 `ProfileRegistry`（免重启）；删文件 → 注销；单个坏文件不拖垮监听 |
| `WorkspaceApiControllerTest` | tree 返回 profiles/skills/archive 结构；**`file?path=../../etc/passwd` 目录穿越 → 400**（关键回归）；正常文件返回内容 |
| `GenerateTest` | `generateAgent` 产出能被 `ProfileLoader` 解析的 Profile 草稿；`generateSkill` 产出能被 `SkillLoader` 解析的 `SKILL.md`；**只生成、不落盘、不注册**；LLM 产出非法 → 400、可读原因 |
| `AgentApiControllerTest` / `SkillApiControllerTest`（standalone MockMvc） | 端点薄转发；冲突 → 400、不存在 → 404，统一 `ApiResponse` |

两个最值钱的：

```java
@Test
void 注册失败_必须回滚已写的Profile_不留半个Agent() {
    doThrow(new ProfileValidationException("bad")).when(profileRegistry).register(any());
    assertThrows(ProfileValidationException.class, () -> lifecycle.create(validRequest("half")));
    verify(profileStore).delete(any());                     // 已写的文件被删回去
    verify(agentScheduler, never()).registerProfile(any()); // 定时根本没走到
    assertFalse(profileRegistry.exists("half"));            // 系统里干干净净
}

@Test
void 删除必须先停定时_再动索引和文件() {
    lifecycle.delete("weather-daily");
    InOrder o = inOrder(agentScheduler, profileRegistry, profileStore);
    o.verify(agentScheduler).unregisterProfile(any());      // 顺序反了：定时还在跑、Profile 已没
    o.verify(profileRegistry).remove("weather-daily");      // ——触发即空指针
    o.verify(profileStore).archive("weather-daily");
}
```

第二个测试的注释说明了为什么顺序值得专门断言：先删索引后停定时，中间那个窗口里 cron 一触发就是空指针——这种时序 bug 靠人工验收几乎撞不上，只有测试能稳定钉住。

### 验收清单（每条都成立才算达标）

- **上传能力即上线**：`POST /api/v1/skills` 成功后不重启，它出现在 `GET /api/v1/skills` 和每个 Agent 的能力索引里；
- **建 Agent 即上线**：`POST /api/v1/agents` 成功后不重启，`GET /api/v1/agents` 立刻可见；cron 临时设每分钟，到点真自己跑、webhook 收到（真链路）；
- **手工丢文件也即上线**：往 `.oryxos/profiles/` 直接拷一个 Profile YAML（不走 API），几秒内 `GET /api/v1/agents` 就出现——`WorkspaceWatcher` 实时拾取，与 API 上传殊途同归；
- **一句话生成分得清**：`/agents/generate` 产出 Profile 草稿、`/skills/generate` 产出 `SKILL.md` 草稿，都不落盘、页面预览可改后再创建；
- **文件落对地方**：建 Agent 后 `.oryxos/profiles/` 有 Profile YAML；传能力后 `.oryxos/skills/<name>/` 有目录，格式跟 29 节手写的一致；
- **文件浏览器**：管理台"工作区"页能列出 profiles/ 的 Agent 与 skills/ 的能力目录、点开看文件内容；`file?path=` 传一个越界路径被 400 挡住；
- **删除可追溯**：DELETE 后文件在 `.oryxos/archive/`，历史 `llm_calls` / `tool_invocations` 仍查得到；
- **行为一致**：API 上传、手工丢文件、启动扫描——三种录入都走同一段 `register(file)`（harness 钉死）；
- **管理平台**：界面走一遍"一句话新建能力 / Agent → 预览 → 看 Agent 自己跑 → 工作区里浏览它的文件 → 编辑 → 删除"；
- **不回退**：`mvn clean verify` 全绿。

到这里，"业务系统通过 API / 一句话 / 直接丢文件装能力、定义 Agent 并让它定时自动运行"的完整闭环就合上了。底座（第一部分）和定义 Agent / 能力（第二部分）都齐了——下一节交付验收：用这套机制做出天气、科技日报、GitHub 日报三个真实 Agent，打包发布第一个版本。
