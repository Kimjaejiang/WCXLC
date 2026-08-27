# WCX · 微信增强模块 💠

## ⚠️ 下游仓库声明

本仓库为 **WCX 下游（fork）仓库**，上游： [Johnny520/wcx](https://github.com/Johnny520/wcx)（上游再上游： [WeKit Fork](https://github.com/Ujhhgtg/WeKit)）。

**宗旨**：
- 🎯 **功能稳定落地**：修复上游功能在真实环境中的不稳定、失效或兼容性问题
- ✨ **优化功能**：改进交互体验与功能效果
- 🧹 **优化代码**：提升可维护性、健壮性与可读性
- 🔄 **回馈上游**：所有修改的源代码会同步回上游仓库（PR / 提交）

> 本仓库功能特性以本仓库实际代码为准（含下游优化），上游更新持续合并。
> 一款基于 Xposed 框架的开源微信增强模块，提供**美化主题、聊天增强、隐私防护、AI 自动回复、红包助手**等丰富的功能定制能力。

> 🚀 **项目定位**：不仅仅是一个微信模块，更是一个集成了**去混淆分析工具 (deobf)** 的综合项目 —— 从逆向分析到功能实现，一站式搞定。

---

## 🛠️ 下游修改项（本仓库优化/修复，均同步上游）

> 以下条目均注明**涉及文件**与**实现细节**，便于回溯代码与同步上游。按日期倒序排列。

### 2026-08-27

- **💬 聊天增强 · 切换微信账号后归拢按账号隔离 + 自动对账**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`、`app/src/main/java/com/Johnny/wcx/features/api/core/WeDatabaseApi.kt`
  - 问题：微信 8.0.77 切换账号后，归拢文件夹仍显示其他账号的归拢（配置与自账号缓存跨账号共享）。
  - 方案：
    - 归拢配置按账号分文件存储（`chat_folders_<wxid>.json`），旧共享 `chat_folders.json` 首次使用时一次性迁移继承，之后各账号独立；
    - `WeDatabaseApi.coreStorage/configStorage` 由 `lazy` 缓存改为每次实时获取，避免切换后读到旧账号 self 信息；
    - `methodGetStorage` 检测到 storage 重初始化（账号切换）时重建 db 引用并派发 `notifyDatabaseSwitched`，归拢清缓存按新账号重载配置并重新对账到新库 + 刷新会话列表。

- **💬 聊天增强 · 归拢染色（标题橙 / 摘要提及蓝黄灰）+ 颜色可配置**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`
  - 归拢文件夹标题默认橙色（`#FF8800`）、摘要 `[@全体]`/`[有人@我]` 蓝（`#2E78E6`）、`[N个聊天]`/`[N个消息]` 黄（`#F2D200`）、`[自己]` 深灰（`#222222`）、群成员括号浅灰（`#E8E8E8`）；标题/摘要控件为 `NoMeasuredTextView`（extends X2CView，非 TextView，`getText()` 为空），hook 其 `setText` 注入 Spannable 上色，会话列表 `dispatchDraw` 循环染色兜底；5 项颜色均可在模块设置页配置（WePrefs 持久化，重启微信生效）；标题识别排除未读数角标等纯数字控件。

- **💬 聊天增强 · 自动同意好友申请修复（WCDB insert hook）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/api/core/WeDatabaseListenerApi.kt`、`app/src/main/java/com/Johnny/wcx/features/items/contacts/AutoAcceptFriendRequests.kt`
  - 问题：微信 8.0.77 数据库走 WCDB（`com.tencent.wcdb`），framework `SQLiteDatabase.insertWithOnConflict` 不被触发 → `onInsert` 收不到好友申请消息 → 自动同意永久无效。
  - 方案：insert hook 同时覆盖 `android.database.sqlite.SQLiteDatabase`、`com.tencent.wcdb.compat.SQLiteDatabase`、`com.tencent.wcdb.database.SQLiteDatabase` 三个类（WCDB 同名方法签名兼容，runCatching 防 404）；`onInsert` 解析 `fromContentValues` 失败时输出 error 日志便于排查。

- **🛠️ 系统与工具 · 微信内模块首页「有更新」模块内自动下载安装**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/activity/settings/HomePager.kt`
  - `ActivationCard` 在「有更新」时点击弹 Miuix 确认框（`UpdateConfirmDialog`），确认后直接调用 `AppUpdater.downloadAndInstall`，无需跳转浏览器 releases 页。

- **🛠️ 构建/CI · Maven 源 301/302/403 根治**
  - 涉及文件：`settings.gradle.kts`、`.github/workflows/ci.yml`
  - 阿里云 `public`/`google`/`gradle-plugin` 镜像置前，移除返回 403 的 `maven.pkg.github.com`；CI 网络探测 `::error::NET` 错误 annotation 改纯文本（避免 GitHub 误判 workflow 错误）。

- **🛠️ 构建/CI · CI 海外 runner 镜像 502 根治**
  - 涉及文件：`settings.gradle.kts`、`.github/workflows/ci.yml`
  - GitHub Actions（海外 runner）访问阿里云镜像偶发 502，Gradle 遇 5xx 会禁用该仓库导致后续依赖全挂。`settings.gradle.kts` 仓库列表按 `WCX_USE_ALIYUN` 环境变量分支：CI 设 `false` 直连官方源（google/central/portal 海外更快更稳），国内本地构建默认仍走镜像。

- **🔧 兼容适配 · 适配微信版本更新为推荐 8.0.76 ~ 8.0.77**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/activity/MainActivity.kt`、`README.md`
  - `MainActivity.getAdaptedWeChatVersions()` 三处文案（完整支持/推荐/异常兜底）与 README「适配版本」表同步改为：推荐 8.0.76~8.0.77、维护 8.0.69~8.0.75、低版本 <8.0.69。

- **🛠️ 构建/CI · 本地构建打包 libwekit_native.so**
  - 涉及文件：`app/build.gradle.kts`、`app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/libwekit_native.so`
  - 背景：本地 gradle 构建不跑 xtask native 编译，产物 APK 缺 `libwekit_native.so` → `StartupAgent` 加载原生库失败、模块整体无效果。
  - 方案：从 CI 发布产物（release tag = 提交时间戳）提取对应 ABI 的 so 放入 `jniLibs` 随包打包，本地构建与 CI 行为一致。

- **💾 数据 · 配置迁移（对话归拢丢失修复）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/utils/fs/KnownPaths.kt` 等数据路径入口
  - 数据目录名固定为 `WCXLC`（与显示名 `BuildConfig.TAG` 解耦——品牌切换不再导致目录漂移）；启动首次访问自动把旧 `.../WCX/` 目录数据合并进 `WCXLC/`（保留旧目录不删，同名文件保留新版本），修复品牌改名后归拢等配置丢失。

- **🎨 界面美化 · 品牌名统一 WCXLC**
  - 涉及文件：`app/build.gradle.kts`（`buildConfigField TAG`）、`MainActivity.kt`、`WeChatMessageContextMenuApi.kt`、`WeSettingsInjector.kt`、`FeaturesLoader.kt`、`ExportChatHistory.kt`、`HomeSidePanelFeature.kt`、`TabTheme.kt`、`AndroidManifest.xml` 等
  - `BuildConfig.TAG` 由 WCX → WCXLC（主页标题/微信内入口/侧边栏等自动跟随）；长按菜单、「N 条消息」对话框、下载通知、加载 toast、崩溃报告、主题分享、导出目录等硬编码文案/目录名统一为 WCXLC。

- **🛠️ 构建/CI · 模块内更新安装加固（3 处）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/utils/AppUpdater.kt`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/xml/file_paths.xml`
  - ① `selectApkUrl` 兜底匹配任意 `app-*-release.apk`（flavor 命名漂移仍可下载）；
  - ② 下载文件名改用 `releaseTag`（剔除空格/非法字符，不再出现 `wcx-WCXLC 260826103759.apk`）；
  - ③ `waitForDownload` 遇 ColorOS/MediaProvider 返回的 `content://` URI 时复制到 `cacheDir` 再交给 FileProvider（修复「安装包解析失败」）；`install()` 改用模块自身 FileProvider（`com.Johnny.wcx.provider`，manifest 注册 + `file_paths.xml` 覆盖 cache/external Download），不再复用微信 recovery provider。

### 2026-08-26

- **💬 聊天增强 · 聊天工具栏修复（相册/收藏/动态定位）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ChatToolbar.kt`
  - ① 相册入口平铺场景不显示：微信第一屏 grid 不在 AppPanel 视图树（快照读不到），改为注入显示；
  - ② 收藏点击错位打开相册：微信 `onItemClick` 按 position 语义触发（与 view/tag 无关，实测 `tag.p=favorite` 传 position 0 仍打开相册），收藏改走微信全局收藏页 `com.tencent.mm.plugin.fav.ui.FavoriteIndexUI`；
  - ③ 点击统一在含相册的第一屏 grid 中按名字动态定位 position，不再硬编码映射（微信重建 grid 后快照 index/弱引用 itemView 失效的兜底）；
  - ④ 语音通话/接龙按会话实际格子显示（普通聊天无格子不显示、群聊快照有则正常），群聊无视频通话格子不再误显示。

- **🛠️ 构建/CI · 模块内更新 asset 匹配兼容单 ABI 命名**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/utils/AppUpdater.kt`
  - AppUpdater 原期望 `app-<flavor>-<abi>-release.apk`（带 ABI 段），CI 实际产物为 `app-<flavor>-release.apk`（release 单 ABI 无 splits）匹配不到 → 更新只能跳转 releases 页面；现兼容两种命名，保证装 standard 更新 standard、装 legacy 更新 legacy。

- **🎨 界面美化 · 桌面图标重制**
  - 涉及资源：`app/src/main/res/mipmap-*/ic_launcher*.png`（5 档 DPI）
  - 指定图片去白底 + 红色 `#E53935` 自适应背景图标，webp → png 换源。

- **💬 聊天增强 · 归拢头像稳定性修复（3 项）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/contacts/CustomLocalFriendAvatars.kt`
  - ① 直接 hook `ImageView.setImageDrawable` 防微信异步占位覆盖（DexKit 扫不到系统类）；
  - ② 持久化文件名加时间戳 + UUID 防同名覆盖（修「改一个全变同一张」）；
  - ③ avatar hit 时清 RecyclerView 复用残留 tag 防串图（系统会话/其他文件夹串图）；
  - hook 回调以 `avatarMap` 当前值为准（移除/更换后不拉回旧头像），`onDisable` 可卸载（unhook）。

- **💬 聊天增强 · 选择器默认按最近消息排序**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/ui/content/ContactSelectors.kt`
  - 默认排序改新-旧（最近消息时间），首次进入自动加载时间数据，DB 未就绪时轮询重试。

- **🛠️ 构建/CI · AppUpdater 版本解析 + CI 版本号对齐**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/utils/AppUpdater.kt`、`.github/workflows/ci.yml`
  - ① 12 位时间戳 tag（YYMMDDHHMMSS）正确解析取后 6 位，修复模块主页「一直提示有更新」；
  - ② Release tag 复用 build job 版本号（与 APK 内 versionName 一致），`ver.txt` 随 artifact 上传（否则 release 必挂）。

### 2026-08-25

- **💬 聊天增强 · 群聊归拢摘要染色**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`
  - 归拢文件夹摘要 `[N个聊天]` 黄色、`[有人@我]`/`[@全体]` 蓝色；摘要控件为 `NoMeasuredTextView`（extends X2CView，`getText()` 为空），hook 其 `setText(CharSequence)` 注入 Spannable 上色。

- **📞 联系人与群组 · 本地好友头像持久化**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/contacts/CustomLocalFriendAvatars.kt`
  - 相册 `content://` 复制到模块私有目录存 `file://`，重启微信后不再空白；旧头像自动迁移。

- **🎨 界面美化 · 侧边栏修复（4 项）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/beautify/HomeSidePanelFeature.kt`
  - ① 入口可见性轮询（Tab 切换及时隐藏/恢复）；② 触发按钮挂载条件增强；③ 离开首页保留挂载改隐藏；④ 微信 LauncherUI 非 androidx FragmentActivity 兼容（ActionBarOverlayLayout 识别 Tab）。

- **🎨 界面美化 · 莫奈引擎异常防护**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/beautify/ThemeStore.kt`
  - 主题色解析（`materialScheme`/`primaryColor`/`onPrimaryColor`）包 try-catch，设备不支持动态取色时回退默认色不崩溃。

- **🔔 通知 · 通知演进优化**
  - 涉及文件：通知相关 feature（MessagingStyle 构建/通知取消 hook）
  - ① 发送者头像缓存 + 异步预取（MessagingStyle 头像）；② 同一会话多条通知合并（notify id 统一为会话哈希）；③ 修复已读消息通知带出：微信 cancel 用原始 id 找不到合并后 id → 建立原 id→会话映射 + hook cancel 转换 id 并清空该会话 history。
- **🔔 通知 · 通知头像（微信默认样式·圆角矩形）+ 加载修复**
  - 涉及文件：`NotificationsEvolved.kt`、`WeDatabaseApi.kt`、`ContactSelectors.kt`
  - `toRoundedBitmap()` 圆角矩形裁剪（25% 半径，微信默认样式）；头像加载修复：磁盘缓存/本地路径/CDN 兜底；选择器排序按联系人 id IN 限定查询（批量取最近消息时间）加速。

### 更早

- **💬 聊天增强 · 归拢@提醒优化** — `ConversationAggregation.kt`：入口行被@时摘要显示「有人@我」（atMeCount 聚合到 folder 行）；染绿 hook 扩展双适配器；剥离摘要 WXID 前缀（后因稳定性回退，保留归拢基础功能）。
- **💬 聊天增强 · 解除消息多选数量限制 8.0.77** — `RemoveMessageSelectionLimit.kt`：DexKit 适配（ChattingDataAdapterV3 移除，allowFailure + placeholder 降级，onEnable guard）。
- **💬 聊天增强 · 自动同意好友申请 8.0.77** — hook 目标改构造器（`p3.<init>` 接受入口，DexMethodDelegate 无法解析构造器导致 `NoSuchMethodException`）。
- **💬 聊天增强 · 预见性返回动画 8.0.77** — hook 回调 null 保护 + `ActivityInfo.name` 为 null 降级 + 字段查找异常容错。
- **🎨 界面美化 · 侧边栏头像加载提速** — `HomeSidePanelFeature.kt`：解码按目标尺寸缩小（192px）+ 缓存读写缩小图 + selfWxId 等待 10s→4s + 重试 15→3 次，首次加载从 20s+ 降至秒级。
- **🎨 界面美化 · 天气卡片（3 项）** — `HomeSidePanelFeature.kt`：① Open-Meteo API 格式识别（`parseWeatherJson` 增加 `current` 字段）；② 湿度/风速独立行左右分布防挤压；③ 加载占位紧凑化、温度 48sp→40sp、湿度补 %/风速补 km/h。
- **🎨 界面美化 · fork 品牌化** — 版本号统一加 LC 后缀（Kimjaejiang/WCXLC），模块主页 WCX→WCXLC，设置页 GitHub/官方链接与作者署名改为 fork。
- **🎨 界面美化 · v245 UI 层对齐 + 侧边栏/主题商城** — 整体替换 UI（69 文件）+ 新增侧边栏 `HomeSidePanelFeature` / 主题商城，适配 hook 抽象 / DexKit / agent API。
- **🔧 兼容适配 · 8.0.77 通话栈/防御性加固** — PipVoip 通话栈 27 处 dex 解析批量 allowFailure；hook 回调 null 保护 / lazy 链式解析容错 / 微信类硬引用降级。
- **🔧 兼容适配 · 版本号对齐 fork Releases** — 默认版本号 v244.12→v252.1（本地构建 verCode 252001，可被 CI 自动发布覆盖升级）。
- **🛠️ 构建/CI · native/R8/CI 修复** — mp3lame-sys 改用 cc 编译、xtask bindgen versioned target、R8 保留 compose 类、Gradle IPv4 优先等。

### 📊 变更对照表（按模块分组）

> 与上文详细条目一一对应，快速浏览每个功能改了什么。

#### 💬 聊天增强

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-27 | 归拢染色可配置 | 标题橙 / 提及蓝 / 聊天数黄 / 自己深灰 / 成员括号浅灰，5 色均可配置；`NoMeasuredTextView` setText 注入 Spannable + dispatchDraw 兜底 | `ConversationAggregation.kt` |
| 08-27 | 切换账号归拢隔离 | 配置按账号分文件 + storage 实时化 + db 切换重载对账，各账号互不串扰 | `ConversationAggregation.kt`、`WeDatabaseApi.kt` |
| 08-27 | 自动同意好友申请 | insert hook 覆盖 framework + WCDB compat/database 三类，WCDB 下生效 | `WeDatabaseListenerApi.kt`、`AutoAcceptFriendRequests.kt` |
| 08-27 | 选择器排序加速 | 按联系人 id IN 限定查询（批量取最近消息时间）加速 | `ContactSelectors.kt` |
| 08-26 | 聊天工具栏 | 相册注入显示 / 收藏走 `FavoriteIndexUI` / 点击动态定位 / 按实际格子显示 | `ChatToolbar.kt` |
| 08-26 | 联系人/群聊选择器 | 默认按最近消息时间新-旧排序，DB 未就绪轮询重试 | `ContactSelectors.kt` |
| 08-25 | 群聊归拢摘要 | `[N个聊天]` 黄、`[有人@我]`/`[@全体]` 蓝（hook `setText` 注入 Spannable） | `ConversationAggregation.kt` |
| 更早 | 群聊归拢@提醒 | 被@时摘要显示「有人@我」（后因稳定性回退，保留归拢基础功能） | `ConversationAggregation.kt` |
| 更早 | 消息多选 | DexKit 适配 8.0.77 + allowFailure/placeholder 降级 | `RemoveMessageSelectionLimit.kt` |
| 更早 | 自动同意好友申请 | hook 目标改构造器（`p3.<init>` 接受入口） | 好友申请 feature |
| 更早 | 预见性返回动画 | hook 回调 null 保护 + 降级 + 字段查找容错 | 返回动画 feature |

#### 🔔 通知

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-27 | 通知头像（微信默认样式） | 圆角矩形 25% 裁剪（微信默认）；加载修复：磁盘缓存/本地路径/CDN 兜底 | `NotificationsEvolved.kt`、`WeDatabaseApi.kt` |
| 08-25 | 新消息通知 | 头像缓存+异步预取 / 同会话通知合并 / cancel 转换 id 清空 history | 通知相关 feature |

#### 📞 联系人与群组

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-26 | 本地好友头像（归拢） | hook `setImageDrawable` / 文件名时间戳+UUID / 清残留 tag / `onDisable` 可卸载 | `CustomLocalFriendAvatars.kt` |
| 08-25 | 本地好友头像持久化 | `content://` 复制到私有目录存 `file://` + 旧头像自动迁移 | `CustomLocalFriendAvatars.kt` |

#### 🎨 界面美化

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-27 | 品牌名 | 主页/微信内入口/侧边栏/通知等文案统一 WCXLC（`BuildConfig.TAG` + 9 处硬编码） | `BuildConfig.TAG` 等 9 文件 |
| 08-26 | 桌面图标 | 去白底 + 红色 `#E53935` 自适应背景 png（5 档 DPI） | `res/mipmap-*` |
| 08-25 | 侧边栏 | 入口可见性轮询 / 挂载条件增强 / 离开改隐藏 / LauncherUI 兼容 | `HomeSidePanelFeature.kt` |
| 08-25 | 莫奈引擎（主题取色） | 主题色解析 try-catch，设备不支持动态取色回退默认色 | `ThemeStore.kt` |
| 更早 | 侧边栏头像加载 | 解码缩小 192px + 缓存缩小图 + 等待 4s + 重试 3 次，秒级加载 | `HomeSidePanelFeature.kt` |
| 更早 | 天气卡片 | 识别 `current` 字段 / 湿度风速分行 / 占位紧凑 | `HomeSidePanelFeature.kt` |
| 更早 | 品牌化 | WCXLC 品牌 + LC 版本后缀（Kimjaejiang/WCXLC） | 多处 |
| 更早 | v245 UI + 侧边栏/主题商城 | 整体替换 UI（69 文件）+ 新增侧边栏与主题商城 | UI 层 69 文件 |

#### 🛠️ 构建/CI

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-27 | Maven 构建源 | 阿里云镜像置前 + 移除 GitHub Packages + CI 探测错误改纯文本 | `settings.gradle.kts`、`ci.yml` |
| 08-27 | CI 海外镜像 502 | CI 设 `WCX_USE_ALIYUN=false` 直连官方源，本地默认镜像不受影响 | `settings.gradle.kts`、`ci.yml` |
| 08-27 | 模块内更新入口 | 首页「有更新」点击确认后模块内自动下载安装 | `HomePager.kt` |
| 08-27 | 模块核心加载 StartupAgent | 本地构建打包 `libwekit_native.so` 随 APK，与 CI 行为一致 | `app/build.gradle.kts`、`jniLibs` |
| 08-27 | 模块内更新安装 | 兜底匹配 `app-*-release.apk` / `releaseTag` 文件名 / `content://` 转 cacheDir + 自身 FileProvider | `AppUpdater.kt`、`AndroidManifest.xml`、`file_paths.xml` |
| 08-26 | 模块内更新下载 | 兼容带 ABI 与不带 ABI 两种 APK 命名 | `AppUpdater.kt` |
| 08-26 | 模块内更新版本检测 | 12 位时间戳 tag 解析 / `ver.txt` 随 artifact 上传 | `AppUpdater.kt`、`ci.yml` |
| 更早 | native/R8/CI | cc 编译 / versioned target / R8 保留 compose 类 / Gradle IPv4 优先 | 构建配置 |

#### 💾 数据与配置

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-27 | 配置存储（对话归拢等） | 数据目录固定 `WCXLC`，首次访问自动合并旧 `WCX/`（不删旧、同名留新） | `KnownPaths.kt` 等路径入口 |
| 更早 | 版本号 | v244.12 → v252.1（本地 verCode 252001，可被 CI 自动发布覆盖升级） | `app/build.gradle.kts` |

#### 🔧 兼容适配

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-27 | 适配微信版本 | 推荐 8.0.76~8.0.77 / 维护 8.0.69~8.0.75 / 低版本 <8.0.69 | `MainActivity.kt`、`README.md` |
| 更早 | 8.0.77 通话栈（PipVoip） | 27 处 dex 解析批量 allowFailure + 回调 null 保护 + 懒加载容错 | PipVoip 通话栈 |

---
## ✨ 功能特性

### 💬 聊天增强
- **阻止消息撤回**：对方撤回的消息依然可见
- **已读追踪**：本地 Hook 实现，不依赖服务器查看群消息已读人数
- **AI 自动回复**：支持多种触发模式（仅被@、关键词、所有消息），可选择特定群聊开启
- **定时发送消息**：支持单次或每日重复发送，兼容多种消息类型
- **进退群提示**：监控群成员进退群，自动发送自定义消息（文字/图片/语音/视频/文件）
- **解除表情上限**：突破微信自定义表情 999 个上限限制
- **自动语音转文字**：语音消息自动转文字显示
- **自动查看原图**：媒体自动加载原图，省得手动点
- **聊天工具栏**：增强聊天输入栏，常用功能一键直达
- **Markdown 渲染**：支持 Markdown 格式消息渲染
- **消息复读**：一键复读群消息
- **左划引用消息**：左划消息快速引用回复
- **贴纸/语音保存到本地**：一键保存到手机存储
- **虚拟视频通话**：自定义视频通话画面

### 🎨 界面美化
- **莫奈引擎 (Monet)**：动态取色，让微信界面更协调
- **Tab 主题背景**：为微信主页、通讯录、发现、我四个界面分别设置背景图片
- **主题导入导出**：支持主题包的导入导出，方便分享和备份
- **自定义配色**：个性化界面颜色方案
- **美化首页底部导航栏**：自定义底部导航样式
- **「我」页面精简**：隐藏不需要的入口，页面更清爽
- **对话框窗口级背景模糊**：毛玻璃效果，视觉升级
- **圆角头像**：统一的圆角头像风格
- **DPI 修改**：自定义界面显示密度
- **隐藏主页下滑「最近」页**：去掉不需要的页面

### 🛡️ 隐私 & 安全
- **反检测/防追踪**：防止微信检测 Xposed 环境
- **环境伪装**：设备信息、系统环境全方位伪装
- **隐藏模块应用**：在应用列表中隐藏模块 App
- **阻止微信清理模块数据**：防止微信擅自清除模块数据
- **禁止上传正在输入状态**：对方看不到你正在打字
- **禁止微信热更新**：防止微信偷偷更新导致模块失效
- **禁止「转发截图」提示**：转发截图不再弹提示
- **禁用 WebView 安全警告**：打开网页不再弹安全警告

### 💰 红包与支付
- **自动抢红包**：收到红包自动拆开
- **自动接收转账**：转账自动接收
- **指纹支付**：快捷指纹支付
- **修改显示余额**：自定义钱包余额显示（娱乐向）

### 📞 联系人与群组
- **检测单向删除好友**：找出谁把你删了
- **隐藏联系人**：把不想看到的联系人藏起来
- **退群监控**：群成员退群实时提醒
- **显示微信 ID**：在资料页显示对方微信号
- **分裂群组**：群聊分组管理
- **查看群成员消息历史**：查看某群成员的所有消息
- **显示群成员身份**：群主/管理员标识

### 📸 朋友圈
- **拦截朋友圈删除**：对方删了的动态你还能看到
- **朋友圈伪集赞**：自定义点赞数（自娱自乐）
- **拦截朋友圈广告**：刷圈不再被广告打扰
- **朋友圈查询增强**：更强大的朋友圈搜索

### 🔧 系统与工具
- **API 服务器**：提供 HTTP API 接口，可对接外部应用
- **虚拟定位**：修改微信定位
- **修改运动步数**：自定义步数
- **强制平板模式**：手机也能用平板界面
- **清理缓存垃圾**：一键清理微信缓存
- **灰度测试管理器**：手动开关微信灰度功能
- **省电模式**：降低微信后台耗电

### 📺 视频号 / 小程序 / 公众号
- **视频号下载**：下载视频号视频
- **小程序去广告**：移除小程序开屏/视频/嵌入广告
- **小程序跳过启动页**：直接进入小程序内容
- **公众号多开**：公众号网页多窗口打开

### 📜 脚本引擎
- **JavaScript 脚本支持**：用 JS 编写自定义功能
- **多触发器**：收到消息/发起请求/收到响应
- **完整 API**：丰富的 Hook API，可深度定制

---

## 🏗️ 项目结构

```
wcx/
├── app/                # 📱 Xposed 模块主程序（Kotlin）
│   ├── src/main/       # 主源码
│   ├── src/standard/   # 标准构建变体
│   ├── embedded/       # 嵌入的原生库
│   └── schemas/        # 数据库 Schema
├── deobf/              # 🔬 微信模块去混淆分析工具
│   ├── unidbg-harness/ # unidbg ARM64 模拟器脱壳解密
│   ├── moduledata/     # 加密载荷与原生库
│   ├── tools/          # Python 后处理工具
│   └── run_deobf.sh    # 一键运行脚本
├── buildSrc/           # ⚙️ Gradle 构建配置
├── xtask/              # 🦀 Rust 构建任务
├── docs/               # 📖 文档（功能说明 / FAQ / 开发指南）
├── scripts/            # 🛠️ 辅助脚本
├── icons/              # 🎨 图标资源
├── libs/               # 📦 依赖库
├── contrib/            # 🤝 贡献者资源
├── build.gradle.kts    # 项目构建配置
├── settings.gradle.kts # 项目设置
└── gradle.properties   # Gradle 属性
```

---

## 📦 核心模块详解

### 📱 app — Xposed 模块主程序

基于 Kotlin 开发的微信 Xposed 模块，通过 LSPosed 框架注入微信进程，实现各项增强功能。

**核心能力：**
- **Hook 框架**：基于 LSPosed / Xposed API 的方法 Hook
- **UI 注入**：向微信界面注入自定义控件和布局
- **数据存储**：Room 数据库持久化配置
- **脚本引擎**：嵌入式 JavaScript 运行环境
- **主题系统**：动态资源替换与主题引擎

### 🔬 deobf — 去混淆分析工具

基于 unidbg ARM64 模拟器的动态解密与去混淆框架，用于分析微信模块的加密载荷。

**功能亮点：**
- **动态脱壳**：在模拟器中运行原生加载器，捕获解密后的 DEX
- **自动化管线**：一键运行，从加密文件到可分析代码
- **Python 工具集**：格式清理、字符串提取、翻译提取等后处理
- **分析报告**：生成详细的分析报告文档

> 详细说明见 [deobf/README.md](deobf/README.md)

### 🦀 xtask — Rust 构建任务

用 Rust 编写的构建辅助工具，提供高效的编译和打包流水线。

### 📖 docs — 完整文档

包含功能说明、安装指南、配置教程、开发文档等完整文档体系：
- 快速开始 / 安装指南
- 模块设置说明 / 配置指南
- 开发指南 / 脚本 API 参考
- 常见问题 FAQ
- 问题反馈指南 / 建议反馈指南
- 免责声明

---
## 📋 适配版本

> 💡 **推荐使用微信 8.0.76 ~ 8.0.77**，功能完整且经过测试。

| 状态 | 版本范围 | 说明 |
|------|----------|------|
| ✅ 推荐使用 | 8.0.76 ~ 8.0.77 | 功能完整适配，推荐使用 |
| 🔧 维护中 | 8.0.69 ~ 8.0.75 | 基本可用，如有问题欢迎反馈 |
| ⚠️ 低版本 | < 8.0.69 | 部分功能可能无法使用，不推荐 |
- **框架支持**：LSPosed（推荐）、EdXposed、Xposed
- **系统要求**：Android 8.0+（推荐 Android 10+）
- **架构支持**：arm64-v8a（主要）、armeabi-v7a

---

## 🔧 构建

```bash
# 克隆项目（含子模块）
git clone --recursive https://github.com/Kimjaejiang/WCXLC.git
cd WCXLC

# 构建 Release 版本
./gradlew assembleRelease

# 构建 Debug 版本
./gradlew assembleDebug

# 运行去混淆分析（需要 Java 环境）
cd deobf && ./run_deobf.sh
```

---

## 📄 许可

- `deobf/` 目录下的代码基于 **GPL-3.0** 许可
- `app/` 及其余代码基于相应许可协议
- 详见各子目录 LICENSE 文件

---
