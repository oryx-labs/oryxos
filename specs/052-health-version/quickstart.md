# Quickstart: Health 端点暴露版本信息

**Feature**: 052-health-version

验证 `/api/v1/health` 返回体已含 `version`/`build` 且向后兼容。详细字段规则见 [contracts/health.md](contracts/health.md) 与 [data-model.md](data-model.md)。

## 前置

- 已完成 `/speckit-implement`（build-info 插件 + git-commit-id 插件 + `HealthView`/`VersionInfoProvider` 落地）
- 本地可构建运行 OryxOS

## 场景 1：正式构建下看到真实版本

```bash
mvn -q clean package -DskipTests
java -jar oryxos-boot/target/oryxos-*.jar &   # 或 bin/start.sh
curl -s http://localhost:8080/api/v1/health | jq .data
```

**预期**：`data` 含 `status:"ok"`、`version`（= 项目 Maven 版本号，当前 `0.1.8-RELEASE`）、`build`（git short sha，如 `a1b2c3d`）。对应 US1 / C-2 / C-3。

## 场景 2：多次请求取值稳定

```bash
curl -s http://localhost:8080/api/v1/health | jq -r '.data.version + " " + .data.build'
curl -s http://localhost:8080/api/v1/health | jq -r '.data.version + " " + .data.build'
```

**预期**：两次输出完全一致。对应 FR-003 / C-4 / SC-003。

## 场景 3：向后兼容（现有调用方不受影响）

```bash
curl -s http://localhost:8080/api/v1/health | jq -e '.data.status == "ok"'
```

**预期**：退出码 0（`status` 原字段仍在、仍为 `ok`）。对应 US2 / C-1 / SC-002。

## 场景 4：源码直接运行的降级

```bash
# 不打包、未生成 git.properties 的开发态运行
mvn -q -pl oryxos-boot spring-boot:run
curl -s http://localhost:8080/api/v1/health | jq .data
```

**预期**：`version`/`build` 为可解析的占位值（如 `unknown` / `dev` / 构建时间），字段存在、非 null、不报错。对应 FR-004 / C-5 / SC-004。

## 通过标准

- 4 个场景全部符合预期
- 既有健康检查相关测试（若有）仍绿
