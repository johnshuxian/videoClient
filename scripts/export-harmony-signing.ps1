param([Parameter(Mandatory = $true)][string]$StoreFile)

$ErrorActionPreference = 'Stop'
$store = (Resolve-Path -LiteralPath $StoreFile).Path
$signingHome = Split-Path -Parent $store
$certificate = [IO.Path]::ChangeExtension($store, '.cer')
$provisioning = [IO.Path]::ChangeExtension($store, '.p7b')
$material = Join-Path $signingHome 'material'
$localProfile = Join-Path (Split-Path -Parent $PSScriptRoot) 'harmony\build-profile.json5'
foreach ($item in @($certificate, $provisioning, $material, $localProfile)) {
  if (-not (Test-Path -LiteralPath $item)) { throw "缺少签名文件：$item" }
}

$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$work = Join-Path $tempRoot ("videoclient-harmony-" + [Guid]::NewGuid().ToString('N'))
$stage = Join-Path $work 'bundle'
$archive = Join-Path $work 'signing.zip'
try {
  New-Item -ItemType Directory -Path $stage -Force | Out-Null
  Copy-Item -LiteralPath $store -Destination (Join-Path $stage 'signing.p12')
  Copy-Item -LiteralPath $certificate -Destination (Join-Path $stage 'signing.cer')
  Copy-Item -LiteralPath $provisioning -Destination (Join-Path $stage 'signing.p7b')
  Copy-Item -LiteralPath $localProfile -Destination (Join-Path $stage 'build-profile.json5')
  Copy-Item -LiteralPath $material -Destination (Join-Path $stage 'material') -Recurse
  Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $archive
  [Convert]::ToBase64String([IO.File]::ReadAllBytes($archive)) | Set-Clipboard
  Write-Output '签名包 Base64 已复制到剪贴板，请粘贴为 GitHub Secret HARMONY_SIGNING_BUNDLE_BASE64。'
} finally {
  $resolvedWork = [IO.Path]::GetFullPath($work)
  if (-not $resolvedWork.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw '临时签名目录不在系统临时目录内'
  }
  Remove-Item -LiteralPath $resolvedWork -Recurse -Force -ErrorAction SilentlyContinue
}
