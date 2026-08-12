# GitHub 贡献入门

> 第一次在 GitHub 上和别人协作？这篇给你。看完你就能提第一个 PR、看懂评审里那些"黑话"。

开源协作有一套通用流程和一套通用"暗语"。它们在 OryxOS、在 Linux 内核、在几乎所有 GitHub 项目里都一样。**学会一次，走到哪都能用。** 别怕，每个 maintainer 都是从看不懂这些开始的。

## 核心流程：Fork → Branch → PR → Review → Merge

![Fork → Branch → 开 PR → CI 跑 · 评审 → LGTM → Merge](/images/gh-flow-zh.svg)

### 1. Fork（复刻）

在项目主页点右上角 **Fork**，把仓库复制到你自己账号下。你对自己的 fork 有完全权限，改坏了也不影响原仓库。

### 2. Clone & Branch（克隆和拉分支）

```bash
git clone https://github.com/<你的用户名>/oryxos.git
cd oryxos
git checkout -b fix/typo-in-readme    # 语义化分支名：类型/做什么
```

**永远不要直接在 `main` 上改。** 每个改动开一个新分支，一个分支对应一个 PR。

### 3. 改完推上去

```bash
git add .
git commit -m "docs: 修正 README 里的错别字"
git push origin fix/typo-in-readme
```

### 4. 开 Pull Request（PR，拉取请求）

推完后，GitHub 页面会提示你 **Compare & pull request**。点它，填标题和正文：

- **标题**：一句话说清做了什么。
- **正文**：为什么改、怎么验证的；关联 issue 写 `Closes #123`（合并后自动关掉那个 issue）。

一个好 PR = **小、聚焦、有说明、带测试**。别一个 PR 塞十件事——那样没人评审得动。

### 5. 评审（Review）

maintainer 会来看你的 PR，可能：

- **Approve**——通过，可以合了。
- **Request changes**——请你改改，会附具体意见。
- **Comment**——留个想法，不卡合并。

**被要求改动很正常，不是否定你。** 这是开源里学得最快的环节。按意见改完，再 push 到同一个分支，PR 会自动更新。

### 6. 合并（Merge）

CI 全绿 + 拿到足够 Approve，maintainer 就会合并。你的名字从此进了这个项目的历史。🎉

## 评审黑话速查表

评审区里你会看到这些缩写。它们不是装酷，是社区多年攒下来的高效简写：

| 词 | 意思 |
|----|------|
| **LGTM** | *Looks Good To Me*，我看没问题——最常见的通过信号 |
| **SGTM** | *Sounds Good To Me*，方案我觉得可以 |
| **PTAL** | *Please Take A Look*，麻烦你看一下（常用来 @ 某人来 review） |
| **TAL** | *Take A Look*，看一下 |
| **nit** | *nitpick*，鸡蛋里挑骨头的小意见——不强制，但改了更好 |
| **WIP** | *Work In Progress*，还没写完，先开着看看方向，别急着 review/合 |
| **RFC** | *Request For Comments*，征求意见，通常是较大的方案讨论 |
| **+1 / 👍** | 我赞成 |
| **ship it** | 合了吧！ |
| **ping** | 催一下（"这个 PR 还有人看吗"） |
| **rebase** | 请把你的分支同步到最新的 `main`（见下） |
| **squash** | 把多个零碎 commit 压成一个再合 |
| **good first issue** | 适合新人上手的 issue——从这里开始最好 |
| **breaking change** | 破坏性变更，会让现有用法失效，要特别谨慎 |

## 常用 Git 操作

**同步最新的 main（rebase）**——PR 落后于主干、或有冲突时：

```bash
git remote add upstream https://github.com/oryx-labs/oryxos.git   # 只需加一次
git fetch upstream
git rebase upstream/main
# 有冲突就手动解决，然后：
git rebase --continue
git push --force-with-lease     # rebase 改写了历史，要强推自己的分支
```

**改上一条 commit message**：

```bash
git commit --amend
```

> `--force-with-lease` 比 `--force` 安全：只有在没人动过你的远程分支时才会推，避免误覆盖别人的提交。

## 怎么写好一个 PR

- **小而聚焦**：一次做一件事。改 300 行比改 3000 行好评审十倍。
- **说清楚**：正文写"为什么这么改""怎么验证的"，别让 reviewer 猜。
- **带测试**：新功能配测试，reviewer 和你都更放心。
- **本地先过门禁**：提交前 `mvn clean verify` 全绿，别把红灯留给 CI。
- **回应评审**：每条意见都回一下——改了就说"done"，不同意就友善地讲你的理由。

## 怎么做好一次 Review

想成为 [maintainer](./maintainer)？review 是必经之路。好的 review：

- **对事不对人**：评论代码，不评论人。
- **具体、可执行**：说"这里 null 没判，第 42 行会 NPE"，别只说"这写得不好"。
- **分清轻重**：小建议标 `nit`，真问题才 request changes。
- **多鼓励**：看到写得好的地方，说一句。新人被鼓励一次，就更可能留下来。
- **满意就明确放行**：点 Approve、回一句 LGTM，别让 PR 干等。

## 心态

- **别怕提得不好。** 提出来、被评审、改好——这就是进步本身。
- **不懂就问。** issue 和 PR 里问问题永远受欢迎，没有"蠢问题"。
- **每个人都是从这里开始的。** 你现在觉得高深的那些人，当年也在纠结 `git rebase` 怎么用。

准备好了就去[贡献指南](./contributing)提你的第一个 PR。我们在评审区等你。
