param([ValidateSet('debug', 'release')][string]$BuildMode = 'release')

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$studio = $env:DEVECO_HOME
if (-not $studio) { throw '请设置 DEVECO_HOME 为 DevEco Studio 安装目录' }
$node = Join-Path $studio 'tools\node\node.exe'
$hvigor = Join-Path $studio 'tools\hvigor\bin\hvigorw.js'
$sdk = Join-Path $studio 'sdk'
foreach ($item in @($node, $hvigor, $sdk)) {
  if (-not (Test-Path -LiteralPath $item)) { throw "DevEco Studio 文件不存在：$item" }
}

$preload = Join-Path $PSScriptRoot 'harmony\force-sdk-resource.cjs'
$loader = Join-Path $PSScriptRoot 'harmony\force-deveco-commonjs.mjs'
$loaderUri = [Uri]::new((Resolve-Path -LiteralPath $loader).Path).AbsoluteUri
$preloadPath = $preload.Replace('\', '/')
$previousNodeOptions = $env:NODE_OPTIONS
$previousSdkHome = $env:DEVECO_SDK_HOME
try {
  # 当前 DevEco 安装盘的上级 package.json 将 .js 误判为 ESM；仅在本次构建进程修正加载方式。
  $env:NODE_OPTIONS = "--require=`"$preloadPath`" --experimental-loader=$loaderUri"
  $env:DEVECO_SDK_HOME = $sdk
  Push-Location (Join-Path $project 'harmony')
  try {
    & $node $hvigor --mode module -p "buildMode=$BuildMode" assembleHap --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Hvigor 构建失败，退出码 $LASTEXITCODE" }
  } finally {
    Pop-Location
  }
} finally {
  $env:NODE_OPTIONS = $previousNodeOptions
  $env:DEVECO_SDK_HOME = $previousSdkHome
}
