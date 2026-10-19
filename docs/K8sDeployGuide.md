# K8s 部署指南（039 / 042）

OryxOS 的第三种部署形态：官方 Helm Chart（`charts/oryxos/`）。裸机 tar.gz 与 docker compose 形态不变——Helm 是新增选项，不是归顺 K8s（裸机 / VM / K8s 都是一等形态）。

## 一条命令安装

前提：K8s ≥1.27、共享 PostgreSQL（集群外或自备）、多副本时具备真实共享语义的 RWX PVC（见 [共享卷指南](SharedVolumeGuide.md)）。生产共享部署先创建 PVC，并在卷根预置普通文件 `.workspace-id`，内容与下方 identity 一致；容器 uid 1000 须能读写该卷。

```bash
# 1) 两项密文（此外须先准备共享 PVC 与身份文件）
kubectl create secret generic oryxos-db \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:postgresql://<host>:5432/<db>' \
  --from-literal=SPRING_DATASOURCE_USERNAME='<user>' \
  --from-literal=SPRING_DATASOURCE_PASSWORD='<pass>'
kubectl create secret generic oryxos-master-key \
  --from-literal=ORYXOS_MASTER_KEY="$(openssl rand -base64 32)"

# 2) 安装（默认双副本；chart 也随 GitHub Release 提供 tgz 附件）
helm install oryxos charts/oryxos \
  --set database.existingSecret=oryxos-db \
  --set masterKey.existingSecret=oryxos-master-key \
  --set workspace.existingClaim=oryxos-shared-rwx \
  --set workspace.provider=shared-posix \
  --set workspace.identity=production-workspace-01

# 3) 验证
kubectl rollout status deploy/oryxos
kubectl port-forward svc/oryxos 8080:8080 &
curl -s localhost:8080/actuator/health/readiness
# 通过管理台或带已配置认证的 API 请求检查 /api/v1/instances：双副本 alive、clusterEnabled=true
```

缺密文配置或 shared-posix 的 identity 时渲染即报错；PVC 身份文件缺失、不匹配或能力不满足时应用拒绝启动。Chart 为兼容旧部署仍默认 `provider: local`，不会自动获得共享卷身份保护；上例显式启用 shared-posix。

## 常用 values

| 键 | 默认 | 说明 |
|----|------|------|
| `replicaCount` | 2 | 副本数（1 亦合法；>1 强制 RWX 工作区） |
| `image.repository`/`image.tag` | ghcr.io/oryx-labs/oryxos / appVersion | 镜像 |
| `workspace.existingClaim` | "" | 引用预置 PVC；非空时 chart 不创建或接管 PVC |
| `workspace.provider` / `identity` | local / "" | 生产共享部署选 shared-posix，并匹配卷根 `.workspace-id` |
| `workspace.accessMode` | ReadWriteMany | 多副本必须声明 RWX；chart 不验证存储后端的实际语义 |
| `workspace.storageClassName`/`size` | 集群默认 / 5Gi | 仅 chart 创建 PVC 时使用 |
| `shutdown.gracePeriodSeconds` | 40 | 终止宽限（与 compose 同口径） |
| `shutdown.drainTimeout` | 30s | 在途请求排空 + 停机阶段上限（适配真实 LLM 轮次） |
| `otel.endpoint` | ""（禁用） | OTLP gRPC 端点（如 `http://jaeger:4317`）；配置即导出 trace（traceId 与 `/api/v1/audit/trace/{id}` 同源互查），不配零开销 |
| `otel.metricsEndpoint` | ""（禁用） | #471：指标 OTLP/HTTP 端点（如 `http://collector:4318/v1/metrics`）——LLM 时延/token/成本/错误与 JVM/HTTP 全量指标推送 OTel 后端（`service.name=oryxos` 与 trace 关联）；Prometheus 拉取口径不受影响 |
| `resources` | 512Mi/250m ~ 2Gi/2 | 容器资源 |
| `extraConfig` | {} | 合并进 `/data/config/application.yml` 的任意段（providers/embedding 等） |

完整契约：[Helm values 契约](../specs/039-k8s-delivery/contracts/helm-values.md)；042 的身份、恢复及 existingClaim 行为见 [共享卷指南](SharedVolumeGuide.md)；渲染断言：`make helm-lint`。

## 升级与回滚

```bash
helm upgrade oryxos charts/oryxos --reuse-values --set image.tag=v0.1.8-RELEASE
helm rollback oryxos            # 回上一版
```

滚动策略 `maxUnavailable=0, maxSurge=1`：先起新副本、旧副本收到终止信号后先摘流量（readiness）→ preStop 5s 等 endpoint 摘除传播 → graceful 排空在途请求（drainTimeout）→ 释放渠道属主租约与在途轮次收尾 → 退出。升级期间连续请求零失败为验收口径（`scripts/rolling-probe.sh` 可自测）。新旧版本短暂共存安全（Flyway V6+ 前向兼容迁移纪律；含新迁移时由启动锁串行化）。

## 探针语义

- **liveness** `/actuator/health/liveness`：仅进程活性（DB 抖动不触发重启）。
- **readiness** `/actuator/health/readiness`：状态位 + **数据库与工作区可用**——DB 断连、身份丢失、存储探针过期或重载失败使副本摘流。工作区探针检查身份、写入、原子替换和删除，缓存超过 15 秒视为不可用；不能将探针通过等同于跨主机高可用证明。
- 两端点免认证；`/api/v1/health` 保持常量 200（裸机/compose 探活口径不变）。

## 排查入口

| 症状 | 检查 |
|------|------|
| Pod CrashLoopBackOff | `kubectl logs`：主密钥不匹配（022 指路恢复）/ 集群档误配（026 拒启文案）/ DB 不可达 |
| Pod Running 但 0/1 Ready | readiness 含 DB/workspace——确认数据库网络、PVC、`.workspace-id` 与重载日志；`kubectl describe pod` 看探针输出 |
| 第二副本 Pending | 多副本 + 非 RWX 存储类：PVC 绑定失败，见 [共享卷指南](SharedVolumeGuide.md) |
| 安装即报错 | 缺 `database.*`/`masterKey.*` 必填（报错文案指路本文档） |
| A 建 Agent B 不可见 | 确认两 Pod 使用同一真实共享 PVC；检查版本轮询/周期对账；停写维护后的直接改盘可用 `POST /api/v1/workspace/refresh` 加速收敛 |

## 本地验收（kind）

`scripts/kind-smoke.sh <image:tag>` 一键完成：附带 PG → 密文 → 单节点 RWX（hostPath 静态 PV）→ 预置身份文件和 existingClaim → shared-posix install → 双副本就绪 → 实例双活 → 跨副本可见性断言。CI 的 helm job 对每个 PR 自动执行同一脚本。工具安装：`scripts/install-k8s-tools.sh all`（kind 需 docker，WSL2 用户见 `specs/039-k8s-delivery/quickstart.md` V0 环境阶梯）。

kind 冒烟使用单宿主存储，不能替代跨主机 NFS/CephFS 验收。042 的真实双主机 T013 仍待提供环境；操作步骤见 [共享验收手册](../scripts/042-shared-acceptance.md)，本次已完成的测试、UI 与备份恢复证据见 [验收记录](../specs/042-workspace-storage/acceptance.md)。
