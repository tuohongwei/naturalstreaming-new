# NaturalStreaming 可视化后台

地址：https://admin.naturalstreaming.net 。使用独立管理员账号，App 测试账号不能登录。初始登录资料由后端私下交付，未写入 Git。登录后右上角可修改密码；修改后所有旧管理会话失效。

## 日常使用

1. **内容概览**：查看主题、上架、草稿和 App 账号情况。默认隐藏回归测试记录，可在主题库和账号页勾选显示。
2. **主题库**：打开主题，选择版本。编辑草稿的音轨名称、描述、默认增益和预设；试听原始 A/B 文件。试听不等于完整 App 混音验收。
3. **来源与授权**：填写真实来源和许可，内容权利负责人确认后勾选审核。不能用勾选代替核实权利。媒体清单内容变更会清除已有审核标记，需重新确认。
4. **保存和校验**：保存后修订号增加，需重新校验。校验失败展示对应字段；不完整草稿自动进入 JSON 修复入口。
5. **发布版本**：校验通过后明确点击发布，并确认。发布请求绑定当前屏幕的修订号；他人修改后返回冲突，重新读取再审核。已发布版本只读，修改需创建新版本。
6. **下架和回滚**：在主题详情执行，操作前确认。下架不撤销用户已有授权；回滚切换历史发布版本，不改写内容。
7. **账号与授权**：创建 App 账号、重置密码、启用/禁用，以及选择可用主题。撤销主题权限会再次确认。后台管理员账号与这些 App 账号分离。
8. **操作记录**：最近 100 条发布、草稿、账号、授权及管理员登录记录。

## 上传主题

在“上传内容”选择含 `scene.json`、`manifest.json` 与媒体文件的文件夹。可以选择单个主题，也可以选择多个主题的父目录。ZIP 先在本机解压；现有 Python 上传工具仍支持 ZIP。

指定内容版本，例如 `1` 或 `2`。每个文件核对清单大小及 SHA-256，已存在的哈希会复用。相同主题/版本的草稿按修订更新；已发布版本不能覆盖。上传只创建草稿，不自动发布。可取消上传，已上传对象保留，重试时复用。

首次导入的来源资料为空且未审核。重新导入时保留来源文字，若文件路径或哈希改变，清除 reviewed；相同内容重试保留审核。网页音频上传不持有发布密钥，服务凭证仅在服务器使用。

## 登录与安全

- 管理会话闲置 30 分钟失效，Cookie 为 Secure、HttpOnly、SameSite=Strict，固定在当前主机。
- 状态修改校验 Origin 和 CSRF；浏览器中不保存长期服务 token。
- 退出或会话失效时清空密码字段、关闭敏感对话框并移除当前管理数据。
- 多个管理员同时编辑，保存/校验/发布按修订号检测冲突。原有脚本 API 保持兼容，可选携带 revision；网页校验和发布必须携带。
- 服务凭证管理不开放到网页。网页的专用服务身份只有 upload、publish、accounts 权限。

## 部署与恢复

网页资源随 admin JAR 部署。V003 新建独立 console_user 表，原 App account/login_session 不变。服务器 admin.env 增加 CONSOLE_SERVICE_TOKEN、CONSOLE_ORIGIN 及首次引导用户配置；私有文件保持原属主和 0600 模式，配置前备份位于 `/etc/naturalstreaming/admin.env.before-console-*`。

`ops/configure_console.py` 只接收标准输入中的私有 JSON，检查目标为 root 的普通文件及私有目录，不在命令行、输出或仓库放入密码。引导使用 INSERT IGNORE，不覆盖已有管理员密码。

部署只修改 NaturalStreaming JAR、schema、环境文件及自己的 Nginx 配置。当前 admin 主机代理到 admin 服务 8191，admin-api 接口地址不变；media 服务新增需 upload 权限的试听接口。

每次发布在专属锁内保存本次开始前的无关配置哈希/PID快照到 `/srv/naturalstreaming/backups/baseline-<release>.json`。使用该快照独立验证，保留首次部署原始快照；其他项目在两次部署之间自行更新，不伪装为本次变化或覆盖旧证据。

## 验证

```text
mvn -f backend/pom.xml verify
node ops/test_console_policy.cjs
python ops/test_console.py
node ops/test_console_dom.cjs
```

HTTP 测试读取忽略目录中的 `.local/console-access.json`。DOM 测试需要 jsdom 26.1.0，安装到 `.local/console-tests` 后执行；它使用真实网页源码与真实接口，但不是视觉浏览器测试。测试只修改 console-test-* 合成主题和账号，不改真实音乐草稿。

开发机安装该测试依赖可运行 `npm install --prefix .local/console-tests --ignore-scripts --no-audit --no-fund jsdom@26.1.0`；生产网页不依赖 npm、CDN 或这些测试模块。

当前原生浏览器测试工具受 Windows 访问权限限制，未完成真实浏览器视觉检查。HTTP、页面 DOM 交互、音频字节试听接口与既有后端回归独立验证；iPhone 播放效果仍由 App 真机验收。
