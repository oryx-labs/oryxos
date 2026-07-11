# Quickstart: CLI 验证指引

**Date**: 2026-07-10 | **Feature**: [spec.md](./spec.md)

## 前置

- JDK 21、Maven；16/17 节交付物在位（`mvn test -pl oryxos-provider,oryxos-storage -am` 绿）。

## 自动化验收（harness 判卷）

```bash
# 全量门禁（完成定义）
mvn clean verify

# 只跑本节两个测试类（会话层）
mvn test -pl oryxos-storage -am -Dtest='SessionManagerTest,SessionRepositoryTest' -Dsurefire.failIfNoSpecifiedTests=false
```

关键回归（课件中文名 → @DisplayName 对号）：

| @DisplayName（课件原文） | 守点 |
|---|---|
| 同一三元组_历次getOrCreate都是同一个Session | 两次 getOrCreate id 相等（幂等）+ 换 channel 则 id 不等（隔离） |

## 回归证据（跨节契约）

```bash
mvn test -pl oryxos-core,oryxos-provider,oryxos-storage -am    # 16/17 节全部回归
grep -rn '":"' oryxos-*/src/main/java --include='*.java' | grep -v JpaSessionManager   # 预期无 id 拼接逃逸
```

## 人工项（课件"五、怎么用，做完怎么验"，harness 判不了）

1. `mvn -pl oryxos-boot -am package` 后 `java -jar oryxos-boot/target/*.jar chat`：进入交互、多轮对话、`/quit` 正常退出；Demo 一对话版真模型走通（需 DEEPSEEK_API_KEY 与 default Profile）。
2. 轻命令秒回：`oryxos init` / `oryxos profile list` 无 Spring 启动等待。
3. chat 启动日志 "Found N JPA repository interfaces" 的 N > 0（课件坑四）。
4. 三种模式共享存储：chat 留下的会话，serve 模式（26 节后）可查到。
5. 12 个子命令 `--help` 全部正常。
