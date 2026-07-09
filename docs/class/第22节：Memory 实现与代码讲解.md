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
- 文件：`.oryxos/memory/MEMORY.md`（`## 核心记忆` / `## 归档记忆` 两区块约定）
- 集成点：`PromptBuilder` 组装时调 `memoryService.buildContext(session)`

---

## 四、做完怎么验

对着下面几条打勾：

- 在核心记忆区写一条（比如"用户叫小王，偏好用 Java"），开一个新会话，系统提示里还带着这条——验证核心记忆"始终在场"。
- 调一次 `save_memory` 写归档记忆，**不重启进程**，紧接着下一轮对话就能通过 `recall_memory` 或直接在系统提示里查到——验证坑一（不缓存）。
- 往归档区堆很多条记录直到超过阈值，确认触发截断后**核心记忆区完整无损**，只有归档区被裁短——验证坑二。
- `save_memory` 不传 `scope` 参数时默认写进归档区，传 `core` 时能正确写进核心记忆区。
- `recall_memory` 传一个归档区里存在的关键词，能查到；传一个不存在的，返回"没有找到相关记忆"而不是报错。
- `MEMORY.md` 和 `USER.md` 的角色分清：`USER.md` 全程只读、`MEMORY.md` 能被 Agent 通过 `save_memory` 写入。
- 写几个单元测试：`LongTermMemoryTest`（append/load/截断/关键词检索）、`MemoryToolsTest`（两个 Tool 的输入输出），覆盖上面这几条。

到这一步，Agent 不但会想（ReAct）、会动手（Tool），还记得住事（Memory）——三大能力凑齐了。Demo 二（每日科技日报）里"日报要体现用户之前说过的偏好"这一环，靠的就是这节的 `save_memory` 写入、下次组装 Prompt 时自动带上，具备了跑通的条件。
