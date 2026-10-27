param([int]$Port=8188,[switch]$CheckOnly,[string[]]$ExtraArgs=@())
$ErrorActionPreference='Stop'
if($Port -lt 1 -or $Port -gt 65535){throw '端口必须在 1 到 65535 之间'}
$project=Split-Path $PSScriptRoot -Parent
$bundle=Split-Path $project -Parent
$comfy=Join-Path $bundle 'ComfyUI'
$python=Join-Path $bundle 'python/python.exe'
$listeners=@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
$processes=@(Get-CimInstance Win32_Process | Where-Object {$_.Name -match '^python(w)?\.exe$' -and $_.CommandLine -match 'main\.py' -and ($_.ExecutablePath -eq $python -or $_.CommandLine -match [regex]::Escape($comfy))})
$addresses=@(Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' -and $_.InterfaceAlias -notmatch 'Tailscale|Mihomo|Loopback|vEthernet'})
foreach($address in $addresses){Write-Host "手机连接地址: http://$($address.IPAddress):$Port （$($address.InterfaceAlias)）"}
if($listeners.Count -gt 0){
    $listeners | Select-Object LocalAddress,LocalPort,OwningProcess | Format-Table
    Write-Host '端口已有监听，不启动第二个实例。若监听地址仅为 127.0.0.1，请在绘世启动器加入 --listen 0.0.0.0 后重启。'
    exit 0
}
if($processes.Count -gt 0){Write-Host '检测到 ComfyUI 进程，可能正在启动或使用其他端口；请查看绘世启动器，不启动第二个实例。';exit 0}
if($CheckOnly){Write-Host '未检测到该端口监听。推荐在绘世启动器添加 --listen 0.0.0.0 --port 8188 后启动。';exit 0}
if(!(Test-Path -LiteralPath $python) -or !(Test-Path -LiteralPath (Join-Path $comfy 'main.py'))){throw '请将 ComfyMobile 放在整合包根目录，与 ComfyUI、python 目录并列'}
Write-Host '正在启动本机 ComfyUI。请保持此终端运行；关闭时按 Ctrl+C。若平时使用绘世自定义参数，推荐仍从绘世启动器启动。'
Push-Location -LiteralPath $comfy
try{& $python -s main.py --listen 0.0.0.0 --port $Port @ExtraArgs}finally{Pop-Location}
