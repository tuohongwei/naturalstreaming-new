# NaturalStreaming 前端 Codex 交接

首版现为 iOS 优先；Android 后续包名及 namespace 均为 `com.naturalstreaming`。只做手动混音、现有预设、下载与离线播放，地图/电台/AI/语音注册后续开发。

## 先读

1. `docs/requirements-v1.0.md`：完整已确认需求，文件名固定，内容修订 1.2。
2. `docs/API.md`：已部署地址、全部接口、请求响应、权限和错误处理。
3. `docs/PLAYBACK.md`：A/B 直接叠加、2秒预设过渡、动态增益、输出保护、定时和生命周期。

后端仓库为 https://github.com/tuohongwei/naturalstreaming-new ，前端仓库为 https://github.com/taviking727-code/NaturalStreaming-App ，分别使用对应 GitHub 身份。前端不要直接访问数据库或部署服务器，使用公网接口。

## 可直接连接的服务

- App API：https://api.naturalstreaming.net
- 后台 API：https://admin-api.naturalstreaming.net
- 文件服务：https://media.naturalstreaming.net

私有接入文件位于本机 `C:/Users/Administrator/Desktop/projects/NaturalStreaming/.local/frontend-access.json`。同机 Codex 可直接读取并放入进程内存；异机由负责人私下传递。设置 `NS_CREDENTIALS_FILE` 为这个文件，不把文件复制进仓库或 App。

服务凭证仅有 upload，可以直接上传真实音乐内容、JSON、编辑草稿和校验；无 publish/accounts/credentials 权限。测试账号用于 App 登录及授权下载。用户密码和 uploadToken 是不同用途，不互相替代。

## 开始联调

Python 3.9+，只依赖标准库。在前端仓库运行：

```powershell
$python='C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$env:NS_CREDENTIALS_FILE='C:/Users/Administrator/Desktop/projects/NaturalStreaming/.local/frontend-access.json'
& $python tools/frontend_smoke.py
& $python tools/frontend_smoke.py
```

以上解释器路径适用于当前 Codex 工作机，其他机器改用已安装的 Python 3.9+。

第一次下载所选片段；第二次同哈希文件新增下载数为 0。工具会替换测试账号当前设备会话，真机测试期间不要运行。工具验证的是接口、下载和本地文件准备，不代表已实现 Android 播放。

```powershell
& $python tools/upload_theme.py '<主题目录或包含多个主题的ZIP>' --version 2 --validate
```

不带 license-file 时保持未审核状态，可上传草稿但不能发布。经内容负责人确认后可用 `--license-file <私有或非敏感授权JSON>`，内容为 source、license、reviewed。草稿更新自动先获取 revision，已发布版本不能覆盖，需换版本。

## 当前素材状态

- “森林的早晨”和“柔软的钢琴”：全部媒体已上传并实际解码通过，版本 1 为草稿。仍需逐轨真实名称/描述及来源授权审核。
- “联调测试音（非音乐）”：已发布的十轨合成信号，可立即用于下载和混音接线；五预设，低音量测试，不作为自然声音内容验收。
- 测试账号仅获诊断主题和首发森林主题授权；后者发布后出现在目录。

## 易错点

- 下载也必须带申请票据时的同一个 App Bearer token，地址不是匿名链接；重登录后旧票据不可复用。
- 单设备互斥，session_revoked 必须退出；session_expired、超时、5xx 不能误踢离线用户。
- 主题下架与账号主题撤权不同，以 removed.reason 和 me.entitlements 判断。
- cache key 用 SHA-256，不用主题内文件名。选轨包含完整 A/B；静音已选轨也准备文件。
- 预设2秒过渡不重启音频；A/B重叠直接相加，不做交叉淡化。
- 新配置准备成功后才切换；播放器只读本地文件。
- App 重开恢复配置但不自动播放；来电等中断结束也不自动恢复。
- upload 凭证只能在开发工具使用，严禁打包进 App。


2026-11-18 更新：用户已批准 iOS 优先；测试账号新增 coastal-home、tender-piano 授权。平台接入说明见前端仓库 docs/BACKEND-IOS-AUTHORIZATION-2026-11-18.md，历史 Android 首版描述以此更新为准。
