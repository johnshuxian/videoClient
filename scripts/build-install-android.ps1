param(
  [string]$AndroidSdk,
  [string]$DeviceId
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot

try {
  # 参数优先，默认使用项目根目录中的 SDK。
  if ($AndroidSdk) {
    $candidates = @($AndroidSdk)
  } else {
    $candidates = @(
      (Join-Path $project 'android-sdk'),
      $env:ANDROID_HOME,
      $env:ANDROID_SDK_ROOT,
      (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    )
  }
  $sdk = $candidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platforms\android-36\android.jar')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'build-tools\35.0.0\apksigner.bat'))
  } | Select-Object -First 1
  if (-not $sdk) {
    throw '找不到完整的 Android SDK。需要 Platform 36 和 Build Tools 35.0.0；请用 -AndroidSdk 指定目录。'
  }

  $npm = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
  if (-not $npm) { throw '找不到 npm.cmd。请先安装 Node.js。' }
  $keystore = if ($env:ANDROID_KEYSTORE_PATH) { $env:ANDROID_KEYSTORE_PATH } else { Join-Path $project 'videoclient-release.jks' }
  $passwordFile = Join-Path $project 'signing-password.txt'
  if (-not (Test-Path -LiteralPath $keystore)) { throw "缺少 Android release 密钥库：$keystore" }
  if (-not $env:ANDROID_KEYSTORE_PASSWORD -and
      (-not (Test-Path -LiteralPath $passwordFile) -or -not (Get-Content -LiteralPath $passwordFile -Raw).Trim())) {
    throw '缺少 Android release 签名密码。请设置 ANDROID_KEYSTORE_PASSWORD 或提供 signing-password.txt。'
  }

  $previousAndroidHome = $env:ANDROID_HOME
  $env:ANDROID_HOME = (Resolve-Path -LiteralPath $sdk).Path
  try {
    Push-Location $project
    try {
      if (-not (Test-Path -LiteralPath (Join-Path $project 'node_modules\.bin\vite.cmd')) -or
          -not (Test-Path -LiteralPath (Join-Path $project 'node_modules\.bin\cap.cmd'))) {
        Write-Host '[1/4] 安装网页依赖...'
        & $npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci 失败，退出码 $LASTEXITCODE" }
      }

      Write-Host '[2/4] 构建并同步 Android 网页资源...'
      & $npm run build:web
      if ($LASTEXITCODE -ne 0) { throw "网页资源构建失败，退出码 $LASTEXITCODE" }
      & (Join-Path $project 'node_modules\.bin\cap.cmd') sync android
      if ($LASTEXITCODE -ne 0) { throw "Android 资源同步失败，退出码 $LASTEXITCODE" }

      Write-Host '[3/4] 编译并校验 release APK...'
      Push-Location (Join-Path $project 'android')
      try {
        & .\gradlew.bat assembleRelease
        if ($LASTEXITCODE -ne 0) { throw "APK 编译失败，退出码 $LASTEXITCODE" }
      } finally {
        Pop-Location
      }

      $apk = Join-Path $project 'android\app\build\outputs\apk\release\app-release.apk'
      if (-not (Test-Path -LiteralPath $apk)) { throw "未找到 release APK：$apk" }
      $apksigner = Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat'
      $signature = @(& $apksigner verify --print-certs $apk 2>&1)
      if ($LASTEXITCODE -ne 0 -or ($signature -join "`n") -notmatch '(?i)7f983ef32a1b69a84ed194797813e188c466ddbccee1a743890c6f36b558fed1') {
        throw 'APK 签名与项目 release 证书不一致，已停止安装。'
      }
      Write-Host "已生成并核验签名：$apk"

      $adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
      if (-not (Test-Path -LiteralPath $adb)) {
        $adb = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
      }
      if (-not $adb) { throw '找不到 adb.exe。请安装 Android SDK Platform Tools。' }

      Write-Host '[4/4] 查找 Android 设备并安装...'
      $devices = @(& $adb devices -l | ForEach-Object {
        if ($_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
      })
      if ($devices.Count -eq 0) {
        throw '没有已授权的 Android 设备。请解锁手机、确认 USB 调试授权后重试。APK 已编译完成。'
      }
      if ($DeviceId) {
        if ($DeviceId -notin $devices) { throw "指定设备未连接：$DeviceId；可用设备：$($devices -join ', ')" }
      } elseif ($devices.Count -eq 1) {
        $DeviceId = $devices[0]
      } else {
        throw "检测到多台设备，请用 -DeviceId 指定目标：$($devices -join ', ')"
      }

      # 仅覆盖安装；签名不一致时 adb 会失败，设备上的现有应用数据仍保留。
      $installOutput = @(& $adb -s $DeviceId install -r $apk 2>&1)
      $installOutput | ForEach-Object { Write-Host $_ }
      if ($LASTEXITCODE -ne 0 -or ($installOutput -join "`n") -notmatch '(?m)^Success\s*$') {
        throw "安装失败，设备：$DeviceId"
      }
      Write-Host "完成：已安装到 $DeviceId"
    } finally {
      Pop-Location
    }
  } finally {
    $env:ANDROID_HOME = $previousAndroidHome
  }
} catch {
  Write-Host "错误：$($_.Exception.Message)" -ForegroundColor Red
  exit 1
}
