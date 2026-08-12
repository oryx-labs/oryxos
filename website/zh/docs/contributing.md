# 贡献指南

> 欢迎你。是的，说的就是你。

OryxOS 是**我们的**项目，刚刚起步。没有那么多规矩，也不需要你先成为专家。这里最看重的一件事只有一个——**你愿意动起来**。只要你愿意提一个 PR、评审一段代码、修一个错别字，你就已经是这个项目的贡献者了。

我们希望通过这个项目一起做到三件事：

1. **动起来，学会 AI 编程，走进开源。** 很多人想进开源但迟迟没迈第一步。这里就是你迈第一步的地方。
2. **学会成为 maintainer。** 不只是提交代码，还包括评审别人的代码、帮别人 review、把关质量、带新人——这些是比写代码更值钱的能力。
3. **把它写进简历。** 我们真心希望这个项目发展好。等它长大了，你参与过、维护过的经历，就是你简历上实打实的一笔。

所以别犹豫，别怕提得不够好。**提出来，我们一起改。**

## 你可以贡献什么

不是只有写核心代码才叫贡献。下面每一样我们都同样欢迎：

- **代码**——修 bug、加功能、写内置 Tool、接一个新的 MCP server、优化性能。
- **文档**——补充文档、修错别字、把一段讲不清楚的地方讲清楚、翻译中英文档。
- **测试**——补测试用例、复现并报告 bug、提高覆盖率。
- **Agent / Skill 示例**——写一个有意思的 Agent 目录或 Skill，放进示例，让别人照着学。
- **Issue**——报一个 bug、提一个想法、参与讨论。哪怕只是"这里我没看懂"，也是有价值的反馈。
- **评审（Review）**——去别人的 PR 下面看一看、提提意见、点个 LGTM。这是成为 maintainer 的第一步。

> 第一次不知道从哪下手？去 [Issues](https://github.com/oryx-labs/oryxos/issues) 找带 `good first issue` 标签的，或者直接开一个 issue 说"我想帮忙，从哪开始"。

## 用 AI 来做——但为结果负责

**欢迎、甚至非常建议你全程用 AI 完成贡献。** 写代码、写文档、写测试、debug、理解一段陌生的模块——尽管用 Claude Code、Copilot、Cursor 或任何你顺手的工具。OryxOS 本身就是一个"和 AI 一起把事做成"的项目，帮你走进 AI 编程正是我们的目的之一。

但有一条底线，和 Linus 对 AI 的看法一样：

> **AI 只是工具。真正重要的是——你能为结果负责。**

这意味着，你交出去的每一个 PR，无论是不是 AI 写的，都是**你**的 PR：

- **你读懂了它。** 别提交你自己都看不明白的代码。AI 生成完，你要能讲清楚它为什么这么写。
- **你验证过它。** 本地 `mvn clean verify` 全绿，功能真的跑通了——而不是"AI 说它能跑"。
- **你对它负责。** 评审意见冲你来，回归 bug 算你的。"这是 AI 写的"不是借口。

一句话：**AI 帮你更快、更强，但署名和责任是你的。** 用好它，然后像对自己亲手写的每一行一样，为它把关。

## 开发环境准备

OryxOS 是 Java 21 + Spring Boot 的 Maven 多模块项目，前端管理台是 Vue。你需要：

- **JDK 21+**（必须——虚拟线程是运行时核心）
- **Maven 3.9+**
- **Node.js 18+**（仅当你要改 `oryxos-web` 的前端管理台时）

```bash
# 克隆你 fork 后的仓库
git clone https://github.com/<你的用户名>/oryxos.git
cd oryxos

# 构建 + 跑全部质量门禁和测试
mvn clean verify

# 本地起服务（先按快速开始配好 Provider）
java -jar oryxos-boot/target/oryxos-boot-*.jar serve
```

更详细的构建与配置见[快速开始](./quick-start)。

## 贡献流程（标准 GitHub 流程）

如果你是开源新手，这套流程和 GitHub 上绝大多数项目一样，学会一次终身受用。不熟的话先看[GitHub 贡献入门](./github-workflow)。

1. **Fork** 本仓库到你自己的账号下。
2. **拉分支**：从 `main` 切出一个语义化的分支名。

   ```bash
   git checkout -b feat/add-slack-channel
   ```

3. **写代码**：小步、聚焦。一个 PR 只做一件事——好评审、好合并。
4. **本地自测**：提交前务必让 `mvn clean verify` **全绿**（下面「质量门禁」有说明）。
5. **提交**：写清楚的 commit message（见下）。
6. **推分支 + 开 PR**：PR 标题说清楚做了什么，正文说清楚为什么、怎么验证。关联相关 issue（`Closes #123`）。
7. **参与评审**：maintainer 会来看你的 PR。有意见就一起改——这是学习最快的环节，别有压力。
8. **合并**：拿到 LGTM / Approve、CI 全绿，maintainer 就会帮你合。🎉

## 提交规范

**Commit message** 建议用约定式提交（Conventional Commits）风格，让历史一眼能读懂：

```
<类型>(<可选范围>): <简短描述>

feat(tool): 新增 http_download 内置工具
fix(web): HTTPS 请求下 auth cookie 未加 Secure 标志
docs: 补充贡献指南
chore: 升级版本到 0.1.1
```

常用类型：`feat`（新功能）、`fix`（修 bug）、`docs`（文档）、`test`（测试）、`refactor`（重构）、`chore`（杂项/构建）。

**PR 标题**同样清晰即可。一个约定：**PR 标题以 `release:` 开头并合入 `main`，会触发自动发版**（打 Release + 上传 tarball），所以普通 PR 别用这个前缀。

## 质量门禁（务必看）

OryxOS 用 `mvn verify` 把关，本地过了才提，不要把红灯留给 CI 和 reviewer：

- **Spotless**（google-java-format）——格式。没过就跑 `mvn spotless:apply` 自动修。
- **P3C / PMD**——阿里巴巴 Java 规约，比如字符串字面量要抽成常量。
- **Checkstyle**——比如未使用的 import 会直接报错。
- **SpotBugs + FindSecBugs**——潜在 bug 和安全问题。
- **单元测试**——JUnit，新功能请带上测试。

另外，OryxOS 有几条**架构宪法**（自实现 ReAct Loop、Spring AI 只做协议转换 + `@Tool` schema、Provider 显式映射、审计表第一天就写入、沙箱白名单、同步 + 虚拟线程……）。改到核心时请对照 `CLAUDE.md` 里的宪法条款；拿不准就在 issue / PR 里问，别一个人闷头猜。

## 行为准则

一句话：**对人友善，对事认真。**

我们刚起步，最怕的不是代码写得不够好，而是有人因为"怕丢脸"不敢参与。所以——

- 评审对事不对人，具体、可执行，别只说"这不行"。
- 提问不丢人，每个 maintainer 都是从"这是啥"开始的。
- 欢迎新人，多一句鼓励，社区就多一个人留下来。

## 找我们

- **Issues**：报 bug、提想法 → [github.com/oryx-labs/oryxos/issues](https://github.com/oryx-labs/oryxos/issues)
- **Pull Requests**：开始贡献 → [github.com/oryx-labs/oryxos/pulls](https://github.com/oryx-labs/oryxos/pulls)

想更进一步、成为 maintainer？看[Maintainer 指南](./maintainer)。第一次用 GitHub 协作？看[GitHub 贡献入门](./github-workflow)。
