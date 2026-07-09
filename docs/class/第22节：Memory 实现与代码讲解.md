# Memory：实现与代码讲解

上一节评审定了方向，这节把它变成能跑的代码。照旧四件事：这节要实现什么、动手前该想清楚什么、代码怎么写、做完怎么验。

技术栈还是 JDK 21 + Spring Boot 3.x + Spring AI 的 `@Tool` 注解。下面的代码是示意，具体 API 以你用的 Spring AI 版本为准。

---

## 一、这节要实现什么

一句话：**一个 `MemoryService` 门面，背后两条腿——会话记忆走 SQLite，长期记忆走一个分区的 `MEMORY.md`，外加两个内置 Tool 让 Agent 自己读写长期记忆。**

对照上一节的结论：接口先行、核心阶段文件式、三层记忆（核心/会话/归档）、写入靠 Agent 主动调 `save_memory`（不做自动提炼）。这节要交付的东西，就是把这几句话变成真实存在的类。

![Memory 架构：MemoryService 门面统一收口 SessionManager 和 LongTermMemory](../../website/public/images/class-22-1.svg)

---

## 二、动手前先想清楚几件事

**第一，接口先行，跟上一节讲的"墙"一致。** `MemoryService` 的方法签名现在就定死：给 `PromptBuilder` 用的"要拼进 Prompt 的记忆内容"、给 `MemoryTools` 用的"记一条"和"查一下"。`ReActLoop`、`PromptBuilder` 全程只认这一个接口，不直接碰 `SessionManager` 或 `MEMORY.md` 文件。

**第二，核心记忆和归档记忆是同一个文件里的两个区块，不是两个子系统。** 用 Markdown 的二级标题分区就够——`## 核心记忆` 和 `## 归档记忆` 两个 header，读写的时候按区块处理。不需要为"核心记忆"单独开一张表或一个新类，那是过度设计。

`MEMORY.md` 内部长这样，一个文件、两个区块，读写规则完全不同：

![MEMORY.md 内部结构：核心记忆区与归档记忆区](../../website/public/images/class-22-2.svg)

**第三，几个坑，提前想到。**

- **坑一：不能缓存。** 上一节说过，`MEMORY.md` 每次重新读、不做缓存，这样 Agent 调完 `save_memory` 下一轮立刻能看到。这条写代码时最容易手滑加个缓存"优化性能"，一旦加了，"记完立刻生效"这个体验就没了，还得处理缓存失效，得不偿失。
- **坑二：截断只能裁归档区，不能连累核心区。** 长期记忆超过阈值（比如 4000 字）要截断，但**核心记忆是"永远完整、不换出"的**——如果截断逻辑简单粗暴地对整个文件掐头去尾，可能把核心记忆区截没了，这就违背了它"始终在场"的定位。截断必须只作用在归档区。
- **坑三：`save_memory` 写核心还是写归档，谁来决定。** 不能让系统自己猜，得让 Agent 显式说清楚——给这个 Tool 加一个可选参数（比如 `scope`，取值 `core` 或 `archival`，缺省 `archival`），Agent 调用时自己指定。
- **坑四：`recall_memory` 的检索不用做得多复杂。** 核心阶段就是简单的关键词包含匹配，按行或按块扫描归档区，命中就返回。别一上来就想上正则引擎或者分词，用不上。

想清楚这几点，代码该长什么样就清楚了：一个接口、一份分区的文件、不缓存、截断只裁一半、写入靠参数不靠猜。

---

## 三、代码怎么写

核心两个类：`MemoryService`（门面接口 + 实现）、`LongTermMemory`（长期记忆的文件读写），外加 `MemoryTools` 把它们暴露给 Agent。

**第一步：`MemoryService` 接口。** 只对上层暴露两件事——要拼进 Prompt 的内容、记一条新的：

```java
public interface MemoryService {
    String buildContext(Session session);           // 给 PromptBuilder：核心记忆 + 会话历史
    void remember(String content, MemoryScope scope); // 给 MemoryTools：save_memory 调这个
    List<String> recall(String keyword);              // 给 MemoryTools：recall_memory 调这个
}

public enum MemoryScope { CORE, ARCHIVAL }
```

**第二步：`LongTermMemory`，长期记忆的核心实现。** 对外四个方法，跟上一节技术方案里定的一致，只是内部要按区块处理：

```java
public class LongTermMemory {

    private static final String CORE_HEADER = "## 核心记忆";
    private static final String ARCHIVE_HEADER = "## 归档记忆";
    private static final int MAX_ARCHIVE_CHARS = 4000;   // 阈值只管归档区

    public void append(String content, MemoryScope scope) {
        String header = scope == MemoryScope.CORE ? CORE_HEADER : ARCHIVE_HEADER;
        String entry = "\n- [" + LocalDate.now() + "] " + content;
        writeIntoSection(header, entry);   // 找到对应区块，追加进去
    }

    public String load() {
        String raw = Files.readString(memoryFilePath());   // 每次都重新读，坑一的解法
        String core = extractSection(raw, CORE_HEADER);      // 核心区：完整返回
        String archive = truncateIfNeeded(extractSection(raw, ARCHIVE_HEADER)); // 归档区：可能截断
        return core + "\n" + archive;
    }

    public List<String> recallByKeyword(String keyword) {
        String archive = extractSection(Files.readString(memoryFilePath()), ARCHIVE_HEADER);
        return archive.lines()
                .filter(line -> line.contains(keyword))   // 简单包含匹配，坑四的解法
                .toList();
    }

    private String truncateIfNeeded(String archiveSection) {
        if (archiveSection.length() <= MAX_ARCHIVE_CHARS) {
            return archiveSection;
        }
        return archiveSection.substring(archiveSection.length() - MAX_ARCHIVE_CHARS); // 只裁这一段，坑二的解法
    }
}
```

一行行看关键的几处：

- `append(content, scope)`——按 `scope` 决定写进哪个区块，核心记忆和归档记忆走同一个方法、同一份文件，只是目标区块不同。
- `load()`——**每次都 `Files.readString`，不做任何缓存**，对应坑一。核心区 `extractSection` 完整拿出来，归档区才过 `truncateIfNeeded`，对应坑二——截断函数只接收归档区这一段文本，物理上不可能动到核心区。
- `recallByKeyword(...)`——只在归档区里搜，核心记忆本来就永远在场，不需要"检索"这个动作。用 `String.lines().filter(...)` 这种最朴素的包含匹配，对应坑四。

**第三步：`MemoryTools`，把长期记忆暴露给 Agent。**

```java
@Component
public class MemoryTools {

    private final MemoryService memoryService;

    @Tool(name = "save_memory", description = "记住一件值得长期记住的事")
    public String saveMemory(
            @ToolParam("要记住的内容") String content,
            @ToolParam("core 或 archival，不确定就填 archival") String scope) {
        memoryService.remember(content, MemoryScope.valueOf(scope.toUpperCase()));  // 坑三的解法
        return "已记住";
    }

    @Tool(name = "recall_memory", description = "按关键词检索长期记忆")
    public String recallMemory(@ToolParam("检索关键词") String keyword) {
        List<String> hits = memoryService.recall(keyword);
        return hits.isEmpty() ? "没有找到相关记忆" : String.join("\n", hits);
    }
}
```

`scope` 这个参数就是坑三的解法——**是不是核心记忆，由 Agent 自己判断，工具层不猜**。`@Tool` 注解让 Spring AI 把这两个方法自动包装成 schema，走的是 Provider 那节讲过的"只翻译不执行"路径的另一头——这两个是真正的执行体，模型请求调用后，`ToolExecutor` 找到它们、真正跑起来。

**集成点：`PromptBuilder` 怎么用它。** 组装 Prompt 时，多一步 `memoryService.buildContext(session)`，把返回的内容拼进 system prompt 里，跟会话历史、工具列表放在一起——这跟 17 节讲的"四部分"对上了：这里就是那个"长期记忆"部分的真身。

**有几样先别做。** 语义检索、情景记忆、`save_memory` 的自动触发（上一节已经定了：核心阶段不做，等信号出现再说）、Memory Wiki 式的结构化矛盾检测、记忆压缩，这些都放扩展阶段。核心阶段做到"记得住、读得出、不会把核心记忆截没"就够。

坑一（不缓存）最值得单独验一遍，因为它是最容易被后来者"优化"掉的一条：

![无缓存读写验证流程：save_memory 写入后 recall_memory 立刻可见](../../website/public/images/class-22-3.svg)

**本节交付物**（Spec-Kit 拆解锚点）：

- 代码：`MemoryService` 接口 + 实现、`MemoryScope` 枚举、`LongTermMemory`（append/load/recallByKeyword/truncateIfNeeded）、`MemoryTools`（save_memory/recall_memory）
- 测试：`LongTermMemoryTest`、`MemoryToolsTest`、`MemoryServiceTest`（见验收 harness）
- 文件：`.oryxos/memory/MEMORY.md`（`## 核心记忆` / `## 归档记忆` 两区块约定）
- 集成点：`PromptBuilder` 组装时调 `memoryService.buildContext(session)`

---

## 四、验收 harness：把验收标准变成可执行的测试

Memory 全是文件和内存操作，用 `@TempDir` 临时目录就能测干净，harness 全单测。四个坑各有一个回归测试钉死：

| 测试类 | 覆盖的验收点 |
|---|---|
| `LongTermMemoryTest` | **写后立读**（坑一：无缓存）；**截断只裁归档、核心区一字不动**（坑二）；`scope` 路由到正确区块（坑三）；`recallByKeyword` 只搜归档区（坑四附带） |
| `MemoryToolsTest` | `scope` 缺省写归档；关键词未命中返回"没有找到相关记忆"而不是抛异常 |
| `MemoryServiceTest` | `buildContext` 返回核心记忆 + 会话历史的组合，归档区不整体注入 |

两个最值钱的：

```java
@Test
void 截断只裁归档区_核心记忆一字不能少() {
    memory.append("用户叫小王，偏好用 Java", MemoryScope.CORE);
    for (int i = 0; i < 500; i++) {
        memory.append("归档流水 " + i, MemoryScope.ARCHIVAL);   // 把归档区灌到远超 4000 字
    }

    String loaded = memory.load();

    assertTrue(loaded.contains("用户叫小王，偏好用 Java"));   // 核心区完整——"始终在场"的底线
    assertFalse(loaded.contains("归档流水 0"));               // 归档区最早的内容被裁掉了
    assertTrue(loaded.contains("归档流水 499"));              // 保留的是最近的
}

@Test
void 写入后立刻可读_不允许有缓存() {
    memory.append("刚记的事", MemoryScope.ARCHIVAL);
    assertTrue(memory.load().contains("刚记的事"));           // 同一进程内下一次 load 立即可见
    assertFalse(memory.recallByKeyword("刚记的事").isEmpty()); // 检索同样立即命中
}
```

第一个测试是这节最重要的一道保险：截断逻辑将来任何"优化"（比如有人改成对全文件掐头去尾），这里立刻红。

---

## 五、做完怎么验

harness 全绿后，剩下的人工确认：

- 用真模型完整走一遍：对话里说一句值得记的话，Agent 主动调 `save_memory`；开新会话，系统提示里带着核心记忆——"始终在场"在真实链路里的体感。
- 跨进程验证：重启后 `MEMORY.md` 里的记忆还在（文件天然跨重启，目检一眼）。
- `MEMORY.md` 和 `USER.md` 的角色分清：`USER.md` 全程只读、`MEMORY.md` 能被 Agent 写入（code review 确认没有写 USER.md 的代码路径）。
- 无缓存、截断保核心、scope 路由、未命中不报错——已由 harness 覆盖，`mvn test` 绿即打勾。

到这一步，Agent 不但会想（ReAct）、会动手（Tool），还记得住事（Memory）——三大能力凑齐了。Demo 二（每日科技日报）里"日报要体现用户之前说过的偏好"这一环，靠的就是这节的 `save_memory` 写入、下次组装 Prompt 时自动带上，具备了跑通的条件。
