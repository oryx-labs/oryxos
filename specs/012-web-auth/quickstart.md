# Quickstart: Web Service 认证机制（最小 Auth）

**Feature**: 012-web-auth | **Date**: 2026-07-22

端到端验证脚本，证明 feature 跑通。前置：JDK 21、Maven、`oryxos init` 已跑（`.oryxos/` 存在）。

## 场景一：默认关，回归零破坏（SC-001）

```bash
# 1. 构建
mvn -q clean package -DskipTests  # 或 mvn -q verify 过全门禁

# 2. 起 serve（auth 默认 false）
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080

# 3. 访问管理台——不弹认证，直接进
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/admin/
# 期望: 200（或 302→/admin/）

# 4. 访问 REST——不弹认证
curl -s http://localhost:8080/api/v1/health
# 期望: {"code":0,"message":"success","data":{"status":"ok"},"timestamp":...}
```

## 场景二：开 auth，保护管理台（US1）

```bash
# 1. 配置开 auth（改 jar 内 application.yml 或 config/application.yml 覆盖）
#    oryxos:
#      web:
#        auth:
#          enabled: true
#          realm: OryxOS

# 2. 首次开 auth 但无账号 → 启动阻断（SC-006）
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080
# 期望: 启动失败，stderr 提示 "No enabled web user found. Run 'oryxos user add <username>'"

# 3. 加 admin 账号
java -jar oryxos-boot/target/oryxos-boot-*.jar user add admin
# 交互输密码（不回显）+ 确认
# 期望: Created user 'admin'

# 4. 列账号（不显密码，SC-004）
java -jar oryxos-boot/target/oryxos-boot-*.jar user list
# 期望:
# USERNAME  ENABLED  CREATED_AT
# admin     true     2026-07-22T...

# 5. 起 serve
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080

# 6. 无凭据访问 /admin/ → 401 + WWW-Authenticate（SC-002）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/admin/
# 期望: 401
curl -s -D - http://localhost:8080/admin/ | grep -i www-authenticate
# 期望: WWW-Authenticate: Basic realm="OryxOS"

# 7. 正确凭据 → 200（SC-002）
curl -s -o /dev/null -w "%{http_code}\n" -u admin:<密码> http://localhost:8080/admin/
# 期望: 200

# 8. 错误凭据 → 401
curl -s -o /dev/null -w "%{http_code}\n" -u admin:wrongpass http://localhost:8080/admin/
# 期望: 401

# 9. REST 不受影响（SC-003）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/health
# 期望: 200（无凭据也通）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/v1/profiles
# 期望: 200（REST 不拦）
```

## 场景三：禁用账号（US1 场景 6）

```bash
# 禁用 admin（另开终端或先停 serve）
java -jar oryxos-boot/target/oryxos-boot-*.jar user disable admin
java -jar oryxos-boot/target/oryxos-boot-*.jar user list
# 期望: admin  enabled=false

# 重启 serve，用 admin 登录 → 401（禁用账号登不进）
java -jar oryxos-boot/target/oryxos-boot-*.jar serve --port 8080
curl -s -o /dev/null -w "%{http_code}\n" -u admin:<密码> http://localhost:8080/admin/
# 期望: 401
# 注意：开 auth 但无 enabled 账号 → serve 启动阻断（SC-006），需先加第二个账号或先 enable admin
```

## 场景四：改密码（US2 场景 4）

```bash
java -jar oryxos-boot/target/oryxos-boot-*.jar user passwd admin
# 输新密码 + 确认
# 期望: Password updated for 'admin'
# 旧密码 401，新密码 200
```

## 单元/切片测试

```bash
mvn -q test -pl oryxos-storage -Dtest=WebUserServiceTest
mvn -q test -pl oryxos-web -Dtest=BasicAuthFilterTest
mvn -q test -pl oryxos-cli -Dtest=UserCommandTest
```

## 全门禁

```bash
mvn -q verify
# Spotless + P3C + Checkstyle + SpotBugs/FSB + OWASP Dep-Check 全绿
```

## 预期产出

- 场景一：4 个 curl 全 200/302，REST 正常
- 场景二：无凭据 401 + `WWW-Authenticate`，正确凭据 200，错误 401，REST 不受影响
- 场景三：禁用账号 401
- 场景四：改密码后旧 401 新 200
- `user list` 输出 0% 含密码/hash
- `web_users` 表 `password_hash` 列 0% 明文
