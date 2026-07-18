# Agent Skills：一份 Skill 定义一个可复用、按需加载的能力

## 开篇：为什么我们敢叫"Agent OS"

先回答一个这门课自始至终悬着的问题——**凭什么叫 OS？** 这个词不是修辞，得对得起。

看操作系统怎么成立的：一个能跑单个程序的东西不叫 OS，它只是那个程序。**OS 的定义是"一套内核之上，能装、能跑任意多个程序，彼此隔离、不改内核、免重编译"**——是 Word、Chrome、Python 一起跑在同一个 Windows 上，Windows 自己一行不改。让它成为 OS 的，从来不是内核有多强，而是那套"**定义一个程序、把它装上去、让它跑起来**"的标准机制（可执行文件格式 + 加载器 + 调度）。

套到我们身上：**Agent OS = 一套底座之上，能装、能跑任意多个 Agent。** 到 28 节为止，我们把**内核**造齐了、也跑稳了（Provider、ReAct、Tool、Memory、Sandbox、定时、Web）——但内核不等于 OS。此刻的 OryxOS 更像"一个能跑 Agent 的框架"：想跑一个 Agent，还得把指令硬写进配置、手工拼装。**缺的正是那套"定义一个 Agent、装一个能力、让它跑起来"的标准机制**——就像一个只有内核、没有可执行文件格式和加载器的系统，它能跑的只有编译进内核的东西，称不上 OS。

**这一节补的就是这块，所以它是 OryxOS 能被叫作 Agent OS 的核心一节。** 做完它，"定义一个 Agent"退化成写一份 Profile、"装一个能力"退化成丢一个 Skill 目录——任意多个、互不干扰、不动底座、免重启地跑在同一套内核上。从这一节起，OryxOS 才从"一个能跑 Agent 的框架"真正变成"一台能跑 N 个 Agent 的 OS"。

下面就从这台 OS 的三层结构讲起——**底座是底座、Agent 是 Agent、Skill 是能力**，形态对齐业界标准 Anthropic Agent Skills。

---

## 一、本节目标

### 1.1 三层分界：底座 / Agent / Skill

类比操作系统看得最清楚：

| 层 | OS 类比 | OryxOS | 是什么 |
|---|---|---|---|
| **底座** | 内核（调度、内存、系统调用） | Provider、ReAct、Tool、Memory、Sandbox、定时、Web（16~28 节） | 一切 Agent 共享的运行时能力 |
| **Agent** | 一个正在运行的进程 | 一份 **Profile** | 谁、以什么身份、何时跑（identity + provider + `schedules` + 允许的 tool） |
| **Skill** | 一个可被任意进程加载的库 | 一个 **能力目录** | 可复用的专长（指令 + 脚本 + 参考资料），被任意 Agent 按需调用 |

**终点（可验证的终态）**：往 `.oryxos/skills/` 丢一个 skill 目录 → 它出现在**每个** Agent 的能力索引里 → 某个 Agent 跑起来时，按需 `use_skill` 把它的正文加载进上下文、照正文做事（必要时再读参考、跑脚本）→ 改一下 skill 正文，下一次加载即时生效 → 全程不写一行 Java、不动一行底座。

### 1.2 为什么 Skill ≠ Agent

因为**"何时跑""以什么身份跑"是 Agent 的属性，不是能力的属性**。一个"PDF 处理"能力，本身没有"每天早上 9 点触发"这回事——是某个 Agent 用到它。把定时、provider、身份塞进能力里，这个能力就绑死在一个场景、无法复用。所以：

- **Agent = 一份 Profile**：身份（`identity`）、`provider`/`model`、触发（`schedules`）、允许的 `tools`。是"谁 / 何时 / 以什么身份"。
- **Skill = 一个纯能力目录**：只讲"会做什么、怎么做"，不含任何 Agent 身份或触发。
- **一个 Skill 被 N 个 Agent 复用**；一个 Agent 面前摆着**全部已装能力**，按需挑。

所以 **`schedules` 属于 Profile（Agent），不属于 Skill**——一个能力被复用时不该自带某个 Agent 的触发时刻。

### 1.3 我们对齐的完整形态：Anthropic Agent Skills

这套模型不是我们发明的，是 **Anthropic Agent Skills** 的忠实落地。先把它讲清楚，因为我们所有设计都照它来。

> Anthropic 官方定义：**Agent Skill 是扩展 Claude 能力的模块化单元**。每个 Skill 打包"指令、元数据、可选资源（脚本、模板）"，Claude 在相关时**自动、按需**加载。它是**基于文件系统的目录**，"像给新同事写的一份 onboarding 指南"。

它最核心的机制叫 **渐进式披露（progressive disclosure）**——**三级内容，按需分阶段进上下文**，避免一次性把所有东西塞进 context：

- **L1 · 元数据（永远加载）**：SKILL.md 的 frontmatter，只有 `name` + `description`。启动时就进 system prompt。`description` 要写清"**做什么 + 何时用**"——模型拿它匹配要不要触发这个 Skill。**装 100 个 Skill 也几乎不占上下文**，因为没触发前只有 name+description 在。
- **L2 · 指令（触发时才加载）**：SKILL.md 正文——工作流、最佳实践、步骤。模型判断相关了，才把正文读进来。
- **L3 · 资源与代码（用到才加载）**：目录里捆绑的额外文件——`REFERENCE.md`（详细参考）、`scripts/*.py`（可执行脚本）、模板等。**脚本用命令行跑，产出结果进上下文、代码本身不进**，于是能做确定性操作而不烧 token。

```
pdf-processing/                 # 一个 Skill = 一个目录
├── SKILL.md                    # L1 frontmatter + L2 正文
├── REFERENCE.md                # L3 详细参考，用到才读
└── scripts/
    └── fill_form.py            # L3 脚本，用命令行跑、代码不进上下文
```

**两个由此而来的原则，直接决定我们怎么做**：

1. **确定性交给代码，判断留给正文**。该确定的步骤（"抽表格、填表单、合并 PDF"）沉进 `scripts/*.py`，一次执行拿结果；正文只写"什么时候该用哪个脚本"这种带判断的部分。**不要**让模型用自然语言去"串"一串确定性步骤——那又慢又不稳，也不是 Skill 的价值所在。
2. **能力靠 `description` 被发现，不靠塞满上下文**。这就是为什么我们注入**全部**已装 Skill 的 L1、却只在触发时才加载 L2/L3。

---

## 二、围绕目标要做哪些

贯穿始终一条原则：**不重写底座，只加"一个能力库 + 一套按需加载"。** 底座（16~28 节）照旧吃 `Profile`、跑 ReAct，一行不改；我们新增的是"能力"这一层，以及让 Agent 在 ReAct 循环里按需把能力加载进来的机制。

### 2.1 Skill 长什么样：目录 + 三级内容

一个 Skill 就是 `.oryxos/skills/<name>/` 一个目录，`SKILL.md` 两段结构、外加可选的 L3 资源：

~~~markdown
---
name: pdf-processing
description: 抽取 PDF 文本与表格、填表单、合并文档。当用户提到 PDF / 表单 / 文档抽取时使用。
---

# PDF 处理

## 快速开始
用 pdfplumber 抽文本：
```python
import pdfplumber
with pdfplumber.open("document.pdf") as pdf:
    text = pdf.pages[0].extract_text()
```
复杂表单填写见 [REFERENCE.md](REFERENCE.md)；批量填充直接跑 `scripts/fill_form.py`。
~~~

- **frontmatter 只有 `name` + `description`（L1）**——能力目录里**不含** `provider`/`model`/`tools`/`notify_channels`/`schedules`，那些是 Agent（Profile）的事；这里只留"这个能力叫什么、什么时候用"。
- **正文（L2）** 是给模型的能力指令；**`scripts/`、`REFERENCE.md`（L3）** 是它按需读/跑的资源。
- 存放位置固定 `.oryxos/skills/<name>/`，**一个目录一个能力**。

### 2.2 能力库：扫 `.oryxos/skills/` → `SkillRegistry`

系统启动时（以及 30 节通过 API 新增时）扫描 `.oryxos/skills/`，对每个目录做两步：

1. **解析**：`SkillLoader` 读 `SKILL.md`，拆出 frontmatter（L1：name/description）与正文（L2）；记住 L3 资源所在目录。
2. **登记**：把这个能力放进 **`SkillRegistry`**（能力库，仅存 name/description/正文路径/目录）。

**注意：登记进的是 `SkillRegistry`，不是 `ProfileRegistry`。** Skill 不是 Agent，扫 skills 目录只是在装能力，不产生任何 Agent。Agent 依旧来自 `.oryxos/profiles/` 的 Profile。两个来源、两件事，别混。

### 2.3 渐进式披露：三级怎么在 ReAct 循环里跑

这是"完整形态"最硬、也最有价值的一块——把 Anthropic 的三级加载映射到我们自实现的 ReAct 循环上：

- **L1 全量注入（`PromptBuilder`）**：组装 prompt 时，往 system prompt 注入一段 `<available_skills>` 索引 = **`SkillRegistry` 里全部**已装 Skill 的 `name: description`。所有 Agent 都看得到全部能力（对齐 Anthropic：不做 per-agent 能力白名单，收紧留扩展阶段）。装再多能力，平时也只多这一小段文本。
- **L2 按需加载（新工具 `use_skill`）**：模型看索引、按 `description` 判断某能力相关 → 调底座新工具 **`use_skill(name)`**，它从 `SkillRegistry` 取出该 Skill 的**正文**，作为工具结果进上下文，ReAct 继续。这样正文只在真用到时才占上下文。**`use_skill` 是每个 Agent 默认自带的通用工具**（像 `read_file` 一样）——既然 system prompt 里给每个 Agent 都摆了全量能力索引，就必须人人都够得着，否则索引成了看得见摸不着的摆设。
- **L3 按需读/跑（复用已有原语）**：正文里指向 `scripts/render.py`、`REFERENCE.md` → 模型用已有的 `read_file` 读参考、用 `shell`/`python` 原语跑脚本。**脚本产出结果进上下文、代码不进**。

**这处改动触及 `ContextLoader`/`PromptBuilder`**：以前是"把 Skill 正文永远塞进 system prompt"，现在改成"**system prompt 只放 L1 索引，正文靠 `use_skill` 按需进**"。这是本节代码量最大的一处，也是"完整形态"与"把 md 全量拼进 prompt"的分水岭。

> **与宪法原则四的关系**：**L1 元数据由 `ContextLoader`/`PromptBuilder` 注入 system prompt**；**L2 正文经 `use_skill` 按需加载**。关键底线没破：`use_skill` 返回的是**指令内容**、不是"执行一个 Skill"，Skill 仍然不是一个可执行 Tool。

### 2.4 Agent 与 Skill 分离：`schedules` 在 Profile + 运行时注册

- **`schedules` 在 Profile**：Agent 什么时候自己跑，写在它自己的 Profile 里（25/28 节的 `AgentScheduler` 照旧遍历 `Profile.schedules` 注册，**一行不改**）——因为定时是 Agent 的属性，不是能力的。
- **运行时注册（为 30 节铺路）**：`ProfileRegistry`（16 节）现在是不可变的；本节把它改成持有可变并发 Map，新增 `register(Profile)` / `remove(String)` / `exists(String)`——这是 16 节 javadoc 就预告的"29 节补运行时 register"。校验复用 16 节 `ProfileLoader` 那套（provider 存在、tool 已注册），不新写。`AgentScheduler` 把 `registerAll` 的循环体抽成 `registerProfile(Profile)`，并新增一张 `Map<String, ScheduledFuture<?>>` 句柄表（30 节注销/更新 Agent 要用），与 25 节的 `taskLocks` 并存、各管一事。**启动路径与运行时路径必须是同一段代码**，否则"API 建的 Agent 和文件建的 Agent 行为不一样"这种 bug 最难查。

### 2.5 新底座工具 `use_skill` + L3 脚本的沙箱与信任边界

- **`use_skill(name)`**：本节新增的底座工具，通用、每个 Agent 默认自带——按名字从 `SkillRegistry` 取正文返回。不是每个 Agent 加 Java，是全局一个原语。未知 name 明确报错。走 `ToolExecutor` 既有审计路径（宪法五）。
- **L3 脚本的沙箱**：Skill 脚本经 `shell`/`python` 原语跑，安全边界落在这里——白名单放行"解释器（`python`/`bash`）+ 限定只能跑 `.oryxos/skills/<name>/scripts/` 下的脚本"。
- **一个必须说清的信任边界**：L3 脚本是**任意代码**——`python scripts/foo.py` 一旦放行，脚本能读写文件、**能自己发网络请求**。这意味着**它绕过了 `http_get` 那道域名白名单**（域名白名单只管内置 `http_get` 工具，管不到子进程的网络）。所以结论要摆明：**装一个带脚本的能力 = 信任这个能力的作者**（与 Anthropic 一致：装一个 skill 就是信任这个 skill）。核心阶段的沙箱对 L3 只做到"解释器 + 脚本目录"两道白名单；把第三方能力关进**受限容器 / 网络隔离**是扩展阶段的事。做 Agent OS 要对这条诚实：**能跑第三方能力很强，但它把信任从"底座"挪到了"能力作者"。**

**有几样先别做**：Skill 版本管理、Skill 市场 / 共享、per-agent 能力白名单（本版全体共享）、L3 脚本的容器 / 网络隔离（核心阶段只到解释器 + 目录白名单）、Profile 与 Skill 同名冲突策略——都放扩展阶段（**目录实时监听 / 热加载在 30 节做**，本节先把"能力库 + 三级按需加载 + 运行时注册"立好）。这一节做到运行时注册就位就够。

**本节交付物**（Spec-Kit 拆解锚点）：

- **代码**：`SkillLoader`（解析目录 → frontmatter/正文/资源路径）；`SkillRegistry`（能力库 + `register`/`get`/`all`）；`use_skill` 工具；`PromptBuilder` 注入 `<available_skills>` L1 索引；`ContextLoader`/`PromptBuilder` 改造（system prompt 不再全量注正文）；`ProfileRegistry.register/remove/exists`（16 节改造点，改可变 Map）；`AgentScheduler.registerProfile` + `scheduledTasks` 句柄表；`schedules` 回归 `Profile`。
- **测试（harness）**：`SkillLoaderTest`、`SkillRegistryScanTest`、`SkillIndexInjectionTest`、`UseSkillToolTest`、`ProgressiveDisclosureTest`、`ProfileRegistryRuntimeTest`、`AgentSchedulerRegisterTest`（见下）。
- **文件**：一个示例 Skill 目录（含 `SKILL.md` + 一个 `scripts/*.py` + `REFERENCE.md`），作为 L3 的参照物；一份引用它的示例 Profile（含 `schedules`）。
- **校验**：Skill 缺 `name`/`description` → 加载报错清晰；`use_skill` 未知 name → 报错。

---

## 三、怎么验收：把机制固化成 harness

这一节的机制是"扫描 → 登记能力库 → L1 全量注入 → `use_skill` 按需加载 L2 → L3 读/跑"，harness 把这条链每一环钉死，尤其钉住**渐进式披露**（触发前正文不在上下文、触发后才在）：

| 测试类 | 覆盖的验收点 |
|---|---|
| `SkillLoaderTest` | 正确从目录拆出 frontmatter（L1）与正文（L2），认出 L3 资源；缺 `name`/`description` 报错清晰 |
| `SkillRegistryScanTest` | 扫一个放了 N 个 Skill 目录的目录 → `SkillRegistry` 里出现 N 个能力（**不产生任何 Agent**——skill≠agent 的直接证据） |
| `SkillIndexInjectionTest` | 组装 prompt → system prompt 含**全部**已装 Skill 的 `name: description`，**且不含任何 Skill 正文**（L1 全量、L2 不预载的直接证据） |
| `UseSkillToolTest` | `use_skill("pdf-processing")` 返回该 Skill 正文；未知 name → 明确报错；调用落审计 |
| `ProgressiveDisclosureTest` | 触发前上下文无正文；模型调 `use_skill` 后正文才进上下文（**渐进式披露的守点**） |
| `ProfileRegistryRuntimeTest` | `register()` 后立即 `get()` 可见；非法配置报错与启动路径**完全一致**（同一异常 + 同一消息——复用同一套校验的直接证据） |
| `AgentSchedulerRegisterTest` | `registerProfile` 后 `scheduledTasks` 有句柄（30 节注销的前提）；cron/时区来自 `Profile.schedules`（**定时来自 Agent、不来自 Skill** 的直接证据） |

最值钱的两个守点，一个钉"L1 全量、L2 按需"，一个钉"定时来自 Agent 不来自 Skill"：

```java
@Test
void 系统提示只放能力索引_不预载正文() {
    var prompt = promptBuilder.build(agent, session, /* ... */);
    // 全部已装能力的 L1 都在
    assertTrue(prompt.system().contains("pdf-processing"));
    assertTrue(prompt.system().contains("抽取 PDF 文本与表格"));   // description
    // 但正文一个字都不预载——必须靠 use_skill 才进来
    assertFalse(prompt.system().contains("用 pdfplumber 抽文本"));  // L2 正文
}

@Test
void use_skill_才把正文加载进上下文() {
    var before = context.messages();
    assertTrue(before.stream().noneMatch(m -> m.content().contains("用 pdfplumber")));
    useSkill.execute(json("{\"name\":\"pdf-processing\"}"));       // 模型主动加载
    var after = context.messages();
    assertTrue(after.stream().anyMatch(m -> m.content().contains("用 pdfplumber")));
}
```

"Skill 正文改了即时生效"不用在这节重测——17 节 `ContextLoaderTest` 的无缓存回归已经钉死，`use_skill` 每次现取即可，harness 不重复钉同一颗钉子。

### 验收清单（每条都成立才算达标）

- **能力库成立**：往 `.oryxos/skills/` 放一个 Skill 目录，它出现在每个 Agent 的 `<available_skills>` 索引里，全程没写一行 Java；
- **渐进式披露真发生**：平时 system prompt 里只有能力的 name+description，某 Agent 跑到需要时才 `use_skill` 把正文加载进来（harness 钉死）；
- **L3 能跑**：Skill 里的脚本经 `shell`/`python` 白名单真的能被执行、产出结果，代码不进上下文；
- **定时来自 Agent**：`schedules` 写在 Profile、到点真触发（真模型链路）——Skill 里没有定时；
- **运行时注册就位**：新建的 Profile 立即可见、定时留了句柄（为 30 节注销铺路）；
- **两条来源同规矩**：Profile 运行时 `register` 与启动 `load` 过同一套校验（harness 钉死）；
- **不回退**：`mvn clean verify` 全绿。

到这里，"给底座装可复用能力、让任意 Agent 按需调用"这套标准形态就完全成立了——只是入口还停在"登录服务器往目录里放文件"。下一节把最后一块拼上：把"装一个能力（传 Skill 目录）"和"定义一个 Agent（建 Profile、挂能力）"都包成 `POST /api/v1/agents` / skills 端点，再加上"**一句话生成一份 Skill / 一个 Agent**"，让业务系统 / 运营在页面上说一句话就上线——管理平台也从"只能看"升级成"真能管"。
