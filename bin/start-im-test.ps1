# Load oryxos/.env then start serve
$Root = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $Root ".env"
if (-not (Test-Path $EnvFile)) { throw "Missing $EnvFile" }
Get-Content $EnvFile -Encoding UTF8 | ForEach-Object {
  $line = $_.Trim()
  if (-not $line -or $line.StartsWith("#")) { return }
  if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
    $name = $Matches[1]
    $val = $Matches[2].Trim().Trim('"').Trim("'")
    Set-Item -Path "Env:$name" -Value $val
  }
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$jar = Join-Path $Root "oryxos-boot\target\oryxos-boot-0.1.4-RELEASE.jar"
if (-not (Test-Path $jar)) { throw "Missing jar: $jar — run mvn package first" }
$port = if ($args.Count -ge 1) { $args[0] } else { "8081" }
Set-Location $Root
& $java -jar $jar serve --port $port
