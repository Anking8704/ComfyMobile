# ComfyMobile 0.1.0

连接家中电脑 ComfyUI 的安卓客户端。电脑运行现有模型和节点，手机编辑工作流、输入提示词、查看任务和保存原图。Android 10 或以上，需要更新的系统 Android WebView。

这是可安装的首个本地开发版本，已构建并通过签名和主机端逻辑验证；尚未在安卓真机或模拟器完成端到端测试。设计参考图不是 APP 实测截图。第三方节点的特殊前端界面需要实际工作流验证，不能保证所有插件都已适配触屏。

## 安装与连接

1. 把 `output/ComfyMobile-0.1.0.apk` 传到手机，点开安装。系统要求时允许本次使用的文件管理器安装应用。
2. 手机和电脑连接同一个 Wi-Fi，电脑保持开机。
3. 在绘世启动器加入启动参数 `--listen 0.0.0.0 --port 8188`，然后正常启动 ComfyUI。保留你原来的显卡、性能等参数。
4. 用手机浏览器先打开 `http://电脑局域网IP:8188`。能看到 ComfyUI 后，在 APP 的“连接设置”填相同地址并点“检测并连接”。APP 会记住地址。

本次检测到的电脑 Wi-Fi 地址是 `192.0.2.10`，因此当前可用地址为 `http://192.0.2.10:8188`，以电脑实际监听端口为准。该地址可能在重新联网后改变。

双击 `检查局域网连接.cmd` 会检查本机状态并显示连接地址，不会启动第二个 ComfyUI 实例。推荐从绘世启动器启动；也可用下面的脚本启动整合包中的 Python，关闭该终端会结束该次运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-comfy-lan.ps1
```

也可以双击 `启动ComfyUI局域网.cmd` 执行上述启动脚本。

打不开时，确认没有连接访客 Wi-Fi、路由器未隔离设备，且 Windows 防火墙允许家庭网络访问对应 Python/ComfyUI。APP 不自动修改防火墙或路由器。

## 使用

- **工作流**：加载电脑提供的原版完整编辑器。“节点”打开列表，可定位、修改参数、复制、删除，或选择输出端口和目标输入端口连线。“添加”搜索电脑已安装的节点。“适应”调整画布视野。
- **提示词**：选择实际的节点文字字段，用系统输入法在原生全屏文本框中编辑。“保存到节点”写回该字段；回车只换行，不会生成。正向、反向或其他文本由用户选择实际字段，不自动猜测。
- **常用词 / 模板 / 历史**：常用词插入光标位置，模板和历史可替换草稿；按“分类 / 内容”保存以分组，长按条目可编辑或删除。选中文字后用“权重 + / −”调整常见括号权重。该工具不会自动处理其他模型专用语法。
- **任务**：查看运行中、等待和最近完成的记录。断线或重新打开后从电脑恢复记录，不自动重新提交。
- **图库**：自动刷新前台生成结果，查看图片，点“保存原图”写入相册 `Pictures/ComfyMobile`；“分享”会先保存原图再打开系统分享。
- **更多**：导入或导出工作流 JSON，撤销，查看最近一次实时预览。预览需要电脑启用预览且前端实际收到对应事件。图片上传和插件专用功能继续使用完整编辑器中的控件。

草稿、常用词和模板只存在这台手机。工作流需使用编辑器的保存功能，或在“更多”导出 JSON；手机草稿不等于工作流保存。切换工作流、重新加载页面或删除目标节点后，需重新选择提示词字段。

## 范围与当前限制

- 完整画布由系统 WebView 加载电脑现有前端。标准节点及自定义节点仍在电脑执行；手机端不安装模型，也不提供新的推理引擎。
- 原生参数面板处理标准文字、数值、开关和选择控件。特殊姿态编辑、蒙版、3D 或自定义绘图控件使用插件原界面，触屏兼容性尚待真机验证。
- 前台每 4 秒刷新任务与图库。应用退出、锁屏或被系统回收后，不保证后台收图或常驻通知。
- 图库显示最近 30 个任务中最多 40 张图片。原图相册保存单张上限 150 MiB，大于此值可在完整编辑器下载。图片预览为缩放显示，保存原图不会重新编码。
- 服务器地址支持根路径 HTTP(S)，不支持带路径前缀的反向代理。首版以家庭局域网、无额外认证的本机 ComfyUI 为目标。
- 本地开发签名保存在 `.tools/comfymobile-dev.keystore`。请保留该文件，后续升级需要同一签名。它不是商店发布签名。
- 当前任务页在历史记录不变时，运行数量会更新，但具体运行条目有时需要手动点“刷新”。详见 `docs/review.md`。

## 构建与测试（Windows）

不依赖 Gradle、Android Studio 或第三方安卓运行库。首次准备工具需要联网，下载 Microsoft OpenJDK 与官方 Android SDK；工具只放在项目 `.tools`，不会改系统 PATH。建议至少预留 2 GB 空间。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-tools.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build.ps1
```

脚本使用 Node.js 执行 JavaScript 适配测试，需先安装 Node.js。开发电脑本次使用 Node 24，JDK 21，Android SDK 35，Build Tools 35.0.0。

只运行自动测试：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build.ps1 -TestOnly
```

输出为 `output/ComfyMobile-0.1.0.apk`。测试和构建记录见 `docs/verification.md`，设计和实施计划见 `docs/superpowers/`。

## 技术资料

- [Android WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview)
- [Android 图片共享存储](https://developer.android.com/training/data-storage/shared/media)
- [官方 SDK 工具下载](https://developer.android.com/studio)
- [Microsoft OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download)
