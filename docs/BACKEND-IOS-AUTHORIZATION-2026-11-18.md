# iOS 优先与测试账号主题授权结果

2026 年 11 月 18 日。项目负责人已明确同意优先开发 iOS；此前“首版只做 Android”的平台决策已被替代。手动混音、主题下载、本地播放、账号和权限等功能范围不变，Android 包名仍保留为 `com.naturalstreaming`。

## 授权已完成

已使用后台账号管理身份，为私有 frontend-access.json 中原有测试账号增加 coastal-home 和 tender-piano 的 allowed:true；保留 forest-in-the-morning 及 integration-tone-demo 授权。已用独立数据库只读查询核对四项均为 true。本次没有重新登录测试账号，没有替换其当前会话或修改密码。

| 主题 | 测试账号授权 | 版本与状态 | 修订号 |
|---|---|---|---|
| coastal-home | 已开通 | `1` / draft | 1 |
| forest-in-the-morning | 保留 | `1` / draft | 3 |
| tender-piano | 已开通 | `1` / draft | 3 |
| integration-tone-demo | 保留 | `1` / published | 1 |

授权不等于发布。三个真实主题仍缺授权审核资料，目前不在 App 已发布目录中；App 查询其版本应由原先可能的 403 theme_forbidden 转为 404 version_missing（有授权但无已发布版本）。这属于预期状态，不要把 404 当成主题撤权。已有会话可刷新 `/v1/me` 看到完整授权，无需为了刷新授权重新登录。

仍由负责人或内容权利负责人交付真实 license JSON，并确认逐轨 label/description；上传者更新和校验草稿后，由发布者显式发布。此次未发布任何真实主题，也未修改草稿内容。iOS 可立即用诊断版本 `integration-tone-demo/1` 开发。

## iOS 访问后端

- 同样使用 App API `https://api.naturalstreaming.net`、后台 API `https://admin-api.naturalstreaming.net`、媒体服务 `https://media.naturalstreaming.net`，不另设 iOS 服务或数据库。
- 登录请求仍为 username/password/deviceId，安装级 deviceId 使用持久化 UUID（8～100 位字母数字、短横线或下划线），不要把 Android 示例前缀当成服务端要求；无需发送硬件标识。
- 后端不要求 iOS 专属签名、客户端证书、App Secret、OAuth 回调或客户端平台字段。iOS Bundle ID、Apple 签名团队和最低系统版本由前端实际工程配置，不能把 Android applicationId 误认为已注册的 Apple 标识。
- 全部 URL 已提供 HTTPS，无需为接入这些接口放宽 ATS。建议 User-Agent 为 `NaturalStreaming/1.0 (iOS)`。
- App token 与账号状态按 iOS 安全存储和账号隔离处理；开发用 uploadToken 仍只能在 Mac 上传工具中使用，不打入 iOS 包。
- Mac 私有文件位置沿用 `~/.config/naturalstreaming/frontend-access.json`，收到私有文件后配置 NS_CREDENTIALS_FILE；账号和凭证值不在 Git 中重复。

## 下载与缓存

下载请求使用带 Authorization 的 URLRequest，再创建 URLSession 下载任务；不能把返回 URL 直接交给只接受匿名 URL 的播放器。GET、HEAD、Range 都必须带申请票据时的同一个 App Bearer token。

票据 300 秒有效，后台队列延迟或重登录后可能失效。首版可先采用 App 内可见的下载准备流程；若启用 background URLSession，需要正确处理进程重建、临时文件移动、队列等待与过期重领。不能假定旧 resumeData 能自动改用新票据；必要时以已确认的本地偏移和新票据发起 Range 请求，核对 Content-Range/ETag。download_expired 不等于主题撤权。

Apple 的下载任务支持文件下载及后台会话，续传依赖服务器 Range 和验证标识。本后端已验证 Range、HEAD、ETag，但不承诺系统队列在 300 秒内启动：[URLSessionDownloadTask](https://developer.apple.com/documentation/foundation/urlsessiondownloadtask)、[续传要求](https://developer.apple.com/documentation/foundation/urlsession/downloadtask%28withresumedata%3Acompletionhandler%3A%29)。

按 SHA-256 复用缓存，校验大小和哈希后原子移入 App 沙盒缓存。配置保存具体主题版本和本地文件引用，不保存过期票据。不要在 Keychain 中存媒体文件。系统回收缓存后将配置标为需重新准备，不假设缓存永久存在。

诊断首轨 A/B 同哈希：固定版本 1、空缓存首次新增一个唯一文件，第二次为零，selectedClips 两次均为 2。按主题 ID 选择，不依赖目录第一项。

## 本地音频与系统行为

- 建议以 AVAudioEngine/AVAudioPlayerNode 等原生音频能力实现独立轨道和精确排程；不要依赖网络播放或后台普通 UI Timer 驱动 A/B 接缝。排程 API 参考 [AVAudioPlayerNode](https://developer.apple.com/documentation/avfaudio/avaudioplayernode/schedulebuffer%28_%3Aattime%3Aoptions%3Acompletioncallbacktype%3Acompletionhandler%3A%29-81qje)。
- AVAudioSession 配置为播放用途，启用相应后台音频模式，才能在锁屏和切后台时持续实际音频播放。参考 [Apple 播放配置](https://developer.apple.com/documentation/avfoundation/configuring-your-app-for-media-playback)、[playback category](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playback)。
- 锁屏控制使用系统媒体信息与远程播放控制；来电/音频中断和耳机断开时暂停，不能自动外放，中断结束仍按产品规则由用户手动恢复。
- 继续遵守 PLAYBACK.md：A/B 重叠直接求和、每段重叠不超过半片长、预设 2 秒过渡不重置音频、静音独立于增益，及最终输出保护。M4A 的 AAC 编码填充、重采样和实际接缝必须在 iPhone 真机验证；不能仅以文件哈希或模拟器通过代替。
- 播放期间以 60 秒为目标进行会话/授权检查，启动和恢复联网时也检查。iOS 系统挂起、网络不可达时不能保证精确后台轮询；不为权限轮询添加无声音频保活。停止或暂停后恢复前台时重新检查，离线和系统调度延迟不当作已确认撤权。
- 定时停止使用单调时间计算剩余时间，实际音频播放期间落实停止；暂停不冻结倒计时，恢复执行时发现已到期不得重新发声。
- 首版只播放本地文件，不请求麦克风权限，不加入语音注册或 AI 控制流程。

请以更新后的需求 v1.2、API.md、PLAYBACK.md 和本回复继续 iOS 开发。Apple 平台构建、签名、真机音质及后台行为由前端验收；后端本次没有改动业务代码或重启服务。
