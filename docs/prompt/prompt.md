# Prompt 记录 — 第一轮

本文件记录本项目第一轮 AI 辅助开发过程中，用户输入的所有提示词（按时间顺序）。

---

1. docs 理解一下这四个文档，这四个文档就是这个项目要做的事情
2. 整理一下这四个文档的格式，整理成md好看的格式
3. 四个文档都要整理
4. 多agent 并行处理
5. 根据这些信息，生成claude.md
6. 基于这些信息，生成readme。 readme文件参考标准的开源项目来写。
7. Architecture 架构图用svg或png画，放到docs/images
8. 注意不要重叠和太拥挤
9. 用maven 初始化这个项目，按照文档中的9个模块，并能通过编译打包
10. readme文件的架构图要用画的这张图
11. boot 能java -jar 运行了吗？ 是一个main函数
12. oryxos 的cli 需要有一个main 函数，它要能运行，打印oryxos 的版本信息
13. 画一个oryxos的logo，放在readme，用svg 格式，你自由发挥，我需要一个logo
14. logo 放在images 目录下
15. logo 放中间
16. 给我打印出我前面给你的所有提示词。
17. 这些内容，写入docs/prompt/01.md ，带上序号
18. robustmq-mq9/website 理解一下这个项目代码。 oryxos 要做一个一模一样的主页，你先理解下。看是否需要我做什么
19. 1. <https://github.com/oryx-labs/oryxos>  2. 要中一个你问  3. 域名是oryxos.robustmq.com
20. 网页要中英文
21. 新的提示词也放到docs/prompt/01.md 中
22. website/.vitepress 和target 驾到gitignore里面
23. 不用提交
24. 调整一下logo，只留下字就可以了
25. 下面那行字也去掉
26. 首页的左上角的logo 要小一点，现在太大了
27. 再一点点 现在有点小
28. 新的提示词也放到docs/prompt/01.md 中
29. 理解一下这个文件：/Users/oker/robustmq/scripts/package.sh的功能
30. 这个项目也需要这个文件，把文件传到远程。但是需要注意target 下的文件不需要传到远程
31. 是的，你执行一下这个脚本
32. 理解一下website的布局，你全部重新设计一下首页风格，要有特点的。可以全部重新设计。你给我设计一个有特点的的，你自由发挥。你主要理解一下文档：docs/what.md。重写readme.md。重新画架构图。重新设计website主页。其他你自己来，不用问我。
33. 重新设计一个logo docs/images/logo.svg
34. Logo的颜色以橙色为主色调。website的页面也以website的主色调
35. website的页面怎么运行
36. 整个网站改为纯黑色的底。现在顶部和底都是白色的
37. docs/oryxos.md 理解一下文档。修改readme.md和website首页和doc文档
38. 提示词加到：docs/prompt/01.md
39. 更新提示题到01.md
40. git push 提示 Permission denied（403），github 没权限，怎么给项目配置 token 权限
41. github 怎么设置这个 token
42. github 上项目的 page 怎么配置
43. <https://oryx-labs.github.io/oryxos/> 的 css 请求 404，是不是哪里配置不对
44. 格式化这个文档：docs/oryxos.md
45. 更新提示词到 01.md

---

# Prompt 记录 — 第二轮

本轮覆盖：课程文档 16~32 节的撰写与对齐、技术方案重构（底座/Agent 两部分）、Notify 模块、两个 Demo、Spec-Kit 执行指导与 Harness 门禁设计、oryxos-lesson-dev skill。早于本轮记录起点的提示词已随上下文压缩丢失原文，未收录。

46. 你错了，我不是让你干活，知识让你理解文档，先回滚改动
47. provider和react的代码被回滚了。我们会按照16、17、18，这种每部分用spec kit来实现。你能理解吗？继续处理
48. 16 有两个作用 1. 我会拿着16的文档，跟用户讲16节的内容，2. 16 会作为spec kit 的输入
49. 你还记得我们的目标是完成一个agent os的oryxos吧。我需要你reveiw 第16节的文档，看下第16节的文档是否有需要改动的。我们的目标是：会根据第16节和原始文档，作为spec kit的原料，让spec kit 开发完provider。你理解下
50. 16 是用来我跟用户讲的。你按照16的结构，修改16的内容
51. 16的图需要重新画吗？
52. 解决一下图中重叠的问题？
53. 不用，接下来我怎么用spec kit 执行。你别执行，告诉我方法即可
54. 重新review，重写17、18、19 三个md？你能知道我要什么吗
55. 改。而且也要review 图
56. ［粘贴 23 节课程规划全文］我需要写23的md？你规划下内容？
57. 可以，写完整的23，并配图，注意文件名称
58. 能通过23 节的文档，给spec kit 作为输入，然后实现吗
59. ［粘贴 OryxOS Memory 技术评审文档全文］基于这份资料生成 21节课 的MD。且需要配图。你先理解喜爱
60. 理解下
61. 可以
62. 要写22.md 你知道22要做什么内容吗
63. 你的推荐呢？
64. 可以的，按你的来。最终是要实现oryxos 的memory这一层。21和22你自己决定
65. ［粘贴 OryxOS Sandbox 技术评审文档全文］基于这份信息，写- 第二十四节课：Sandbox box是什么，原理是什么，业界怎么做的 的内容
66. 并画图
67. 基于24，准备：第二十五节课：Sandbox box 的实现和代码讲解——这一节课的目的是，Oryxos 中sanbox的实现；根据上一节的思路实现拆解任务；实现sandbox的代码；然后讲解sandbox的代码 的内容了
68. 需要根据最新的内容，更新一下docs/TechnicalSolution.md吧
69. 然后在合适的地方配图
70. 合适的位置画图，比如架构图，多画图，各个模块可以用当前的图。而且技术方案文档，需要和每节课的实现保持一致
71. 是不是少了，通过skill 定义一个agent那部分。通过web serivce 接口上传一个skil
72. skill定义一个agent。然后agent 定时运行
73. demo 只有两个。每日天气 每日科技日报
74. 核心能力四：Tool 体系 是指agent 对外的操作，比如查数据库等。Agent 应该是独立的模块？讨论下？通过skill 定义一个agent，llm 发起agent的运行或者持续运行？
75. 用中文输出
76. 但是要定义一个agent。比如上传一个skill，定义一个agent的话，通过http service 上传一个skill定义一个agent，这个怎么实现呢？在哪里呈现
77. 我们现在做的事agent os。Profile（WHO：身份/provider/tools/skills/schedules）加上 AgentService+ReActLoop（HOW：执行引擎）这两者组合起来本来就已经是"Agent"了这些是基础能力，不是agent。只有通过skill 定义一个功能后，才是我们要的业务agent
78. 当然要
79. 技术方案结构和内容是不是重新设计下。分为底座（agent os）和定义一个agent 两个部分。区分开。当前这些都是底座。然后我们会通过web service 来定义一个agent
80. ［粘贴新版 32 节课程大纲全文］你理解下，大纲以这个为准
81. 第 19 节｜Notify 模块：原理解析、实现、代码讲解……需要写19了。参考当前class 下的内容模板。写19。记得参考docs/TechnicalSolution.md
82. 整体的架构图重新画一下，需要体现agent，以及和外部的对接，prividor、notify等等，现在的架构图太简单了。整体架构。然后图全部改为svg。放到website/public/images 下
83. 架构图直接用这个：website/public/images/architecture.svg 就好了，不用重复画？
84. 也可以画一个新的website/public/images/docs-agent-lifecycle.svg 这种风格的架构图。也就是白底彩色的
85. docs/TechnicalSolution.md 用website/public/images/docs-architecture-light.svg 这个架构图
86. 把/Users/oker/Desktop/doc/ 下16～25的md挪到docs/class。后面16 开始的md 都在这里维护，因为进入开发阶段了
87. 你基于：docs/TechnicalSolution.md review 一下当前16～25的文档，看是否有需要完善和丰富的。先讨论下，你知道16～25 这些文档的目的吧。这些文档是用来跟人讲的，教学的文档，跟人讲清楚。意思就是课纲。也是用来给后续spec kit 执行的大纲
88. 注意21和23 这两节有点像docs/TechnicalSolution.md。也就是作memory和sandbox这两个模块的评审。内容风格要跟docs/TechnicalSolution.md类似，就是技术评审。
89. 可以，然后输出中文
90. 另外，文档中的图都用svg。放在统一的image目录下
91. 26到32的文档，你能一篇一篇按顺序写出来吗？按照当前的思路。你知道要注意什么吗？先不写。
92. 你看还缺少什么吗
93. 现在是fable模型了吗
94. 你当前是fable模型，你重新review一下16到25的内容。看是否有调整空间。你有上下文了。我不重复了
95. 然后有调整空间就直接改。然后按顺序完成26到32的md。顺序来。
96. 这是我们的技术方案：docs/TechnicalSolution.md 记得看技术方案。以及我们会用spec kit开发
97. scripts/package.sh 执行失败，怎么处理
98. 比如16节课的内容。我想用spec kit 开发。我要怎么操作。给我执行命令
99. 和指导16节课的需求
100. 在docs/class 下生成这个执行指导的.md 文件？
101. docs/class/第16节：Agent Provider 原理解析、实现与代码讲解.md 中规划一下怎么验收功能？也就是建立harness。加一个部分，如何验收？比如加这部分的单测。
102. 这部分harness 在speckit 执行时，会被执行吧
103. 需要调整docs/class/Spec-Kit 执行指导：从课件到代码（以第16节为例）.md 这个手册吗
104. 17到31。中也加上harness这部部分。不用强制，合适的时候加就行，不合适的时候就不加。你能理解我要做什么吗
105. oryxos 最终运行天气、日报的agent的角度、从用spec kit 实现的角度review每节课文档的设计。核心是 spec kit 根据文档能不能拆除可拆解的任务，然后生成好的结果。
106. 按你的来，就行
107. 另外我需要你补充harness，也就是提示词，让代码质量符合预期。保证质量。切生成的功能是围绕docs/TechnicalSolution.md oryxos的设计来的，因为我们交付的是oryxos 这个最终结果。你有harness补充吗？
108. 或者说门禁也行
109. 你写skill 之前，能写个我们如何设计harness的md吗。也就是我们刚刚聊的内容。门禁，保证每个功能开发都能尽量符合预期
110. 开始写skill
111. 把对话中，docs/prompt/prompt.md 中没有的提示词，都要写入到docs/prompt/prompt.md 中

## 第三轮（逐节开发执行期：16/17 节落地）

112. scripts/package.sh 执行完成后，不会commit 本地代码
113. 切换到远程的class-16 分支
114. /oryxos-lesson-dev 16
115. 继续（tasks 停点：确认 OryxTool.getInputSchema 补齐、ProviderRequest 值对象、两项 clarify 默认后放行 implement）
116. 给我总结一下代码的改动点和重点review的部分
117. /oryxos-lesson-dev 17
118. 1. 不一定是当前9个模块，可以根据需要新建，调整模块名称。比如增加orysox-sandbox模块。按照agent 的模块来。 这点可以写入到claude.md 或者.specify/memory/constitution.md 2. 3、4 其他的按你的来（17 节 tasks 停点：批准 D1 契约上移，并把"模块结构可按需演进"写入宪法 v1.1.0）
119. 总结一下代码变更，重点reveiw，如何验证。 并把这个环节加入到skill中国呢
120. 把对话中新增的提示词存储docs/prompt/prompt.md 中

## 第四轮（逐节开发执行期：18~25 节 + 白名单动态管理端点）

121. 切换到远程的 class-18 分支
122. /oryxos-lesson-dev 18
123. 继续
124. 写一个文档，cli 的使用文档
125. 放到 docs 下
126. 切换到远程的：class-19 分支
127. /oryxos-lesson-dev 19
128. 继续
129. 对接了哪些渠道，当前，给个清单
130. 企业微信、飞书、lark、dingding 需要对接，有现成的实现吗？你能实现吗
131. 你能解决就行。我不管了（授权：provider 回退事故按前一分支基线静默修复，免请示）
132. 切换到 class-20 分支
133. /oryxos-lesson-dev 20
134. 继续
135. 当前实现了哪些 tool，业界主流都会用哪些 tool。基础的 tool 清单给一个
136. 实现缺的 5 个 tool，同时更新文档：docs/class/第20节：Tool 体系 原理解析、实现与代码讲解.md
137. docs/class/第21节：Memory 原理解析、业界方案与 OryxOS 设计评审.md 中加两部分：mem0 和知识图谱
138. 修改 docs/class/第22节：Memory 实现与代码讲解.md 的内容。插件化的 memory 实现机制。md、sqlite、mem0 三种。也就是实现三种
139. 当前的分支是？
140. 切换到 class-22 分支
141. /oryxos-lesson-dev 22
142. A，以课件为准，去修改技术方案
143. 继续
144. react 在哪里调用了 memory 的保存和读取
145. 第23节：Sandbox 原理解析… 补充业界主流 sandbox 的实现思路（比如 claude code、hermes agent）、学界对 sandbox 的讨论；helix 也是 sandbox 的一种吗？先讨论要补什么、社区主流实现思路是什么
146. 不管 helix 了，给你 https://github.com/nousresearch/hermes-agent
147. 1. 肯定要联网核实再写的
148. 23 改了后，24 需要改吗？
149. 可以，你决定
150. 切换到 class-24 分支
151. /oryxos-lesson-dev 24
152. 确认（第24节 H0 五处偏差 + tasks 停点）
153. 白名单的配置方法是不是只能是 application.yml 的格式
154. 那我能通过 http 的接口修改白名单吗？
155. 管理员需要能够动态操作白名单。给我实现在 web service 模块，实现白名单的增加、查询和删除三个接口
156. 给服务运行的命令和 curl 的测试命令
157. 切换到 class-25 分支
158. /oryxos-lesson-dev 25
159. 继续
160. AgentService 是在哪节课实现的
161. profile 的列表数据是哪里来的
162. 记录下还没记录到 docs/prompt/prompt.md 中的提示词，记录到 docs/prompt/prompt.md 中
