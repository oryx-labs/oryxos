# 插件化 Agent：一份 Skill 定义一个会自己跑的 Agent

前面 28 节做完，一个事实值得停下来看清楚：我们搭了一整套能跑、跑得稳的系统，但**还没有真正"定义"过一个业务 Agent**——测试用的配置都是顺手写的。这一节讲这门课最关键的一次视角转换：**底座是底座、Agent 是 Agent**；然后动手做第一件"在底座上定义 Agent"的事——**丢一份 Skill 进去，就长出一个会自己跑的 Agent**。

---

## 一、本节目标：一份 Skill，定义一个会自己跑的 Agent

先把终点说清楚，一句话：

> **让"定义一个业务 Agent"退化成"写一份 Skill"——这份 Skill 自己说清楚"做什么"（正文）和"怎么跑、什么时候跑"（frontmatter）；系统自动扫描 skills 目录，把每份 Skill 变成一个已注册、到点自己跑的 Agent。全程不写一行 Java、不动一行底座、（这一节之后）连重启都免了。**

先说清"底座 vs Agent"这个分界——这是"OS"这个词的关键。类比操作系统：进程调度、内存管理、系统调用是 OS 的能力，但 OS 本身不是任何一个程序，程序是跑在它上面的可执行文件。对应过来：Provider、ReAct、Tool、Memory、Sandbox、定时、Web Service 是 OryxOS 的内核能力，而 **Skill 就是这台 OS 上的"可执行文件"**——业务方定义一个新 Agent，写的就是一份 Skill。

"真能用"给个能验证的终态：往 `.oryxos/skills/` 丢一份 `SKILL.md` → 它出现在 Agent 列表里 → 到它自己声明的时间点，自动跑完"想 → 查 → 答 → 推送"、审计留账 → 改一下它的正文，下一轮触发即时生效。

![一份 Skill 派生出一个 Agent](../../website/public/images/class-29-1.svg)

**这一节最关键的观念转变**：Skill 不再只是"给 LLM 看的任务说明"，而是**一个 Agent 的完整声明**——frontmatter 是机器可读的配置（用哪个模型、能用哪些工具、往哪推、什么时候跑），正文是给 LLM 的任务指令。这跟 Claude 自己的 SKILL.md 是同一个形态：frontmatter 管配置、正文管内容。但底线不变（宪法原则四）：**正文仍然只走 ContextLoader 注入 system prompt，Skill 永远不是一个 Tool**——新增的只是"多一个扫描器去读 frontmatter，把它注册成一个 Agent"。

## 二、围绕目标要做哪些

要让"丢一份 Skill = 多一个会自己跑的 Agent"成立，做四件事：定清楚 Skill 长什么样（2.1），让系统自动扫描、把 Skill 变成 Agent（2.2），让定时从 Skill 里生效（2.3），补运行时注册、去掉"重启"这条尾巴（2.4）。

**贯穿始终的一条原则：不重写底座，只给"Profile"添一个新来源。** 底座（16~28 节）的一切——`AgentService`、`ReActLoop`、`PromptBuilder`、`AgentScheduler`——都是吃 `Profile` 这个值对象的。所以我们一行底座都不动，只是把"从 YAML 文件读 Profile"扩展成"也能从 Skill 派生出 Profile"，两个来源汇进同一个注册表、走同一套校验。

### 2.1 Skill 长什么样：frontmatter（声明）+ 正文（指令）

一份 Skill 就是一个 `.oryxos/skills/<name>.md` 文件，两段结构：

```markdown
---
name: daily-greeting              # 唯一标识，也是这个 Agent 的名字
description: 每天早上向团队群问好
provider: deepseek                # 可选，缺省用系统默认 provider
model: deepseek-chat              # 可选
tools:                            # 这个 Agent 能用的工具（最小权限，20 节）
  - notify
notify_channels:                  # 可选，结果往哪推（19 节）
  - type: webhook
    url: ${TEAM_WEBHOOK_URL}
schedules:                        # ★ 定时写在 Skill 自己里，不再另配 Profile
  - cron: "0 0 9 * * *"
    zone: Asia/Shanghai
    message: 到点了，按你的技能说明执行晨间问候。
---

你是团队的晨间助手。被触发时：
1. 组织一句简短、友好的早安问候，包含今天的日期和星期几；
2. 调用 notify 工具把问候发送出去；
3. 不需要等待任何回复。
```

- **frontmatter = 这个 Agent 的运行声明**：`name` / `description` 是身份；`provider` / `model` / `tools` / `notify_channels` 是运行时绑定（不填就走系统默认）；`schedules` 是"什么时候自己跑"。**这里是本节相对以前最大的一处变化：定时从 Profile 挪进了 Skill**——因为"这个 Agent 什么时候该做事"，本就属于"这个 Agent 是什么"，跟它写在一处最自然。
- **正文 = 给 LLM 的任务指令**：写给"人"看的清晰程度，就是它执行的准确程度。正文由 `ContextLoader` 注入 system prompt——跟 22 节 Memory 一样不缓存，改完保存、下一轮触发就是新的。

存放位置固定在 `.oryxos/skills/`，**一个文件一个 Agent**。业务方要新增一个 Agent，从头到尾就写这一份文件。

### 2.2 自动扫描：把每份 Skill 变成一个已注册的 Agent

系统启动时（以及 30 节通过 API 新增时）扫描 `.oryxos/skills/`，对每份 Skill 做三步——**这三步就是"插件化"的机制本体**：

![扫描 skills 目录 → 解析 → 派生 Profile → 注册](../../website/public/images/class-29-2.svg)

1. **解析**：`SkillLoader` 读文件，拆出 frontmatter（YAML）和正文；
2. **派生**：`deriveProfile(skill)` 把 frontmatter 映射成一个底座认识的 `Profile` 值对象（name / provider / tools / notify_channels / schedules 一一对应，并把这份 Skill 绑定到它的 `skills` 字段），正文留给 ContextLoader 用；
3. **注册**：把派生出的 Profile 塞进 `ProfileRegistry`（复用 16 节那套校验：provider 存在、tool 已注册），有 `schedules` 的再交给 `AgentScheduler` 注册定时。

**为什么走"派生 Profile"、而不是另起一套？** 因为底座 16~28 节的一切都吃 `Profile`。派生成 Profile，就等于让 Skill 定义的 Agent **零改动复用整台底座**。于是系统里有了两个 Profile 来源：手写的 Profile YAML（通用助手骨架，前面几节那种）和 Skill 派生的 Profile（业务 Agent）——**两者汇进同一个 `ProfileRegistry`、过同一套校验**，下游根本不关心它从哪来。这跟 27/28 节"复用同一个引擎"是同一种思路。

> 一个小细节：`ContextLoader` 注入 prompt 时只注**正文**，frontmatter 交给 `SkillLoader` 解析、不进 prompt——别让一段 YAML 配置漏进模型的上下文。

### 2.3 定时从 Skill 里生效

定时现在写在 Skill 的 frontmatter、并被派生进 Profile 的 `schedules`，所以 `AgentScheduler` **一行都不用改**——它照旧遍历 Profile 的 `schedules` 去注册 cron（25 节）。变的只是"定时的源头"：以前从 Profile YAML 来，现在从 Skill 来。效果正是你要的——**一份 Skill 声明了 `schedules`，系统扫到它就自动到点跑**，不用再单独配一个 Profile。

### 2.4 补运行时注册，去掉"重启"这条尾巴

到这里还差最后一环。Skill 正文改了是即时生效的（ContextLoader 不缓存），但**新扫进来一份 Skill 却要重启**——因为 `ProfileRegistry` 和 `AgentScheduler` 目前都只在启动时注册一次。这个不对称，就是本节代码要补的短板：给两者各加一个**运行时注册方法**，让"启动时扫描"和"运行时新增"走同一段代码。

![只在启动时注册 vs 本节补上的运行时注册方法](../../website/public/images/class-29-3.svg)

```java
// ProfileRegistry：启动扫描和运行时注册，共用同一个入口
public void register(Profile profile) {
    profileValidator.validate(profile);        // 复用启动时那套校验：
    profiles.put(profile.name(), profile);     // provider 存在、tool 已注册、skill 文件存在
}
```

```java
// AgentScheduler：把 25 节 registerAll() 里的循环体，抽成"注册单个 Profile"
public void registerProfile(Profile profile) {
    for (ScheduleConfig sc : profile.schedules()) {
        ScheduledFuture<?> future = taskScheduler.schedule(
            () -> runOnce(profile, sc),
            new CronTrigger(sc.cron(), resolveZone(sc.zone())));
        scheduledTasks.put(sc.id(), future);   // 留着句柄，30 节注销/更新要用
    }
}
```

两处共同的讲究：**运行时路径和启动路径必须是同一段代码。** 要是 `register()` 另写一套校验、`registerProfile()` 另写一套注册，两条路径迟早行为漂移——"API 建的 Agent 和文件建的 Agent 表现不一样"这种 bug 最难查。`scheduledTasks` 这张句柄表是给 30 节埋的：更新、删除 Agent 时要能把旧定时注销掉，现在不留句柄，将来就只能重启。

> **对齐既有实现（上面是示意，按 16/25 节已落地的真实代码改，别照抄示意）：**
> - `ProfileRegistry`（16 节）现在是**不可变**的（构造注入 `Map.copyOf`，无 `register`）。本节把它改成持有可变并发 Map，新增 `register(Profile)` / `remove(String)` / `exists(String)`——这是 16 节 javadoc 里就预告过的"29 节补运行时 register"，属课件明列的改造点。校验复用 16 节 `ProfileLoader` 那套（provider 存在 / tool 已注册 / skill 文件存在），不新写。
> - `AgentScheduler`（25 节）是**构造注入的纯 POJO**，启动注册走 `@Bean(initMethod="registerAll")`（**不是** `@PostConstruct`，别把 Spring 注解塞回 core 类）；`ScheduleConfig` 是 `Profile.ScheduleConfig` **record**，用 `sc.id()/cron()/zone()/message()`、`profile.name()/schedules()`（**不是** getter），时区经 `ZoneId.of(sc.zone())`、空则系统默认。本节把 `registerAll` 里的循环体抽成 `registerProfile(Profile)`，保留 25 节**每条 try/catch 跳过非法 cron**（FR-007）；`registerAll` 改成 `profileRegistry.all().forEach(this::registerProfile)`。
> - 25 节已有一张按任务 id 的 `taskLocks`（防重叠锁）。本节**新增**一张 `scheduledTasks`（`Map<String, ScheduledFuture<?>>` 句柄表，为 30 节注销用）——两张表并存、各管一事，别合并。
> - `SkillLoader` / `deriveProfile` 归 `oryxos-core`（跟 `ContextLoader`、`Profile` 同域）；启动时扫描 `.oryxos/skills/` 并逐个 `register` 的接线，放在装配层（`oryxos-cli` 的 `OryxOsRuntime`），跟"启动扫 Profile YAML"并列成两个来源。

**有几样先别做。** Skill 的版本管理、Skill 市场 / 共享、Profile YAML 与 Skill 同名时的合并策略（本节先规定：**同名即冲突、加载报错**）、文件监听热加载——都放扩展阶段。这一节做到"一份 Skill 自动派生成一个会自己跑的 Agent + 运行时注册能力就位"就够。

**本节交付物**（Spec-Kit 拆解锚点）：

- **代码**：`SkillLoader`（解析 frontmatter + 正文）；`deriveProfile(Skill) → Profile`；启动扫描 `.oryxos/skills/` 并注册（装配层）；`ProfileRegistry.register/remove/exists`（16 节改造点，改可变 Map）；`AgentScheduler.registerProfile` + `scheduledTasks` 句柄表；`ContextLoader` 注入时剥掉 frontmatter、只注正文。
- **测试（harness）**：`SkillLoaderTest`、`DeriveProfileTest`、`SkillScanRegisterTest`、`ProfileRegistryRuntimeTest`、`AgentSchedulerRegisterTest`、`SkillConsistencyTest`（见下）。
- **文件**：`daily-greeting` 示例——**一份** `SKILL.md`（frontmatter 含 `schedules`），手动路径的参照物。
- **校验**：`tools` 声明里若有底座未注册的工具 → 加载时明确告警。

## 三、怎么验收：把机制固化成 harness

这一节的机制是"扫描 → 派生 → 注册 → 到点自己跑"，harness 就要把这条链的每一环钉死，尤其是**"两条来源（Skill 派生 / YAML 加载）走同一套校验"**——将来谁给某一条单开逻辑，测试立刻红：

| 测试类 | 覆盖的验收点 |
|---|---|
| `SkillLoaderTest` | 正确拆出 frontmatter（YAML）与正文；缺 `name` 等必填项报错清晰 |
| `DeriveProfileTest` | frontmatter 各字段正确映射到 `Profile`；**`schedules` 被原样带进派生的 Profile**（定时来自 Skill 的直接证据） |
| `SkillScanRegisterTest` | 扫描一个放了 N 份 Skill 的目录 → `ProfileRegistry` 里出现 N 个 Agent、带 `schedules` 的都进了 `AgentScheduler` |
| `ProfileRegistryRuntimeTest` | `register()` 后立即 `get()` 可见；**非法配置的报错与启动路径完全一致**（同一异常类型 + 同一消息——复用同一套校验的直接证据） |
| `AgentSchedulerRegisterTest` | `registerProfile` 后 `scheduledTasks` 有句柄（30 节注销的前提）；传给 `taskScheduler` 的 cron / 时区与声明一致 |
| `SkillConsistencyTest` | Skill 的 `tools` 里有底座没注册的工具 → 加载时明确告警 |

最值钱的一个，钉的就是"两条来源同一套校验"：

```java
@Test
void skill派生与yaml加载_必须过同一套校验() {
    // 一个引用了不存在 provider 的定义，两条来源都必须以同样的方式拒绝
    var yamlEx    = assertThrows(ProfileValidationException.class,
        () -> profileLoader.load(badYaml));                        // YAML 来源
    var skillEx   = assertThrows(ProfileValidationException.class,
        () -> profileRegistry.register(deriveProfile(badSkill)));  // Skill 来源
    assertEquals(yamlEx.getMessage(), skillEx.getMessage());       // 行为漂移在此现形
}
```

"Skill 正文改了即时生效"不用在这节重测——17 节 `ContextLoaderTest` 的无缓存回归已经钉死了，harness 不重复钉同一颗钉子。

### 验收清单（每条都成立才算达标）

- **一份文件定义一个 Agent**：往 `.oryxos/skills/` 放一份 `SKILL.md`，`oryxos profile list` / `GET /api/v1/profiles` 里就出现这个 Agent，全程没写一行 Java；
- **定时来自 Skill**：Skill 的 `schedules` 到点真的自己触发、webhook 收到、审计有账（真模型链路）；
- **正文即时生效**：改 Skill 正文不重启，下一次触发就用了新说明；
- **两条来源同规矩**：Skill 派生与 YAML 加载过同一套校验（harness 钉死）；
- **运行时注册就位**：新扫入的 Skill 立即可见、定时留了句柄（为 30 节注销铺路）；
- **不回退**：`mvn clean verify` 全绿。

到这里，"在底座上定义一个会自己跑的 Agent"这件事，机制上完全成立了——只是入口还停在"登录服务器往目录里放文件"。下一节把最后一块拼上：把这套能力包成 `POST /api/v1/agents`，再加上"**一句话自动生成一份 Skill**"，让业务系统 / 运营在页面上说一句话，就造出一个会自己跑的 Agent——管理平台也从"只能看"升级成"真能管"。
