<#
install.ps1 — Windows 下安装 OryxOS CLI（构建 jar + 注册 PowerShell 函数 + 自检）

步骤：
  1. mvn -pl oryxos-boot -am package -DskipTests
  2. 在当前用户 PowerShell 配置文件（$PROFILE）写入 oryxos 函数
  3. 执行 oryxos --help 验证

用法（在项目根目录）：
  powershell -ExecutionPolicy Bypass -File scripts\install.ps1     # Windows PowerShell 5.1
  pwsh     -ExecutionPolicy Bypass -File scripts\install.ps1      # PowerShell 7+

说明：
  • 幂等可重复运行：$PROFILE 中旧函数块（begin/end 标记之间）会被替换为最新版本。
    • jar 文件名带版本号（如 oryxos-boot-0.1.5-RELEASE.jar），函数内用通配符动态
    解析最新产物，且排除 spring-boot repackage 留下的 *.jar.original。
#>

$ErrorActionPreference = 'Stop'

# ── 日志 ────────────────────────────────────────────────────────────────────────
function Info { param($Msg) Write-Host "[INFO]  $Msg" -ForegroundColor Green }
function Warn { param($Msg) Write-Host "[WARN]  $Msg" -ForegroundColor Yellow }
function Fail { param($Msg) Write-Host "[ERROR] $Msg" -ForegroundColor Red; exit 1 }

# ── 路径定位 ────────────────────────────────────────────────────────────────────
$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$BootTarget  = Join-Path $ProjectRoot 'oryxos-boot\target'

# ── Step 0: 依赖检查 ────────────────────────────────────────────────────────────
foreach ($cmd in 'java', 'mvn') {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Fail "$cmd 不在 PATH 中，请先安装并配置环境变量后重试。"
    }
}

# ── Step 1: Maven 打包 ──────────────────────────────────────────────────────────
Info "Step 1/3: mvn -pl oryxos-boot -am package -DskipTests"
Push-Location $ProjectRoot
try {
    & mvn -pl oryxos-boot -am package -DskipTests
    if ($LASTEXITCODE -ne 0) { Fail "Maven 构建失败（exit code $LASTEXITCODE）。" }
} finally {
    Pop-Location
}

$Jar = Get-ChildItem -Path $BootTarget -Filter 'oryxos-boot-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notlike '*.original' } |
       Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $Jar) { Fail "未在 $BootTarget 找到 oryxos-boot-*.jar，请检查构建输出。" }
Info "构建产物: $($Jar.FullName)"

# ── Step 2: 写入当前用户 $PROFILE ───────────────────────────────────────────────
$FuncBlock = @"
# >>> oryxos begin >>> (managed by scripts/install.ps1)
function oryxos {
    `$jar = Get-ChildItem -Path '$BootTarget' -Filter 'oryxos-boot-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { `$_.Name -notlike '*.original' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not `$jar) {
        Write-Error 'oryxos jar 未找到（$BootTarget）。请在项目根目录重新运行 scripts\install.ps1。'
        return
    }
    # 中文 Windows 控制台默认 GBK，而 JDK 18+ / Logback 输出 UTF-8，须让 PS 按 UTF-8 解码
    `$oldEnc = [Console]::OutputEncoding
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    try {
        java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -jar `$jar.FullName @args
    } finally {
        [Console]::OutputEncoding = `$oldEnc
    }
}
# <<< oryxos <<<
"@

$ProfilePath = $PROFILE
Info "Step 2/3: 写入 PowerShell 配置文件: $ProfilePath"

$ProfileDir = Split-Path -Parent $ProfilePath
if (-not (Test-Path $ProfileDir)) {
    New-Item -ItemType Directory -Force -Path $ProfileDir | Out-Null
}

# 幂等：先移除旧标记块（若有），再追加新块
$Pattern = '(?s)\r?\n?# >>> oryxos begin >>>.*?# <<< oryxos <<<\r?\n?'
if (Test-Path $ProfilePath) {
    $Raw = (Get-Content -Raw -Path $ProfilePath) -replace $Pattern, ''
    $New = $Raw.TrimEnd() + "`r`n`r`n" + $FuncBlock + "`r`n"
} else {
    $New = $FuncBlock + "`r`n"
}
Set-Content -Path $ProfilePath -Value $New -Encoding UTF8
Info "函数 oryxos 已写入（重复运行本脚本会自动更新为最新构建路径）。"

# ── Step 3: 验证执行 ────────────────────────────────────────────────────────────
Info "Step 3/3: oryxos --help"
Invoke-Expression $FuncBlock   # 在当前会话注册同名函数，模拟新终端效果
oryxos --help
if ($LASTEXITCODE -ne 0) { Fail "oryxos --help 退出码为 $LASTEXITCODE。" }

Info '安装完成！新开一个 PowerShell 窗口（或执行 . $PROFILE）后即可使用：oryxos <命令>'
