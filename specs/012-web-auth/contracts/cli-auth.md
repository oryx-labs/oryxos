# CLI Contract: `oryxos user`

**Feature**: 012-web-auth | **Date**: 2026-07-22

`oryxos user` 子命令组契约。所有 leaf 都是 heavy command（自启 `OryxOsRuntime` Spring 上下文，`WebApplicationType.NONE`，`context.getBean(WebUserService.class)` 干活）。镜像 `ProfileCommand`（group + static nested leaf）+ `ChatCommand`（heavy 启动）。

## 命令树

```
oryxos user
  ├── add    <username>            创建账号（交互输密码）
  ├── list                          列账号（不显密码）
  ├── delete <username>             删账号
  ├── passwd <username>             改密码（交互输新密码）
  ├── disable <username>            禁用账号
  └── enable  <username>            启用账号
```

parent `oryxos user`（无参数）打 usage 退出 0。

## 子命令契约

### `oryxos user add <username>`

- **参数**: `@Parameters(index="0") String username`（必填，1-64 字符，无空格）
- **交互**: 提示 `Password: `（`System.console().readPassword`，不回显）→ `Confirm: `（再读一次）
- **校验**: username 非空/无空格/≤64；password ≥8 字符；两次一致；username 未存在
- **行为**: `WebUserService.create(username, rawPassword)` → BCrypt 哈希 → 存 `web_users`（enabled=true）
- **成功**: stdout `Created user '<username>'`，退出码 0
- **失败**:
  - username 已存在 → stderr `Error: user '<username>' already exists`，退出码 1
  - password <8 → stderr `Error: password must be at least 8 characters`，退出码 1
  - 两次不一致 → stderr `Error: passwords do not match`，退出码 1
  - `System.console()==null`（piped stdin）→ stderr `Error: no interactive console available`，退出码 1
- **安全**: 明文密码 NEVER 打印/日志（宪法 VI）

### `oryxos user list`

- **参数**: 无
- **行为**: `WebUserService.list()` → 排序（按 username 或 createdAt）
- **输出**（表格或对齐文本，**不含密码/hash**）:
  ```
  USERNAME    ENABLED  CREATED_AT
  admin       true     2026-07-22T10:30:00Z
  alice       true     2026-07-22T11:00:00Z
  ```
- **空表**: stdout `No users found. Run 'oryxos user add <username>' to create one.`，退出码 0
- **安全**: 输出 0% 含 `password_hash` / 明文（SC-004）

### `oryxos user delete <username>`

- **参数**: `@Parameters(index="0") String username`
- **行为**: `WebUserService.delete(username)` → 删 `web_users` 行
- **成功**: stdout `Deleted user '<username>'`，退出码 0
- **失败**: username 不存在 → stderr `Error: user '<username>' not found`，退出码 1

### `oryxos user passwd <username>`

- **参数**: `@Parameters(index="0") String username`
- **交互**: `New password: `（不回显）→ `Confirm: `
- **校验**: password ≥8；两次一致；username 存在
- **行为**: `WebUserService.changePassword(username, rawPassword)` → 更新 `password_hash` + `updated_at`
- **成功**: stdout `Password updated for '<username>'`，退出码 0
- **失败**: 用户不存在 / 密码太短 / 不一致 / 无 console → stderr + 退出码 1

### `oryxos user disable <username>`

- **参数**: `@Parameters(index="0") String username`
- **行为**: `WebUserService.disable(username)` → `enabled=false` + `updated_at`
- **成功**: stdout `Disabled user '<username>'`，退出码 0
- **失败**: 用户不存在 → stderr，退出码 1

### `oryxos user enable <username>`

- **参数**: `@Parameters(index="0") String username`
- **行为**: `WebUserService.enable(username)` → `enabled=true` + `updated_at`（对称 disable，禁用后可恢复）
- **成功**: stdout `Enabled user '<username>'`，退出码 0
- **失败**: 用户不存在 → stderr，退出码 1

## 通用约定

- 所有 leaf `mixinStandardHelpOptions = true`（`-h`/`--help`）
- 退出码：成功 0，失败 1
- 错误到 stderr，结果到 stdout（便于脚本管道）
- heavy command：try-with-resources 起停 Spring，一次性退出
- 不缓存账号：每次命令重读 DB（FR-007 同精神，加账号后下次命令即可用）
