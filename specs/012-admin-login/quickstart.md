# Quickstart: 验证管理后台用户登录

本指南用于验收 `012-admin-login` 的实现，不要求执行任何初始化或迁移命令。

## 1. 本地配置

生产环境必须通过部署平台或密钥管理系统注入配置，不要把明文密码、密码散列、Cookie、CSRF token 或 session id 写进仓库。

PowerShell 示例：

```powershell
$env:ORYXOS_ADMIN_USERNAME = 'admin'
$env:ORYXOS_ADMIN_PASSWORD_HASH = '{bcrypt}<encoded-password-hash>'
$env:ORYXOS_SECURITY_COOKIE_SECURE = 'false' # only for local HTTP validation
```

本地 HTTP 验证可临时把 secure cookie 设为 `false`；生产 HTTPS 环境应设为 `true`。

## 2. 构建与测试命令

在 JDK 21 + Maven 工具链下执行完整验证；Windows 本机需要把 Git Bash 加入 PATH，供 shell 工具测试使用：

```powershell
$env:JAVA_HOME='C:\Users\yuche\.jdks\jdk-21.0.11+10'
$env:Path="$env:JAVA_HOME\bin;C:\Program Files\Git\bin;$PWD\.tools\apache-maven-3.9.9\bin;$env:Path"
& '.tools\apache-maven-3.9.9\bin\mvn.cmd' verify
```

前端构建：

```powershell
Push-Location oryxos-web/src/main/frontend
& '..\..\..\target\node\npm.cmd' run build
Pop-Location
```

完整 `mvn verify` 已在 JDK 21.0.11 + Git Bash PATH 下通过。不要使用当前 JDK 26 工具链执行完整验证；既有 Mockito/Byte Buddy、Spotless、SpotBugs 插件仍可能触发 Java 26 兼容问题。

## 3. 未配置部署安全性

1. 不提供 `ORYXOS_ADMIN_USERNAME` 和 `ORYXOS_ADMIN_PASSWORD_HASH` 启动服务。
2. 请求 `GET /api/v1/auth/status`，应返回 `configured=false`。
3. 请求受保护的 `GET /api/v1/info`，应得到统一 JSON 401，且不泄露 Provider 或运行信息。
4. 不存在任何默认用户名或默认密码可以登录。

## 4. 成功登录与受保护访问

1. `GET /api/v1/auth/csrf`，保存同源 Cookie、`headerName` 和 `token`。
2. 携带 Cookie 与 CSRF header 调用 `POST /api/v1/auth/login`，提交已配置管理员账号和正确密码。
3. 验证 HTTP 200、`ApiResponse.code=0`，响应不包含密码、密码散列或 session id。
4. 使用同一 Cookie 请求 `GET /api/v1/info`、`GET /api/v1/agents`、`GET /api/v1/auth/events`，均应通过认证。
5. 打开 `/admin/`，未登录时只出现登录界面；登录成功后才加载运行状态和管理数据。

## 5. 失败、锁定与退出

1. 不存在的账号与正确账号错误密码都应返回相同的通用 401 文案。
2. 同一提交账号 15 分钟内连续失败 5 次后，后续尝试返回 429，且不会建立认证会话。
3. 锁定窗口结束后，正确密码可恢复登录。
4. 登录后携带有效 CSRF 调用 `POST /api/v1/auth/logout`；随后同一 Cookie 请求受保护 API 应返回 401。
5. `GET /api/v1/auth/events?limit=100` 应显示成功、失败、锁定、退出事件；记录不得包含密码、密码散列、CSRF token 或 Cookie。

## 6. 回归检查

- `GET /api/v1/health` 与 `GET /actuator/health` 仍可匿名访问。
- `/swagger-ui/**`、`/v3/api-docs/**`、`/actuator/**` 非健康端点、系统运行信息和所有管理 API 均不能匿名访问。
- `/admin/**` 静态资源可匿名刷新，但未认证状态不得预加载管理数据。
- 登录页沿用 OryxOS 管理台现有视觉 token，在桌面与窄屏下不白屏、不遮挡、不文本溢出。
