# 安装 iPad / 平板网页版：复制到 ComfyUI\custom_nodes\ComfyMobileWeb。首次安装后重启 ComfyUI，之后更新网页文件不需要重启。
# 访问 http://电脑地址:8188/comfymobile/ 。可选 -Token 设置局域网访问口令（只含字母、数字、- 和 _），-Token '' 关闭口令。
param([string]$ComfyUI = (Join-Path $PSScriptRoot '..\..\ComfyUI'), [string]$Token = $null)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$nodes = Join-Path (Resolve-Path $ComfyUI) 'custom_nodes'
if (-not (Test-Path $nodes)) { throw "找不到 custom_nodes 目录：$nodes" }
$target = Join-Path $nodes 'ComfyMobileWeb'
$static = Join-Path $target 'static'
New-Item -ItemType Directory -Force $static | Out-Null
Copy-Item -Force (Join-Path $root 'web\comfymobile_web\__init__.py') $target
Copy-Item -Force (Join-Path $root 'web\comfymobile_web\static\*') $static
# 与安卓版共用同一份编辑器适配脚本和双语词库
Copy-Item -Force (Join-Path $root 'app\src\main\assets\editor-adapter.js') $static
Copy-Item -Force (Join-Path $root 'app\src\main\assets\prompt-library.json') $static
# 让手机浏览器直接从电脑下载最新安卓安装包
$version = (Get-Content (Join-Path $root 'version.properties') | Where-Object { $_ -match '^name=' }) -replace '^name=', ''
$apk = Join-Path $root "output\ComfyMobile-$version.apk"
if (Test-Path $apk) { Copy-Item -Force $apk (Join-Path $static 'ComfyMobile.apk') } else { Write-Host "未找到 $apk ，网页版暂不提供安卓安装包下载" }
if ($PSBoundParameters.ContainsKey('Token')) {
    if ($Token -and $Token -notmatch '^[A-Za-z0-9_-]{6,64}$') { throw '口令只能包含字母、数字、- 和 _，长度 6–64' }
    $config = Join-Path $target 'config.json'
    [System.IO.File]::WriteAllText($config, (@{ token = $Token } | ConvertTo-Json), (New-Object System.Text.UTF8Encoding($false)))
    if ($Token) { Write-Host "已开启局域网访问口令。iPad 打开 http://电脑地址:8188/comfymobile/login?token=$Token 一次即可；本机不受影响。" } else { Write-Host '已关闭局域网访问口令' }
}
Write-Host "已安装到 $target"
Write-Host '首次安装需重启 ComfyUI。然后在 iPad Safari 打开 http://电脑局域网地址:8188/comfymobile/ ，用 分享 → 添加到主屏幕 即可全屏使用。'
