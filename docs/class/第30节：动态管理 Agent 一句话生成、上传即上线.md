# 动态管理 Agent：一句话生成、上传即上线

上一节把一件东西立住了：**一个 Agent = 一个目录**（`.oryxos/agents/<name>/`，含 `AGENT.md` + 可选 `scripts/`/`skills/`/`REFERENCE.md`），系统扫到就 `deriveProfile` 派生成一个会自己跑的 Agent，外加运行时注册能力（`ProfileRegistry.register/remove`、`AgentScheduler.registerProfile`）。但入口还停在"登录服务器往目录里放东西、还得重启才生效"——业务系统够不着、管理平台只能看不能管。这一节把最后一块拼上：让 Agent 目录**实时生效**（丢进去即上线、免重启），把它包成 REST 端点、加上"**一句话自动生成一个 Agent**"和一个**工作区文件浏览器**——让运营在页面上说一句话就造一个 Agent，或直接往目录丢一个 Agent，两条路殊途同归。

---

## 一、本节目标：Agent 能动态管、免重启

先把终点说清楚。对外要管的只有**一类资源——Agent**（一个目录），不再有别的顶层概念。

> **一个 Agent（一个目录）可以上传即上线、也可以一句话生成；能通过 API / 页面增删改查、全程不重启；管理台还能像文件浏览器一样钻进一个 Agent 目录看它的 `AGENT.md`、脚本、子指令。**

三个能验证的终态：

1. **建 / 传 Agent 即上线**：`POST /api/v1/agents` 带上 Agent 定义（或直接上传一个 Agent 目录），返回 200 后**不重启**，这个 Agent 立刻出现在列表里、到 cron 点自己跑。
2. **丢目录也即上线**：直接往 `.oryxos/agents/` 拷一个 Agent 目录（scp / git / 编辑器），几秒内它就被登记、可用——不走 API 也行。
3. **一句话生成**：在管理台说一句话——"每天早上九点查北京天气，把穿搭建议发到团队群"——系统用 LLM 生成一份规范的 `AGENT.md` 给你预览，改完再一键创建。

这组接口做完，26 节管理平台"只读"的限制同时解除：加"Agent 管理""工作区（文件浏览器）"两页，管理平台从"能看"升级成"真能管"。

## 二、围绕目标要做哪些

### 2.1 一个目录、两条录入路径：上传 + 实时扫描

Agent **只有一个真相源**——`.oryxos/agents/` 目录，一个子目录一个 Agent。往里放 Agent 有**两条录入路径，但殊途同归**：

1. **API 上传**：`POST /api/v1/agents`——本质就是校验 + **把 Agent 目录写进 `.oryxos/agents/<name>/`**（至少一份 `AGENT.md`，可带 `scripts/`/`skills/`）；
2. **手工丢目录**：scp / git pull / 编辑器直接往 `.oryxos/agents/` 写、改、删一个目录。

关键在于**目录被实时监听、不只是启动扫一次**——本节新增一个 `WorkspaceWatcher`（启动先全量扫一遍，之后用 JDK `WatchService` 实时监听 `.oryxos/agents/` 变更），它是**唯一的注册入口**：一个 Agent 目录新增 / 改 / 删 → `deriveProfile` 校验 → 调 29 节的 `ProfileRegistry.register/remove`、`AgentScheduler.registerProfile/unregister`。于是：

- **上传即上线 = 丢目录即上线**：两条路都只是"写一个 Agent 目录进去"，监听器实时拾取、注册，**全程免重启**；
- **一段注册代码**：API 上传写完目录后同步调它、监听器事件也调它——同一个 `register(agentDir)`。这正是 29 节"API 建的和文件建的行为一模一样"落到实处；
- **删除对称**：从 `.oryxos/agents/` 移除 / 归档一个目录 → 监听器注销。

> 这把 29 节列在"先别做"里的**文件监听热加载**提前到本节做——因为"动态管理"的本质就是"**改目录即改运行时**"，没有实时监听，"上传即上线"就名不副实。`WorkspaceWatcher` 是个后台守护线程（跟 25 节 `AgentScheduler` 的调度线程同类，是基础设施守护线程，不是把异步编程模型引进请求链路，不违反宪法七）。

### 2.2 Agent 端点，编排全复用 29 节

没有一件是新能力——`deriveProfile`、注册 / 注销 / 校验全是 29 节立好的。新增的只有一个**编排者** `AgentLifecycleService`，把步骤按顺序串起来、失败回滚。

| 端点 | 内部动作 |
|---|---|
| `POST /api/v1/agents` | 校验（provider 存在、tool 已注册）→ 写 Agent 目录（`AGENT.md` [+ 脚本/子指令]）→ `deriveProfile` → `ProfileRegistry.register` → 有 `schedules` 则 `AgentScheduler.registerProfile` |
| `POST /api/v1/agents/generate` | 一句话 → LLM 生成 `AGENT.md` 草稿**原样返回**（不落盘、不注册） |
| `GET /api/v1/agents` / `GET /{name}` | 查已定义的 Agent |
| `PUT /api/v1/agents/{name}` | 改正文 / provider / notify 覆写即可；`schedules` 变则**先注销旧句柄、再注册新的** |
| `DELETE /api/v1/agents/{name}` | 注销定时（29 节存的 `scheduledTasks` 句柄兑现）→ `ProfileRegistry.remove` → 目录归档 `.oryxos/archive/` |
| `POST /api/v1/agents/{name}/invoke` | 26 节已有的无状态调用，不变 |

判断标准还是 29 节那句：**API 建的和手工丢目录建的，行为必须一模一样**——底层都落到 §2.1 那个 `register(agentDir)`，不另写一套。

### 2.3 一句话生成一个 Agent

"一句话生成"是这一节唯一的新面孔，本质是一次 LLM 调用：把用户那句话 + "OryxOS `AGENT.md` 的格式说明"，让模型产出一份合规的 `AGENT.md`（frontmatter 齐全 + 正文清晰）。

关键是**两步、人在环里**，不要一句话直接上线：

1. `POST /api/v1/agents/generate` 收一句话 → LLM 生成 `AGENT.md` 草稿 → **原样返回给前端预览**，不落盘、不注册；
2. 用户看一眼、可改（尤其 `schedules` 的 cron、`tools` 权限这种敏感项）→ 满意了再调 `POST /api/v1/agents` 正式创建。

为什么必须留这一步：LLM 生成的 cron 可能把时间理解错、`tools` 可能给多了权限——**得人过一眼**。而且生成本身也是一次 LLM 调用，照样落 `llm_calls` 审计（宪法原则五）。

### 2.4 删除与更新的语义，提前定死

- **删 Agent**：先注销定时（29 节存的句柄这时兑现价值）→ 从 `ProfileRegistry` 移除 → **整个 Agent 目录**移进 `.oryxos/archive/`——**不物理删**（它干过的事都在审计表里，定义也应可追溯）。
- **更新 Agent**：改正文覆写 `AGENT.md` 即可（`ContextLoader` 每次重读、即时生效）；`schedules` 变了则**先注销旧句柄、再注册新的**，不然旧 cron 会跟新 cron 一起跑。
- **手工删也对称**：不走 API、直接把 `.oryxos/agents/` 里的目录删掉 / 移走，`WorkspaceWatcher` 收到删除事件一样注销——API 删除只是"删目录 + 归档"的规整版，底层注销是同一段代码。

### 2.5 错误码沿用 26 节口径

name 已存在、`AGENT.md` 字段非法、引用了不存在的 provider——都是 400，`code` 各自区分；查 / 改 / 删一个不存在的 Agent——404。一句话生成时若 LLM 产出的不是合法 `AGENT.md`——也归 400（带可读原因），不发明新状态码；统一走 26 节的 `ApiResponse` 信封。

## 三、代码怎么写

**Controller，照旧很薄。** 在 26 节的 `AgentApiController` 上加方法（它已有 `POST /agents/{name}/invoke`）：

```java
@RestController
@RequestMapping("/api/v1/agents")
public class AgentApiController {

    private final AgentLifecycleService lifecycle;

    @PostMapping("/generate")   // 一句话 → AGENT.md 草稿（只生成、不落盘）
    public ApiResponse<String> generate(@RequestBody GenerateRequest req) {
        return ApiResponse.ok(lifecycle.generate(req.sentence()));
    }

    @PostMapping                 // 正式创建：写 Agent 目录 + 派生注册
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

创建请求体既可给结构化字段，也可直接给一段 `AGENT.md` 正文——两者最终都拼成一个 `.oryxos/agents/<name>/AGENT.md`（要带脚本 / 子指令的复杂 Agent，走上传目录 / 手工丢目录那条路）。

**编排者 `AgentLifecycleService`。** 一次 `create` 从头到尾、中途失败要回滚，别在系统里留半个 Agent：

![POST /agents 编排流程：校验、写 Agent 目录、deriveProfile、注册、注册定时、200 OK](../../website/public/images/class-30-1.svg)

```java
@Service
public class AgentLifecycleService {

    /** 一句话 → AGENT.md 草稿：一次 LLM 调用，只生成、不落盘（落 llm_calls 审计）。 */
    public String generate(String sentence) {
        return providerService.complete(AGENT_AUTHOR_PROMPT, sentence);   // 返回 AGENT.md 文本
    }

    public AgentView create(CreateAgentRequest req) {
        if (profileRegistry.exists(req.name())) {
            throw new InvalidRequestException("Agent 已存在: " + req.name());   // → 400
        }
        Path agentDir = agentStore.write(req.name(), req.toAgentMarkdown());  // ① 写 Agent 目录（AGENT.md）
        try {
            return AgentView.from(register(agentDir));   // ② 走跟监听器完全同一段注册代码
        } catch (RuntimeException e) {
            agentStore.delete(agentDir);                 // 中途失败：把已写的目录回滚掉
            throw e;
        }
    }

    /** 注册单个 Agent 目录——API 上传写完目录后同步调它、WorkspaceWatcher 监听到变更也调它。同一段代码。 */
    Profile register(Path agentDir) {
        Profile profile = agentLoader.deriveProfile(agentDir);   // 解析 + 校验（与启动同一套）
        profileRegistry.register(profile);                       // 运行时注册（29 节）
        if (!profile.schedules().isEmpty()) {
            agentScheduler.registerProfile(profile);             // 注册定时（29 节）
        }
        return profile;
    }

    public void delete(String name) {
        Profile profile = profileRegistry.get(name)
                .orElseThrow(() -> new AgentNotFoundException(name));   // → 404
        agentScheduler.unregisterProfile(profile);   // 先停定时（用 29 节留的句柄）
        profileRegistry.remove(name);                // 再移出索引
        agentStore.archive(name);                    // 整个 Agent 目录移入 .oryxos/archive/，不物理删
    }
}
```

**目录监听器 `WorkspaceWatcher`（本节把"实时扫描"落地）。** 它就是第二条录入路径的执行者——启动全量扫、之后实时监听，**变更事件调的是上面那个同款 `register(agentDir)`**：

```java
// 后台守护线程：跟 AgentScheduler 的调度线程同类，不把异步引进请求链路
void onAgentDirChanged(Path agentDir, Kind kind) {
    try {
        if (kind == ENTRY_DELETE)  unregisterByDir(agentDir);      // 手工删目录 → 注销
        else                        lifecycle.register(agentDir);   // 手工新增/改 → 同一段注册代码
    } catch (RuntimeException e) {
        LOG.warn("Agent 目录 {} 变更处理失败，跳过：{}", agentDir.getFileName(), e.getMessage()); // 单个坏目录不拖垮监听
    }
}
```

一句话：**API 上传和手工丢目录，最后都汇到 `register(agentDir)` 这一个方法**——这就是"两条录入路径、一套行为"的技术保证。

**工作区文件浏览器（本节新端点）。** 管理台要能"看有哪些 Agent，还能钻进一个 Agent 目录看它的 `AGENT.md`、脚本、子指令"，就得有一组**只读**的工作区浏览端点（`WorkspaceApiController`）：

- `GET /api/v1/workspace/tree`：返回 `.oryxos/agents/`（每个 Agent 一个目录，可展开看目录内 `AGENT.md`/`scripts/`/`skills/`/`REFERENCE.md`）与 `.oryxos/archive/` 的目录树；
- `GET /api/v1/workspace/file?path=...`：读某个文件的文本内容。**必做防目录穿越**：把 `path` 解析成绝对路径后校验必须落在 `.oryxos/` 内（`normalize()` 后 `startsWith(root)`），否则 400——这是唯一的安全要点。

**管理平台补上"管"。** 给 26 节 `oryxos-admin-ui` 追加两页：

```text
新增"Agent 管理"页：
- 表格列出 GET /api/v1/agents，每行 查看 / 编辑 / 删除（删前二次确认）；
- "一句话新建 Agent"：输入框 → POST /api/v1/agents/generate 拿 AGENT.md 草稿 →
  可编辑预览（改 cron / tools）→ 确认后 POST /api/v1/agents 创建；
- 出错显示 ApiResponse.message。
新增"工作区"页（文件浏览器）：
- 左侧目录树 GET /api/v1/workspace/tree：agents/（每个 Agent 一个目录，可展开）、archive/；
- 点一个 Agent 目录 → 展开看 AGENT.md / scripts/ / skills/；点一个文件 →
  GET /api/v1/workspace/file 在右侧只读展示内容；
- 只读，本节不做在线编辑（写操作走 Agent 管理页的表单，语义更清楚）。
```

> **对齐既有实现（上面是示意）：** `AgentApiController` 是 26 节已建的（已有 `POST /agents/{name}/invoke`），本节在它上面**加** generate/create/get/update/delete，另新建 `WorkspaceApiController`（只读文件浏览）；统一信封用 26 节的 `ApiResponse`。`AgentLoader.deriveProfile`、`ProfileRegistry.register/remove/exists`、`AgentScheduler.registerProfile` + `scheduledTasks` 句柄表都是 29 节交付的，本节直接调、不重写。`agentScheduler.unregisterProfile(profile)` 遍历 `profile.schedules()`、从 `scheduledTasks` 取每条 `ScheduledFuture` 调 `cancel(false)` 再移除句柄——注销定时触发，不动 `taskLocks`。`generate` 走既有 `ProviderService`（系统默认 provider、落 `llm_calls` 审计）。`WorkspaceWatcher` 用 JDK `java.nio.file.WatchService`，装配层起一个守护线程（跟 `ThreadPoolTaskScheduler` 同类），初始全量扫 + 之后监听。

**有几样先别做。** 认证鉴权（谁能建 Agent / 读文件——内网假设，扩展阶段随 API Key / RBAC 补）、文件浏览器的在线编辑（本版只读）、带脚本的复杂 Agent 走 multipart / zip 上传（本版 JSON create 只写 `AGENT.md`，带脚本的走手工丢目录）、Agent 启用 / 停用状态位、创建时的 dry-run 试跑、Agent 版本历史、一句话生成的多轮追问式细化——都放扩展阶段。

**本节交付物**（Spec-Kit 拆解锚点）：

- **代码**：`AgentApiController` 六个端点（generate/create/get/update/delete/invoke）+ `WorkspaceApiController`（tree/file，只读、防目录穿越）；`AgentLifecycleService`（create 含回滚 / `register(agentDir)` / get / update / delete / generate）；**`WorkspaceWatcher`（启动全量扫 + 实时监听 `.oryxos/agents/` → 同一段 `register(agentDir)`）**；`AgentScheduler.unregisterProfile`；`agentStore` 的 write/archive/delete；`AgentView`/`FileNode` 与请求体 DTO。
- **测试（harness）**：`AgentLifecycleServiceTest`、`WorkspaceWatcherTest`、`WorkspaceApiControllerTest`、`AgentApiControllerTest`、`GenerateTest`（见下）。
- **目录**：`.oryxos/archive/`（删除归档，不物理删）。
- **前端**：管理平台新增"Agent 管理""工作区（文件浏览器）"两页。

## 四、怎么验收：把编排与失败路径固化成 harness

复杂度全在**编排顺序**和**失败回滚**，恰好是单测主场——`agentStore`/`profileRegistry`/`agentScheduler`/`agentLoader`/`providerService` 全 mock，用 `InOrder` 钉顺序、用异常注入钉回滚：

| 测试类 | 覆盖的验收点 |
|---|---|
| `AgentLifecycleServiceTest` | create 按序执行；**name 冲突在第一步就拒、一个目录都不写**；**注册失败时回滚已写的 Agent 目录**；**create 与 watcher 走同一段 `register(agentDir)`**；delete 按"注销定时 → 移出索引 → 归档"顺序；update 改 schedules 时先 unregister 后 register |
| `WorkspaceWatcherTest` | 往 `.oryxos/agents/` **手工丢一个 Agent 目录** → 监听事件触发 `register(agentDir)`、Agent 出现在 `ProfileRegistry`（免重启）；删目录 → 注销；单个坏目录不拖垮监听 |
| `WorkspaceApiControllerTest` | tree 返回 agents/archive 结构、可钻进 Agent 目录列文件；**`file?path=../../etc/passwd` 目录穿越 → 400**（关键回归）；正常文件返回内容 |
| `GenerateTest` | `generate` 产出能被 `AgentLoader` 解析的 `AGENT.md` 草稿；**只生成、不落盘、不注册**；LLM 产出非法 → 400、可读原因 |
| `AgentApiControllerTest`（standalone MockMvc） | 端点薄转发；冲突 → 400、不存在 → 404，统一 `ApiResponse` |

两个最值钱的：

```java
@Test
void 注册失败_必须回滚已写的Agent目录_不留半个Agent() {
    doThrow(new ProfileValidationException("bad")).when(profileRegistry).register(any());
    assertThrows(ProfileValidationException.class, () -> lifecycle.create(validRequest("half")));
    verify(agentStore).delete(any());                       // 已写的目录被删回去
    verify(agentScheduler, never()).registerProfile(any()); // 定时根本没走到
    assertFalse(profileRegistry.exists("half"));            // 系统里干干净净
}

@Test
void 删除必须先停定时_再动索引和目录() {
    lifecycle.delete("weather-daily");
    InOrder o = inOrder(agentScheduler, profileRegistry, agentStore);
    o.verify(agentScheduler).unregisterProfile(any());      // 顺序反了：定时还在跑、Profile 已没
    o.verify(profileRegistry).remove("weather-daily");      // ——触发即空指针
    o.verify(agentStore).archive("weather-daily");
}
```

第二个测试的注释说明了为什么顺序值得专门断言：先删索引后停定时，中间那个窗口里 cron 一触发就是空指针——这种时序 bug 靠人工验收几乎撞不上，只有测试能稳定钉住。

### 验收清单（每条都成立才算达标）

- **建 Agent 即上线**：`POST /api/v1/agents` 成功后不重启，`GET /api/v1/agents` 立刻可见；cron 临时设每分钟，到点真自己跑、webhook 收到（真链路）；
- **丢目录也即上线**：往 `.oryxos/agents/` 直接拷一个 Agent 目录（不走 API），几秒内 `GET /api/v1/agents` 就出现——`WorkspaceWatcher` 实时拾取，与 API 上传殊途同归；
- **一句话生成**：`POST /api/v1/agents/generate` 把一句话变成能解析的 `AGENT.md` 草稿、不落盘；页面预览可改后再创建；
- **目录落对地方**：建 Agent 后 `.oryxos/agents/<name>/` 有 `AGENT.md`，格式跟 29 节手写的一致；
- **文件浏览器**：管理台"工作区"页能列出 `agents/` 的每个 Agent、钻进目录看 `AGENT.md`/脚本内容；`file?path=` 传一个越界路径被 400 挡住；
- **删除可追溯**：DELETE 后目录在 `.oryxos/archive/`，历史 `llm_calls` / `tool_invocations` 仍查得到；
- **行为一致**：API 上传、手工丢目录、启动扫描——三种录入都走同一段 `register(agentDir)`（harness 钉死）；
- **管理平台**：界面走一遍"一句话新建 Agent → 预览 → 看它自己跑 → 工作区里浏览它的目录 → 编辑 → 删除"；
- **不回退**：`mvn clean verify` 全绿。

到这里，"业务系统通过 API / 一句话 / 直接丢目录定义一个 Agent 并让它定时自动运行"的完整闭环就合上了。底座（第一部分）和定义 Agent（第二部分）都齐了——下一节交付验收：用这套机制做出天气、科技日报、GitHub 日报三个真实 Agent，打包发布第一个版本。

---

## 五、实现回写：最终落地与迭代增补

> 上文是本节的**原始设计**（一句话生成整个新 Agent、工作区只读）。随管理台迭代，实际实现做了几处调整并增补了三块能力，以下**以实现为准**；上文保留，作为设计演进的记录。

### 5.1 与原始设计的差异（以实现为准）

| 原始设计（前文） | 最终实现 | 为什么改 |
|---|---|---|
| `POST /agents/generate`：一句话生成**整个新 Agent** → 预览 → `POST /agents` 创建 | 取消该端点。**创建**改为 `POST /agents {name, description}`——后台按模板**脚手架**出完整目录（`AGENT.md` + `scripts/` + `skills/` + `REFERENCE.md`，模板内容）；"**用大模型生成**"下沉为**对某个已存在 Agent** 的按需重生成（见 5.2.4） | 创建要稳定可复现（模板不依赖模型），生成要人在环里改；两者语义分开更清楚 |
| `providerService.complete(prompt, sentence)` | `providerService.chat(genSessionId, 生成用 Profile, ProviderRequest.of(prompt)).text()`——`ProviderService` 没有 `complete`，只有 `chat` | 对齐既有 Provider 接口；仍落 `llm_calls` 审计（原则五） |
| 生成用 provider/model 取"系统默认" | 系统没有"默认 model"（model 只在各 `AGENT.md` 里）。新增配置键 `oryxos.author.provider` / `oryxos.author.model`（provider 缺省取 `oryxos.providers` 第一个；model 留空则生成端点返回可读的 **503**，不向 OpenAI 兼容端点发 `model=null`） | 补上唯一的实现层缺口 |
| 工作区文件浏览器**只读** | 可**编辑保存**：`POST /api/v1/workspace/file {path, content}`（见 5.2.3） | 管理台要能直接改 Agent 文件 |

### 5.2 增补的四块能力（本节原始范围之外）

**5.2.1 每个 Agent 专属记忆（原全局 `MEMORY.md` → per-agent）。** 原设计记忆是全局一份 `.oryxos/memory/MEMORY.md`。改成**跟着 Agent 走**：`.oryxos/agents/<name>/MEMORY.md`（这个 Agent 自己的成长记录，跟它的 `AGENT.md`/`skills/` 同目录，合原则四）。

- **关键障碍**：工具在 `OryxTool.execute(JsonNode)` 时**不知道自己在替哪个 Agent 跑**（接口无执行上下文，`sessionId` 只到 `ToolExecutor` 为止）。
- **解法**：新增 `ToolExecutionContext`（`ThreadLocal<String> agentName`）。同步阻塞模型下（原则七，无 Reactor/异步）一次 ReAct 循环整体跑在一条虚拟线程上，`ToolExecutor` 执行工具**前置入** `profile.name()`、**执行后清除**——`save_memory`/`recall_memory` 据此落到本 Agent 的 `MEMORY.md`。读路径（`buildContext`/`readAll`）不经 `ToolExecutor`，由 `MemoryServiceImpl` 在委托 `store.load` 前后临时置入 Agent 名（`buildContext` 取 `session.profileName()`、`readAll` 取入参）再复原。
- **好处**：`LongTermMemoryStore` SPI 与三档后端（Markdown/SQLite/Mem0）的契约测试**一行不改、完全向后兼容**——无 Agent 上下文时 Markdown 档回退全局路径；只有默认 Markdown 档变成 per-agent。
- 端点：`GET /api/v1/agents/{name}/memory`；**移除**全局 `GET /api/v1/memory`。

**5.2.2 一个 Agent 一个固定会话（上下文可累积）。** 管理台每个 Agent 恰好一条会话——固定 `channel="admin"` + `user="console"`，`profile=<Agent 名>`，`getOrCreate` 幂等 → 恒为同一条 `admin:console:<name>`（`session_id` 拼接规则未动，只固定了 channel/user）。

- `GET /api/v1/agents/{name}/session` → 返回这条会话的 `SessionView`（sessionId + profileName + 最近 ≤100 条消息）；
- `POST /api/v1/agents/{name}/session/messages {content}` → 往这条固定会话发消息、触发 ReAct（同 `invoke` 入口，但落在固定会话里累积上下文）。

**5.2.3 文件可编辑（工作区不再只读）。** `POST /api/v1/workspace/file {path, content}`，复用同一套防目录穿越（`normalize()` 后 `startsWith(oryxosRoot)`，越界 400）。编辑到某个 `agents/<name>/AGENT.md` 时**走 `AgentLifecycleService.update`**（写 + 校验 + 重注册，`schedules` 变更先注销旧句柄）——因为 macOS `WatchService` **不监听子目录内文件的改动**，必须显式重注册；其余文件直接写盘。

**5.2.4 "生成/编辑 Agent"（对已存在 Agent 按描述重生成文件）。** 两个端点：

- `POST /api/v1/agents/{name}/generate-files {description}`：一次 LLM 调用（走 `ProviderService.chat`，落 `llm_calls`）产出 `AGENT.md` → `AgentLoader.parse` 校验能否解析成合法定义（非法 → 400）→ 返回 `{相对路径 → 内容}` 给前端**预览可改**，**不落盘、不注册**；输出的 `provider` 若该 Agent 已存在则沿用其 provider（保持可跑）；模型偶尔多吐的 ``` 代码围栏会被剥掉。
- `POST /api/v1/agents/{name}/files {files}`：保存（可能被用户改过的）一组文件——先校验 `AGENT.md` 可解析（非法 → 400，不写坏目录）→ `agentStore.writeAll` → 覆写后重注册（`schedules` 变更先注销旧的），写入即生效。

### 5.3 最终端点全表（本节实际交付）

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/v1/agents` | 创建：`{name, description}` → 脚手架完整目录 + 派生注册（失败回滚） |
| `GET` | `/api/v1/agents` / `/{name}` | 列表 / 单个详情 |
| `PUT` | `/api/v1/agents/{name}` | 覆写 `AGENT.md`；`schedules` 变则先注销旧再注册新 |
| `DELETE` | `/api/v1/agents/{name}` | 注销定时 → 移出索引 → 目录归档 `.oryxos/archive/` |
| `POST` | `/api/v1/agents/{name}/invoke` | 26 节无状态调用（不变） |
| `GET` | `/api/v1/agents/{name}/memory` | 这个 Agent 的专属记忆（**5.2.1**） |
| `GET` | `/api/v1/agents/{name}/session` | 这个 Agent 的固定管理台会话（**5.2.2**） |
| `POST` | `/api/v1/agents/{name}/session/messages` | 往固定会话发消息、触发 ReAct（**5.2.2**） |
| `POST` | `/api/v1/agents/{name}/generate-files` | 描述 → 大模型生成文件草稿，不落盘（**5.2.4**） |
| `POST` | `/api/v1/agents/{name}/files` | 保存一组文件、写入即生效（**5.2.4**） |
| `GET` | `/api/v1/workspace/tree` | 目录树（agents/ + archive/） |
| `GET` | `/api/v1/workspace/file?path=` | 读文件（防穿越） |
| `POST` | `/api/v1/workspace/file` | 写文件（**5.2.3**，防穿越；AGENT.md 走 update 重注册） |

生成用系统提示词见 `docs/prompt/prompt.md`（`AGENT_AUTHOR_PROMPT`）。

### 5.4 管理台最终形态

不再有"工作区"独立菜单，对外只有一个 **Agent 列表**（含"新建 Agent"= 只填 name + description）。点"详情"进入，是 **5 个 tab**：

- **基本信息**：name/description/provider/model/tools/定时；
- **生成**：填一句描述 → "用大模型生成" → 每个文件可编辑预览 → "保存并生效"（5.2.4）；
- **文件**：左侧文件树、右侧**可编辑**并保存（5.2.3）；
- **会话**：这个 Agent 的**固定会话**，直接显示对话气泡 + 输入框发消息（5.2.2）；
- **记忆**：这个 Agent 的专属 `MEMORY.md`，只读（由 `save_memory` 写入，5.2.1）。
