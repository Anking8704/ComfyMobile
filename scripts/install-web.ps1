# 安装 iPad / 平板网页版：复制到 ComfyUI\custom_nodes\ComfyMobileWeb，重启 ComfyUI 后打开 http://电脑地址:8188/comfymobile/
param([string]$ComfyUI = (Join-Path $PSScriptRoot '..\..\ComfyUI'))
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
Write-Host "已安装到 $target"
Write-Host '重启 ComfyUI 后，在 iPad Safari 打开 http://电脑局域网地址:8188/comfymobile/ ，再用 分享 → 添加到主屏幕 即可全屏使用。'
