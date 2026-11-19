# NaturalStreaming 开发服务运维

## 隔离边界

开发服务器 `13.214.63.156`，SSH 用户 ec2-user。密钥由本机已有私有文件提供，不提交 Git。

| 资源 | NaturalStreaming 专用位置 |
|---|---|
| 服务与发布 | /srv/naturalstreaming，current 指向 releases 内版本 |
| 配置与凭据 | /etc/naturalstreaming，部署状态及各角色 env，仅 root 可读 |
| 数据库 | naturalstreaming |
| 数据库身份 | ns_api、ns_admin、ns_files、ns_migrate |
| 服务及端口 | naturalstreaming-api 8190、admin 8191、files 8192，仅监听回环 |
| 文件 | systemd StateDirectory naturalstreaming-files，/var/lib/naturalstreaming-files |
| 媒体工具 | /srv/naturalstreaming/tools/ffmpeg、ffprobe，来源及哈希见 source.json |
| Nginx | /etc/nginx/conf.d/naturalstreaming.conf |
| HTTPS | /etc/letsencrypt-naturalstreaming，六个规划域名 |
| 续期 | naturalstreaming-cert-renew.timer/service，独立 certbot 配置目录 |

`ops/server.py provision` 仅用于首次建立，遇已有项目目录、schema、账号或占用端口即拒绝。不重复初始化，不修改 SayTavi、AiChef、CRM 或其他数据库与服务。不改用户主目录、SSH、祖先目录或无关 ACL。

provision 保存无关 Nginx 配置哈希和现有应用 MainPID 到 baseline.json；后续每次 deploy 保存新的 per-release 基线，`server.py verify <该基线路径>` 独立核对，首次记录保留。若其他项目在此后自行升级，先核实变更归属，不能为了让验证通过静默覆盖基线。

## 构建与升级

Windows 当前环境运行 `./ops/build.ps1`。跨环境使用 JDK26 与 Maven：`mvn -f backend/pom.xml clean verify`。

```powershell
python ops/deploy.py --key <私有SSH密钥路径> --host ec2-user@13.214.63.156
```

脚本仅上传四个 JAR，校验 SHA-256，创建新版本目录并原子切换 current，运行 Flyway 及三个项目服务健康检查。旧版本和前一链接保存在 releases 内；启动失败恢复前一链接。数据库迁移不自动回滚，后续破坏性迁移必须单独设计恢复方案。初始 V001 只作用本项目新 schema。

每次部署使用新建 0700 私有暂存目录，部署辅助脚本位于各自暂存目录；安装、current 切换、启动及回滚全过程持有本项目 deploy.lock，不共用可互相覆盖的临时脚本。V002 固定服务凭证 ID 的大小写敏感比较，避免身份比较不一致。

首次部署步骤为 server.py provision → 传 JAR 并建立 current → server.py start → server.py http → 独立证书签发 → server.py https → server.py verify。脚本重跑 start 只检查/更新本项目权限与服务。

同一时间不要重启服务和上传内容。升级期间项目接口可能短暂 502/503，客户端保留旧播放并重试；本次不承诺本项目零停机发布。无关项目不重启。

## 健康与回归

各 api/admin-api/media 域名 GET /healthz 返回 service、version、status。仅健康状态公开。App 业务和文件需认证，服务上传需 upload scope。

```powershell
$env:NS_CREDENTIALS_FILE='<私有owner-access.json>'
python ops/test_e2e.py
```

测试会创建 regression-* 合成主题和 test-* 测试账号，覆盖真实公网、MySQL、媒体上传/解码、权限、发布、单设备和下载撤销。它不会用测试信号冒充真实音乐，也不修改首发音乐草稿。测试账号最后禁用，数据保留用于复核；清理需按明确 ID 单独执行，不全库删除。

前端仅有 frontend-access.json，运行 `tools/frontend_smoke.py` 或后端 `ops/frontend_smoke.py`；该工具会登录测试账号并替换它的旧设备会话，不要与真机联调同时运行。

## 内容状态与发布

两个用户提供主题已完整导入版本 1 草稿，未声明授权 reviewed，也保留原始占位轨名。补齐真实试听名称和 description，确认来源授权后，PUT 当前 revision、重新 validate，由发布者显式 publish。不要为了通过校验编造声源身份或授权。

`integration-tone-demo` 是 CC0 合成信号，十轨和五预设，仅供下载缓存与播放器接线，不能作为真实环境声听感验收。测试账号获此诊断主题及首个森林主题授权，森林正式发布后即可出现在同一目录。

## 凭证交接

本机 `.local/frontend-access.json` 包含前端上传 scope 和测试账号；`.local/owner-access.json` 含后端发布/账号管理凭证。两者都被 Git 忽略。前端仓库只交文档和工具，不写 token/password。其他机器由负责人私下转交前端文件，不能用后端发布凭证替代。

数据库密码、下载签名密钥、服务 token 不进入日志。Nginx 项目代理关闭 access_log，避免记录含票据的 query。错误响应使用错误码，不返回服务器堆栈。

## 备份与恢复

备份本项目 MySQL schema、/etc/naturalstreaming 私有配置，以及 StateDirectory 内哈希文件；配置备份必须保持私有，不上传源码仓库。媒体不可变，数据库逻辑备份使用 single-transaction，备份期间不清理文件。恢复时先恢复媒体和配置，再恢复数据库，最后启项目服务；不可导入其他项目 schema。

首版不自动删除历史版本、媒体、事件或会话，避免破坏旧版本下载承诺和撤销识别。测试期容量监控后再制定清理周期。

## 验证边界

后端回归证明接口与数据行为，不证明 Android 真机音质、前台服务、音频焦点、锁屏或断网播放。这些由前端按 PLAYBACK.md 验收。


可视化后台已加入，管理员会话、引导配置和操作说明见 ADMIN-WEB.md。网页随 admin JAR 发布，admin.naturalstreaming.net 不再显示占位文本。
