$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$toolRoot=Join-Path $root '.tools'
New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
function Fetch([string]$url,[string]$name,[string]$hash,[string]$algorithm) {
    $file=Join-Path $toolRoot $name
    if (!(Test-Path -LiteralPath $file)) {
        & curl.exe -L --fail --show-error --connect-timeout 30 --max-time 600 -o $file $url
        if ($LASTEXITCODE -ne 0) {throw "下载失败: $name"}
    }
    if ($hash -and (Get-FileHash -LiteralPath $file -Algorithm $algorithm).Hash -ne $hash) {throw "校验失败: $name；请将该压缩包移开后重试"}
    return $file
}
$jdkArchive=Fetch 'https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip' 'jdk.zip' '' 'SHA256'
$platform=Fetch 'https://dl.google.com/android/repository/platform-35_r02.zip' 'platform.zip' '0bb560a90a7a2cbd0dd8348224d518b638fe7949' 'SHA1'
$build=Fetch 'https://dl.google.com/android/repository/build-tools_r35_windows.zip' 'build-tools.zip' 'af059bb67cf7786f45ee0db85e2d24985df1b4b6' 'SHA1'
$adb=Fetch 'https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip' 'platform-tools.zip' 'e03e78b1d80b396f1c3358e31251cb31740e1110' 'SHA1'
foreach($item in @(@($jdkArchive,'jdk'),@($platform,'unpacked/platform'),@($build,'unpacked/build-tools'),@($adb,'unpacked/platform-tools'))){
    $destination=Join-Path $toolRoot $item[1]
    if(!(Test-Path -LiteralPath $destination)){Expand-Archive -LiteralPath $item[0] -DestinationPath $destination}
}
Write-Host '官方构建工具已准备好。运行 pwsh -File scripts/build.ps1 构建 APK。'
Write-Host '工具仅位于项目 .tools 目录，不修改系统 PATH。Android SDK 使用条款随压缩包提供。'
