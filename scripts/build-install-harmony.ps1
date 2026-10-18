param(
  [string]$DevEcoHome,
  [string]$DeviceId
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot

try {
  # 显式指定的路径优先；本机默认安装位置可直接双击使用。
  if ($DevEcoHome) {
    $candidates = @($DevEcoHome)
  } elseif ($env:DEVECO_HOME) {
    $candidates = @($env:DEVECO_HOME)
  } else {
    $candidates = @(
      'E:\mysoft\DevEco Studio',
      (Join-Path $env:ProgramFiles 'Huawei\DevEco Studio'),
      (Join-Path $env:ProgramFiles 'DevEco Studio'),
      (Join-Path $env:LOCALAPPDATA 'Programs\DevEco Studio')
    )
  }
  $studio = $candidates | Where-Object {
    Test-Path -LiteralPath (Join-Path $_ 'tools\hvigor\bin\hvigorw.js')
  } | Select-Object -First 1
  if (-not $studio) {
    throw '找不到 DevEco Studio。请用 -DevEcoHome 指定安装目录，或设置 DEVECO_HOME。'
  }

  $npm = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
  if (-not $npm) { throw '找不到 npm.cmd。请先安装 Node.js 22 或更新版本。' }
  $signing = Join-Path $project 'harmony\build-profile.json5'
  if (-not (Test-Path -LiteralPath $signing)) {
    throw '缺少鸿蒙签名配置。请先在 DevEco Studio 的 Signing Configs 中配置当前真机签名。'
  }

  $previousDevEcoHome = $env:DEVECO_HOME
  $env:DEVECO_HOME = $studio
  try {
    Push-Location $project
    try {
      if (-not (Test-Path -LiteralPath (Join-Path $project 'node_modules\.bin\vite.cmd'))) {
        Write-Host '[1/4] 安装网页依赖...'
        & $npm ci
        if ($LASTEXITCODE -ne 0) { throw "npm ci 失败，退出码 $LASTEXITCODE" }
      }

      Write-Host '[2/4] 构建并同步鸿蒙网页资源...'
      & $npm run sync:harmony
      if ($LASTEXITCODE -ne 0) { throw "网页资源构建失败，退出码 $LASTEXITCODE" }

      Write-Host '[3/4] 编译并签名 HAP...'
      & (Join-Path $PSScriptRoot 'build-harmony.ps1') -BuildMode release
      if ($LASTEXITCODE -ne 0) { throw "HAP 编译失败，退出码 $LASTEXITCODE" }

      $hap = Join-Path $project 'harmony\entry\build\default\outputs\default\entry-default-signed.hap'
      if (-not (Test-Path -LiteralPath $hap)) { throw "未找到签名 HAP：$hap" }
      Write-Host "已生成：$hap"

      $hdc = Join-Path $studio 'sdk\default\openharmony\toolchains\hdc.exe'
      if (-not (Test-Path -LiteralPath $hdc)) {
        throw "找不到 hdc：$hdc"
      }
      function Get-ConnectedDevices {
        & $hdc list targets -v | ForEach-Object {
          $parts = ($_.ToString().Trim() -split '\s+')
          if ($parts.Count -ge 3 -and $parts[1] -ne 'UART' -and $parts[2] -eq 'Connected') {
            $parts[0]
          }
        }
      }

      Write-Host '[4/4] 查找鸿蒙设备并安装...'
      $devices = @(Get-ConnectedDevices)
      if ($devices.Count -eq 0) {
        # USB 偶尔显示 Offline；重启本机 hdc 服务后再查一次。
        & $hdc start -r | Out-Null
        $devices = @(Get-ConnectedDevices)
      }
      if ($devices.Count -eq 0) {
        throw '没有已连接的鸿蒙设备。请解锁手机、确认 USB 调试授权后重试。HAP 已编译完成。'
      }
      if ($DeviceId) {
        if ($DeviceId -notin $devices) { throw "指定设备未连接：$DeviceId；可用设备：$($devices -join ', ')" }
      } elseif ($devices.Count -eq 1) {
        $DeviceId = $devices[0]
      } else {
        throw "检测到多台设备，请用 -DeviceId 指定目标：$($devices -join ', ')"
      }

      $installOutput = @(& $hdc -t $DeviceId install -r $hap 2>&1)
      $installOutput | ForEach-Object { Write-Host $_ }
      if ($LASTEXITCODE -ne 0 -or ($installOutput -join "`n") -notmatch 'install bundle successfully') {
        throw "安装失败，设备：$DeviceId"
      }
      Write-Host "完成：已安装到 $DeviceId"
    } finally {
      Pop-Location
    }
  } finally {
    $env:DEVECO_HOME = $previousDevEcoHome
  }
} catch {
  Write-Host "错误：$($_.Exception.Message)" -ForegroundColor Red
  exit 1
}
