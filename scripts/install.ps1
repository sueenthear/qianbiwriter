#Requires -Version 5.1
<#
.SYNOPSIS
    把浅笔记事安装到已连接的设备/模拟器上。

.DESCRIPTION
    查找 adb（优先 Android SDK 的 platform-tools），安装指定变体的 APK，
    并按需启动主界面。APK 优先取项目根目录的构建产物，其次取 app/build/outputs 下的产物。

.PARAMETER Variant
    安装哪一个变体：debug（默认）或 release。

.PARAMETER Launch
    安装完成后自动启动应用。

.PARAMETER ApkPath
    直接指定 APK 路径（优先级最高）。

.EXAMPLE
    pwsh -File .\scripts\install.ps1 -Launch

.EXAMPLE
    pwsh -File .\scripts\install.ps1 -Variant release -ApkPath .\QianbiWriter-release-v1.0.apk
#>
[CmdletBinding()]
param(
    [ValidateSet('release', 'debug')][string]$Variant = 'debug',
    [switch]$Launch,
    [string]$ApkPath
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot

function Resolve-SdkDir {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate) { return $candidate }
    }
    $localProps = Join-Path $projectRoot 'local.properties'
    if (Test-Path $localProps) {
        $match = Select-String -Path $localProps -Pattern '^\s*sdk\.dir\s*=' | Select-Object -First 1
        if ($match) {
            return ((($match.Line -split '=', 2)[1].Trim()) -replace '\\\\', '\' -replace '\\:', ':')
        }
    }
    return $null
}

# ---------- 找 adb ----------
$adb = $null
$sdk = Resolve-SdkDir
if ($sdk) {
    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path $candidate) { $adb = $candidate }
}
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) {
    throw '未找到 adb：请安装 Android SDK platform-tools，或把 platform-tools 加入 PATH。'
}

# ---------- 检查设备 ----------
$devices = @(& $adb devices 2>&1 | Select-String -Pattern "`tdevice")
if ($devices.Count -eq 0) {
    throw '没有已授权的设备。请连接手机开启 USB 调试，或先启动模拟器，再重试。'
}
Write-Host ("检测到 {0} 台设备：" -f $devices.Count) -ForegroundColor Cyan
$devices | ForEach-Object { Write-Host ("  {0}" -f $_.Line) }

# ---------- 找 APK ----------
if (-not $ApkPath) {
    $patterns = @(
        (Join-Path $projectRoot ("QianbiWriter-{0}-v*.apk" -f $Variant)),
        (Join-Path $projectRoot "app\build\outputs\apk\$Variant\*.apk")
    )
    foreach ($pattern in $patterns) {
        $found = Get-ChildItem -Path $pattern -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($found) { $ApkPath = $found.FullName; break }
    }
}
if (-not $ApkPath -or -not (Test-Path $ApkPath)) {
    throw "未找到 $Variant 变体的 APK，请先执行：pwsh -File .\scripts\build.ps1 -Variant $Variant"
}

Write-Host ("安装: {0}" -f $ApkPath) -ForegroundColor Cyan
& $adb install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "adb install 失败（退出码 $LASTEXITCODE）。若提示签名冲突，先卸载旧包：adb uninstall <applicationId>"
}

if ($Launch) {
    # debug 变体带 .debug 后缀
    $appId = 'com.qianbi.writer'
    if ($Variant -eq 'debug') { $appId += '.debug' }
    Write-Host ("启动: {0}/.MainActivity" -f $appId) -ForegroundColor Cyan
    & $adb shell am start -n "$appId/.MainActivity"
}

Write-Host ''
Write-Host '完成。查看日志：adb logcat -s AndroidRuntime:E ActivityTaskManager:I' -ForegroundColor Green
