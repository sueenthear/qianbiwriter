#Requires -Version 5.1
<#
.SYNOPSIS
    一键构建 浅笔记事 的 APK。

.DESCRIPTION
    调用 Gradle Wrapper 构建指定变体，并把产物复制到项目根目录，
    命名为 QianbiWriter-<variant>-v<versionName>.apk，方便直接安装分发。

.PARAMETER Variant
    构建变体：release（默认，带签名）或 debug。

.PARAMETER Clean
    构建前先执行 clean。

.PARAMETER NoCopy
    只构建，不把 APK 复制到项目根目录。

.EXAMPLE
    pwsh -File .\scripts\build.ps1

.EXAMPLE
    pwsh -File .\scripts\build.ps1 -Variant debug -Clean
#>
[CmdletBinding()]
param(
    [ValidateSet('release', 'debug')][string]$Variant = 'release',
    [switch]$Clean,
    [switch]$NoCopy
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $projectRoot 'gradlew.bat'
$variantTitle = $Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1)
$tasks = @()
if ($Clean) { $tasks += 'clean' }
$tasks += "assemble$variantTitle"

if (-not (Test-Path $gradlew)) {
    throw "未找到 gradlew.bat，请在项目根目录内运行本脚本：$gradlew"
}

Write-Host ("===== 构建 {0} 变体 =====" -f $variantTitle) -ForegroundColor Cyan
Write-Host ("任务: {0} " -f ($tasks -join ' '))

Push-Location $projectRoot
try {
    & $gradlew @tasks --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle 构建失败（退出码 $LASTEXITCODE）。排错提示：先运行 scripts\check-env.ps1 检查环境。"
    }

    if ($NoCopy) { return }

    # 解析 versionName 用于产物命名
    $versionName = 'unknown'
    $gradleMatch = Select-String -Path (Join-Path $projectRoot 'app\build.gradle.kts') -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
    if ($gradleMatch) { $versionName = $gradleMatch.Matches[0].Groups[1].Value }

    $apkDir = Join-Path $projectRoot "app\build\outputs\apk\$Variant"
    $apk = Get-ChildItem -Path $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $apk) {
        throw "构建成功但未找到 APK：$apkDir"
    }

    $dest = Join-Path $projectRoot ("QianbiWriter-{0}-v{1}.apk" -f $Variant, $versionName)
    Copy-Item $apk.FullName $dest -Force

    $sizeMb = [math]::Round($apk.Length / 1MB, 2)
    Write-Host ''
    Write-Host '构建成功' -ForegroundColor Green
    Write-Host ("  产物: {0}" -f $dest)
    Write-Host ("  大小: {0} MB" -f $sizeMb)
    Write-Host ("  安装: adb install -r `"{0}`"" -f $dest)
} finally {
    Pop-Location
}
