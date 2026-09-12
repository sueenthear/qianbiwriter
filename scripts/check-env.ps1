#Requires -Version 5.1
<#
.SYNOPSIS
    浅笔记事 开发环境自检。

.DESCRIPTION
    依次检查 JDK、Android SDK（platforms / build-tools / platform-tools）、Gradle Wrapper、
    签名文件、本地配置与连接设备，并对不通过的项给出中文修复建议。
    全部通过时退出码为 0，否则为未通过项数量。

.PARAMETER SdkDir
    显式指定 Android SDK 路径。默认依次读取参数 > $env:ANDROID_HOME > $env:ANDROID_SDK_ROOT > local.properties。

.EXAMPLE
    pwsh -File .\scripts\check-env.ps1

.EXAMPLE
    pwsh -File .\scripts\check-env.ps1 -SdkDir 'D:\Android\Sdk'
#>
[CmdletBinding()]
param(
    [string]$SdkDir
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$requiredCompileSdk = 'android-34'
$requiredBuildTools = [version]'34.0.0'
$requiredJdk = 17

$script:failCount = 0
$script:warnCount = 0

function Write-Result {
    param(
        [ValidateSet('OK', 'WARN', 'FAIL')][string]$Status,
        [string]$Name,
        [string]$Detail,
        [string]$Hint
    )
    $tag = switch ($Status) { 'OK' { '[ OK ]' } 'WARN' { '[WARN]' } 'FAIL' { '[FAIL]' } }
    $color = switch ($Status) { 'OK' { 'Green' } 'WARN' { 'Yellow' } 'FAIL' { 'Red' } }
    Write-Host ("{0} {1,-20} {2}" -f $tag, $Name, $Detail) -ForegroundColor $color
    if ($Hint) { Write-Host ("          -> {0}" -f $Hint) -ForegroundColor DarkGray }
    if ($Status -eq 'FAIL') { $script:failCount++ }
    elseif ($Status -eq 'WARN') { $script:warnCount++ }
}

function Resolve-SdkDir {
    param([string]$Explicit)

    if ($Explicit) { return $Explicit }
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate) { return $candidate }
    }
    $localProps = Join-Path $projectRoot 'local.properties'
    if (Test-Path $localProps) {
        $match = Select-String -Path $localProps -Pattern '^\s*sdk\.dir\s*=' | Select-Object -First 1
        if ($match) {
            $value = ($match.Line -split '=', 2)[1].Trim()
            # 反转义 properties 格式：C\:\\Android\\Sdk -> C:\Android\Sdk
            return ($value -replace '\\\\', '\' -replace '\\:', ':')
        }
    }
    return $null
}

Write-Host ''
Write-Host '===== 浅笔记事 开发环境自检 =====' -ForegroundColor Cyan
Write-Host ("项目根目录: {0}" -f $projectRoot)
Write-Host ''

# ---------- 1. 项目文件 ----------
foreach ($file in @('settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew.bat', 'local.properties')) {
    $path = Join-Path $projectRoot $file
    if (Test-Path $path) {
        Write-Result -Status OK -Name '项目文件' -Detail $file
    } else {
        Write-Result -Status FAIL -Name '项目文件' -Detail "$file 缺失" -Hint "确认在项目根目录运行，或重新检出该文件。"
    }
}

# ---------- 2. JDK ----------
$javaExe = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) { $javaExe = $javaCmd.Source }
}

if (-not $javaExe) {
    Write-Result -Status FAIL -Name 'JDK' -Detail '未找到 java' -Hint '安装 JDK 17（如 Temurin/MS Microsoft Build of OpenJDK）并设置 JAVA_HOME。'
} else {
    $javaOut = (& $javaExe -version 2>&1 | Select-Object -First 1) -join ''
    $javaMajor = $null
    if ($javaOut -match 'version "(\d+)(\.(\d+))?') {
        $javaMajor = [int]$Matches[1]
        if ($javaMajor -eq 1 -and $Matches[3]) { $javaMajor = [int]$Matches[3] }  # 1.8.0 -> 8
    }
    if ($javaMajor -and $javaMajor -ge $requiredJdk) {
        Write-Result -Status OK -Name 'JDK' -Detail ("{0} (major {1})" -f $javaExe, $javaMajor)
    } elseif ($javaMajor) {
        Write-Result -Status FAIL -Name 'JDK' -Detail ("major {0} < {1}" -f $javaMajor, $requiredJdk) -Hint 'Android Gradle Plugin 8.5 需要 JDK 17+，请安装并设置 JAVA_HOME。'
    } else {
        Write-Result -Status WARN -Name 'JDK' -Detail "无法解析版本：$javaOut" -Hint '请手动确认 java -version 输出。'
    }
}

# ---------- 3. Gradle Wrapper ----------
$wrapperProps = Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.properties'
$wrapperJar = Join-Path $projectRoot 'gradle\wrapper\gradle-wrapper.jar'
if ((Test-Path $wrapperJar) -and (Test-Path $wrapperProps)) {
    $distUrl = (Select-String -Path $wrapperProps -Pattern 'distributionUrl=(.+)' | Select-Object -First 1).Matches[0].Groups[1].Value
    $gradleVersion = if ($distUrl -match 'gradle-([\d.]+)-') { $Matches[1] } else { '未知' }
    Write-Result -Status OK -Name 'Gradle Wrapper' -Detail ("gradle {0}" -f $gradleVersion)
    $distName = "gradle-$gradleVersion-bin"
    $cacheDir = Join-Path $env:USERPROFILE ".gradle\wrapper\dists\$distName"
    if (Test-Path $cacheDir) {
        Write-Result -Status OK -Name 'Gradle 缓存' -Detail "已下载 $distName"
    } else {
        Write-Result -Status WARN -Name 'Gradle 缓存' -Detail "$distName 尚未下载" -Hint '首次构建会自动下载（约 130MB），国内网络建议保持 settings.gradle.kts 中的阿里云镜像。'
    }
} else {
    Write-Result -Status FAIL -Name 'Gradle Wrapper' -Detail 'gradle/wrapper 不完整' -Hint '需要 gradle-wrapper.jar 与 gradle-wrapper.properties。'
}

# ---------- 4. Android SDK ----------
$sdk = Resolve-SdkDir -Explicit $SdkDir
if (-not $sdk) {
    Write-Result -Status FAIL -Name 'Android SDK' -Detail '未找到 SDK 路径' -Hint '设置环境变量 ANDROID_HOME，或在 local.properties 写入 sdk.dir=C:\\Android\\Sdk。'
    $sdk = ''
} elseif (-not (Test-Path $sdk)) {
    Write-Result -Status FAIL -Name 'Android SDK' -Detail "路径不存在：$sdk" -Hint '修正 local.properties 中的 sdk.dir 或设置 ANDROID_HOME。'
} else {
    Write-Result -Status OK -Name 'Android SDK' -Detail $sdk
}

if ($sdk -and (Test-Path $sdk)) {
    # platform
    $platformDir = Join-Path $sdk "platforms\$requiredCompileSdk"
    if (Test-Path $platformDir) {
        Write-Result -Status OK -Name 'SDK Platform' -Detail $requiredCompileSdk
    } else {
        Write-Result -Status FAIL -Name 'SDK Platform' -Detail "缺少 $requiredCompileSdk" -Hint "用 sdkmanager 安装：sdkmanager `"platforms;$requiredCompileSdk`""
    }

    # build-tools
    $btRoot = Join-Path $sdk 'build-tools'
    $btVersions = @()
    if (Test-Path $btRoot) {
        $btVersions = Get-ChildItem $btRoot -Directory |
            Where-Object { $_.Name -match '^\d+(\.\d+)+$' } |
            ForEach-Object { [version]$_.Name } |
            Sort-Object
    }
    $btOk = $btVersions | Where-Object { $_ -ge $requiredBuildTools }
    if ($btOk) {
        Write-Result -Status OK -Name 'Build Tools' -Detail (($btOk -join ', '))
    } else {
        $found = if ($btVersions) { $btVersions -join ', ' } else { '无' }
        Write-Result -Status FAIL -Name 'Build Tools' -Detail "需要 >= $requiredBuildTools（现有：$found）" -Hint "用 sdkmanager 安装：sdkmanager `"build-tools;$requiredBuildTools`""
    }

    # platform-tools / adb
    $adb = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path $adb) {
        $adbVersion = (& $adb version 2>&1 | Select-Object -First 1) -join ''
        Write-Result -Status OK -Name 'Platform Tools' -Detail $adbVersion
        $devices = @(& $adb devices 2>&1 | Select-String -Pattern "`tdevice")
        if ($devices.Count -gt 0) {
            Write-Result -Status OK -Name '连接设备' -Detail ("$($devices.Count) 台：`n" + (($devices | ForEach-Object { $_.Line }) -join "`n"))
        } else {
            Write-Result -Status WARN -Name '连接设备' -Detail '未检测到已授权设备' -Hint '手机开启「USB 调试」并允许本机授权；模拟器请先启动 AVD。'
        }
    } else {
        Write-Result -Status WARN -Name 'Platform Tools' -Detail '缺少 adb' -Hint "用 sdkmanager 安装：sdkmanager `"platform-tools`"（仅安装到设备时需要）"
    }
}

# ---------- 5. 签名 ----------
$keystore = Join-Path $projectRoot 'release.keystore'
if (Test-Path $keystore) {
    Write-Result -Status OK -Name '签名文件' -Detail 'release.keystore'
} else {
    Write-Result -Status WARN -Name '签名文件' -Detail 'release.keystore 缺失' -Hint 'release 构建会失败；可用 keytool 生成后更新 app/build.gradle.kts 中的签名配置。'
}

# ---------- 汇总 ----------
Write-Host ''
if ($script:failCount -eq 0) {
    Write-Host ("环境自检通过：0 项失败，{0} 项警告。" -f $script:warnCount) -ForegroundColor Green
    Write-Host '可以开始构建：pwsh -File .\scripts\build.ps1 -Variant debug' -ForegroundColor Green
} else {
    Write-Host ("环境自检未通过：{0} 项失败，{1} 项警告。" -f $script:failCount, $script:warnCount) -ForegroundColor Red
}
Write-Host ''

exit $script:failCount
