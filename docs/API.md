# NaturalStreaming 前端接口对接

契约版本 1.0，iOS 优先，跨端接口一致；Android 后续包名 `com.naturalstreaming`。本项目后端采用独立账号，不能使用 SayTavi 账号。产品范围见 requirements-v1.0.md（内容修订 1.2）。

## 地址与凭证

| 服务 | 地址 | 调用身份 |
|---|---|---|
| App API | https://api.naturalstreaming.net | 测试账号登录所得 Bearer token |
| 后台 API | https://admin-api.naturalstreaming.net | 独立服务 token，按 scope 授权 |
| 媒体服务 | https://media.naturalstreaming.net | 下载用 App token；上传用 upload 服务 token |

所有 JSON 为 UTF-8；时间戳若为整数则是 Unix 秒。业务 ID 和版本是大小写敏感字符串。除 login、healthz 外都必须带 `Authorization: Bearer <token>`。设置明确的 User-Agent，例如 `NaturalStreaming/1.0 (Android)`，默认 Python urllib User-Agent 已实测被 Cloudflare 拒绝（403/1010），随附工具已设置应用身份。不要把服务凭证放入 APK、App 配置、Git 或日志。

私有接入文件由后端在本机 `.local/frontend-access.json` 交付；其中有 api/admin/media、uploadToken、testUsername、testPassword。设置 `NS_CREDENTIALS_FILE` 指向该文件。其他机器由项目负责人私下转交该文件；文档和仓库不含秘密值。发布凭证不交付前端上传端。

## 登录与会话

### POST /v1/auth/login

```json
{"username":"<testUsername>","password":"<testPassword>","deviceId":"android-installation-uuid"}
```

返回：

```json
{"token":"<app-token>","accountId":1,"deviceId":"android-installation-uuid","expiresIn":2592000,"checkIntervalSeconds":60}
```

deviceId 必须为 8～100 位字母、数字、下划线或短横线，安装时生成并持久保存，不使用硬件标识。每次成功登录签发新会话并撤销旧会话，包括同设备再次登录。多设备并发登录最终只保留一个有效设备。

### GET /v1/me

```json
{"account":{"id":1,"username":"ns-dev"},"deviceId":"android-installation-uuid","entitlements":[{"theme_id":"forest-in-the-morning","allowed":true}],"checkIntervalSeconds":60,"offlineExpiryEnforced":false}
```

启动、恢复联网和在线播放期间每 60 秒调用，包括锁屏与后台。`entitlements` 是完整授权列表，`allowed:false` 表示明确撤权，立即停止对应主题并禁止再打开；不要从目录消失推断撤权。

- 401 `session_revoked`：停止全部播放、清除在线会话，隐藏当前账号配置，提示其他设备登录或账号被撤销。
- 401 `session_expired`：普通到期，允许已有配置离线播放，在线功能需重新登录。
- 401 `login_required`：没有可识别凭证，提示登录；不能当作已明确被踢。
- 网络超时、5xx、429：保留当前离线播放，不当作撤权或踢出。
- 离线设备不能即时接收踢出；恢复联网后确认撤销才执行。

### POST /v1/auth/logout

带 App token，无需请求体，返回 `{"status":"logged_out"}`。本机退出即停止旧账号播放并隐藏配置；网络不可达时也应完成本机退出，不阻塞用户。

## 索引与主题

### GET /v1/catalog/sync

首次不带 cursor。后续 `?cursor=<URL编码后的上次cursor>`：

```json
{"mode":"snapshot","cursor":"opaque-signed-value","upserts":[{"id":"forest-in-the-morning","name":"森林的早晨","version":"1","cover":"media/small.jpg","description":"","supportedModes":["mix"],"tracks":[{"id":"1","label":"声源名","description":"简短说明"}]}],"removed":[]}
```

后续 mode 为 delta。upserts 覆盖对应主题，removed 为 `{"id":"...","reason":"unpublished"}` 或 `reason:"revoked"`。unpublished 只移出在线目录，保留已下载配置；revoked 停止播放并阻止再次打开。快照须替换目录，但不能据此删除本地文件或误撤销未列出的授权。

在本地同一事务应用全部变更和新 cursor。cursor 与账号绑定，切账号不可复用。409 `cursor_invalid` 时丢弃 cursor 重新快照。不得解码或构造 cursor。`GET /v1/themes` 为首次目录快照的别名。

封面是主题内相对路径，通过版本清单和同一下载流程获取，没有匿名公开图片 URL。

### GET /v1/themes/{id}/versions/{version}

需要账号主题授权。只返回曾发布的固定版本，包括普通下架后的旧版：

```json
{"themeId":"forest-in-the-morning","version":"1","state":"published","revision":1,"scene":{"id":"forest-in-the-morning","schema_version":1,"tracks":[],"presets":[]},"manifest":{"theme_id":"forest-in-the-morning","schema_version":1,"files":[]},"license":{"source":"...","license":"...","reviewed":true}}
```

示例数组仅展示结构；实际 scene/manifest 保留主题完整 JSON。`schema_version` 是格式，version 是内容版本，revision 是管理草稿修订号。发布后内容不可修改。不要把版本字符串当数字排序，当前版本以目录为准。

## 下载与本地缓存

### POST /v1/media/download-urls

```json
{"themeId":"forest-in-the-morning","version":"1","paths":["media/1a.wav","media/1b.wav"]}
```

一次 1～129 条包内文件路径，必须存在于固定版本清单。

```json
{"files":[{"path":"media/1a.wav","sha256":"64位小写哈希","bytes":2889078,"url":"https://media.naturalstreaming.net/v1/media/<sha>?ticket=<opaque>","expiresAt":1790000000,"requiredHeaders":["Authorization"]}]}
```

- ticket 有效期 300 秒，并绑定具体会话、主题、版本和文件。
- 下载 GET 和 HEAD 都必须带申请票据的同一个 App Bearer token。票据不能跨登录会话复用。
- 支持标准单段 Range，包括 `bytes=1024-`、`bytes=0-1023`、`bytes=-1024`。206 读取 Content-Range；416 检查本地偏移后重新准备。ETag 为文件 SHA-256。
- 普通完整 GET 返回 200。下载返回 403 `download_expired` 时重新申请地址；重登录后也必须重领地址。
- 每个下载/续传请求重新校验当前会话和主题授权。已开始的响应不承诺即时中断；不收回已下载字节。
- URL 含敏感票据，不记日志，不交播放器。音频不直接流播。

固定版本 → 枚举已选轨 A/B（含静音轨）→ 按哈希查缓存 → 下载缺项至临时文件 → 大小与 SHA-256 都通过 → 原子移入缓存 → 全部必需文件可读后激活。缓存键使用 sha256，不用 `media/1a.wav`，因为不同主题可同名。

下载任务同哈希去重；取消、失败、空间不足不破坏旧场景。整主题下载枚举全部 manifest.files；配置就绪与整主题就绪分别显示。新版本准备完成才替换，不自动更新正在播放的版本。

## 后台上传与草稿

下面的 `/v1/admin/...` 全部位于后台 API；`/v1/uploads/...` 与 `/v1/exports/...` 位于媒体服务。均带服务 Bearer token。

| scope | 能力 |
|---|---|
| upload | 上传、查媒体、草稿读写、校验、导出 |
| publish | 显式发布、下架和回滚 |
| accounts | 开通账号、重置或禁用、主题授权 |
| credentials | 创建和撤销服务凭证 |

前端 Codex 的凭证只有 upload，无权发布、开账号或给自己增加 scope。

### PUT media /v1/uploads/{sha256}

`Content-Type: application/octet-stream`，请求体为原始文件字节，单文件最大 128 MiB，同时最多两个解码校验任务；429 upload_busy 可退避重试。201 返回实际解析的 bytes、sha256、kind、codec、container、channels、sampleRate、duration、mime。同哈希可安全重试。

支持 AAC-LC M4A、PCM16 WAV、单帧 JPEG 和 PNG；音频 1～2 声道、采样率 8000～192000 Hz、最长 7200 秒，实际完整解码检查。只启用对应格式及本地文件协议，拒绝播放列表、视频伪装封面和外部网络协议。解码后按样本时间测量时长，容器与解码时长差异不得超过 0.15 秒。422 返回 hash_mismatch、media_decode_failed、unsupported_codec、duration_mismatch 等。不能仅改后缀绕过格式检查。`GET media /v1/uploads/{sha}` 返回已入库文件元数据。

### PUT /v1/admin/themes/{theme}/versions/{version}

```json
{"scene":{},"manifest":{},"license":{"source":"来源说明","license":"授权说明","reviewed":true}}
```

首次 201 返回 `themeId,version,revision:1,state:draft`。scene/manifest 传入完整原始结构。草稿允许暂缺文件、标签或授权，校验时报告。后续更新先 GET 同一路径，携带当前 `revision`，冲突返回 409 revision_conflict；每次更新 revision 加一并清空校验通过状态。已发布版本返回 409 version_immutable，需创建新内容版本。

ID/版本允许 1～80 位字母、数字、下划线和短横线。JSON 总长最大 500000 字符（网关另限 1 MiB）。轨道 1～32 条，每条正好两个 A/B 片段；文件最多 129 项。路径仅包内相对路径，禁止绝对路径、反斜线和 `..`。

### POST /v1/admin/themes/{theme}/versions/{version}/validate

无业务请求体，可发送 `{}`。校验完成返回 200：

```json
{"valid":false,"revision":2,"errors":[{"field":"tracks.label:1","code":"invalid_or_missing"}]}
```

不要把 HTTP 200 当作校验通过，必须检查 valid。字段问题与当前 revision 绑定；修改草稿后需要重验。校验要求来源授权已审查、轨道有名称及 description、文件和大小哈希一致、实际声道格式匹配、预设完整引用轨道、切换时间为 2 秒。每段时长必须大于 overlap，且 overlap 不超过每段时长一半，保证同一轨最多两个片段同时发声。清单若声明 sample_rate/sampleRate 或 duration/duration_seconds，必须匹配实际值（时长容差 0.15 秒）。占位名称“轨道 01”不能正式发布。

### 管理查询与导出

- GET `/v1/admin/themes`：所有草稿及发布主题的 id/current_version/listed。
- GET `/v1/admin/themes/{theme}/versions`：历史版本、状态、修订和校验信息。
- GET `/v1/admin/themes/{theme}/versions/{version}`：完整草稿或已发布文档。
- GET media `/v1/exports/{theme}/{version}`：完整 ZIP 导出；文件未齐备返回 409 export_not_ready。导出只读，不产生发布。

## 发布与账号管理 供后端或发布者使用

以下前端上传凭证无权调用：

- POST `/v1/admin/themes/{theme}/versions/{version}/publish`：必须当前 revision 校验通过。显式发布；重试不会创建重复版本，成功后会使该版本成为目录当前版本。
- POST `/v1/admin/themes/{theme}/unpublish`：目录下架，保留历史发布版本读取与有权账号的下载。
- POST `/v1/admin/themes/{theme}/rollback`，`{"version":"旧版"}`：只允许完整已发布版本。
- POST `/v1/admin/accounts`，`{"username":"ns-dev","password":"至少12位"}`：201 返回 id、username；用户名 3～80 位字母数字下划线短横线，密码 12～72 字符。
- GET `/v1/admin/accounts`：账号列表，无密码哈希。
- PUT `/v1/admin/accounts/{id}`，`{"enabled":false}` 或 `{"password":"新密码"}`：禁用或重置密码并撤销旧会话。
- PUT `/v1/admin/accounts/{id}/themes/{theme}`，`{"allowed":true}` 或 false：授权或撤权，进入同步事件并反映在 me。
- POST `/v1/admin/credentials`，`{"id":"uploader-2","scopes":["upload"]}`：201 只返回一次新 token。
- GET `/v1/admin/credentials`：无 token 的凭证状态列表。
- DELETE `/v1/admin/credentials/{id}`：撤销服务凭证，不能撤销自身。

## 错误约定

```json
{"error":{"code":"theme_forbidden","details":[]}}
```

details 可为列表、字符串或对象，客户端依赖 code 而非文本。400 为参数错误；401 认证错误需按具体 code 区分；403 为 scope/主题/票据错误；404 缺失；409 为状态或版本冲突；413 文件过大；422 媒体或内容非法；429 限流繁忙；5xx 允许重试。未知错误保留原播放，不盲目清理本地状态。无效 cursor 返回 409，不是会话撤销。

## 本地播放交接

详见 PLAYBACK.md。后端不执行混音、定时器或系统音频焦点。接口通过不能替代真机离线、A/B 接缝和锁屏续播验收。
