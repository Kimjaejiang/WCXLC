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

### 2026-09-02

- **💬 聊天增强 · 归拢 @所有人 正确显示 [@全体]（不再误判为 [有人@我]）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`
  - 问题：文件夹内群聊 @所有人 时，文件夹行摘要错误显示 `[有人@我]` 而非 `[@全体]`（单群聊 @所有人 场景必现）。
  - 根因 ①：判定只检查**最新一条**成员行摘要的文本关键词（所有人/全体/全员/全部人/@all 等），而微信 @所有人 的摘要文本不一定含这些词（日志实测摘要与内容均无关键词）——文本判断经常漏判；
  - 根因 ②：微信权威标记在 `rconversation.atCount` 的 **bit 24（0x01000000）**，日志实测 @所有人 时 `atCount=16777216`。
  - 修复：判定改双重（`atCount` bit24 权威标志 + 文本关键词兜底），且改为**聚合判断**（任一未读成员行命中即显示 `[@全体]`，不只看最新一条）；已读后恢复普通摘要。

- **💬 聊天增强 · 归拢免打扰未读计入文件夹角标（小圆点）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`
  - 问题：免打扰群聊的未读完全不显示在文件夹角标（此前仅修了「闪现红色数字角标」）。
  - 根因：微信对免打扰会话 `unReadCount==0`、`unReadMuteCount>0`，原统计只进 `if (unread > 0)` 分支，`muteUnread` 从未被计入 `mutedUnread` → 文件夹行 `unReadMuteCount==0`。
  - 修复：`unReadMuteCount > 0` 直接计入 `mutedUnread`，文件夹角标正确显示免打扰未读（小圆点样式，非红色数字）。

### 2026-08-31

- **💬 聊天增强 · 归拢摘要颜色设置完善（弹窗内实时配色 + 独立开关）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregationColors.kt`（新增）、`ConversationAggregation.kt`、`activity/settings/FeaturesPager.kt`
  - ① 新增与「对话归拢」同级的配色设置项，读写同一组 `agg_mention_*` / `agg_folder_*` 键（与归拢染色 5 色配置互通）；
  - ② 弹窗内嵌入实时配色区：改动即写 pref 并刷新会话列表，无需重启；深色模式文字显式 `onSurface`/`onSurfaceVariant`；
  - ③ 文件夹标题染色改为**独立开关**，不再随摘要颜色总开关关闭；
  - ④ 设置搜索与分类列表过滤「对话归拢摘要颜色」项（收进归拢弹窗内，避免设置页平级项残留）。

- **💬 聊天增强 · 归拢文件夹特殊行：学校通知打开企业会话页**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`
  - 归拢文件夹内「学校通知」行（`gh_158599a58f81`）点击打开企业会话页 `EnterpriseConversationUI`（与微信首页行为一致），实测正常。

- **💬 聊天增强 · 归拢免打扰群聊角标闪现修复**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`
  - 问题：免打扰群聊的未读偶尔闪现红色数字角标（应显示小圆点）。
  - 根因：`parseChatRoomNotify` 在 lvbuff 缺失/解析失败时返回 `null`，原判定 `notify == 0` 把 `null` 误判为「非免打扰」，未读误计入 `normalUnread` → 文件夹行 `unreadCount > 0` → 红色数字角标闪现。
  - 修复：改为 `notify == null || notify == 0`，lvbuff 解析失败时保守归入免打扰（小圆点），与「lvbuff 解析失败/缺失时兜底判定免打扰」的注释意图一致。

### 2026-08-30

- **🔔 通知 · 新消息通知头像增强**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/notifications/NotificationsEvolved.kt`
  - ① 头像缓存目录升级 `notif_avatars_v3`（微信原生样式小圆角头像，旧版缓存不再读取）；
  - ② 单聊第一条消息同步读磁盘缓存，命中立即显示头像（不等异步预取）；
  - ③ 群聊发送者昵称精确匹配失败时 LIKE 模糊兜底（备注/昵称空格、符号差异也能匹配到成员）。

- **💬 聊天增强 · 发送后合并显示自动勾选（已移除）**
  - 曾尝试发送预览页自动勾选「发送后合并展示」；微信的合并设置依赖真实触摸输入管道（防自动化），程序化勾选无法生效，该功能已移除。

- **💬 聊天增强 · 归拢文件夹修复（免打扰角标 / @所有人识别 / [@全体] 标签）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`
  - ① 免打扰群聊角标：优先用 rconversation 的 `unReadMuteCount` 权威判定免打扰（lvbuff 解析失败/缺失时兜底），避免免打扰群聊被误当普通未读显示角标；
  - ② @所有人识别扩展：补充「全部人」「全员」等微信摘要形式（原有 所有人/全体/@all/@everyone/all members）；
  - ③ [@全体] 标签上色修复：生成的是带 @ 的 `[@全体]`，原上色/识别逻辑只匹配 `[全体]`（漏 @）导致不上色，已兼容两种形式；
  - ④ [@全体] 已读后消失：补未读条件（atMeCount/unreadChatCount），与 [有人@我] 行为一致。

### 2026-08-29

- **🎨 界面美化 · 隐藏对话列表分割线（会话/归拢文件夹/搜索页）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/beautify/HideConversationListDividers.kt`
  - 隐藏微信 8.0.77 对话列表的分割线，按当前前台 Activity 限定处理范围，不影响通讯录/发现/我的/设置：
    - 会话页：ListView/RecyclerView item 内线 View（1~6px 高全宽）尺寸识别后 GONE + 锁定（`setVisibility` 拦截 + `OnGlobalLayout` 持续强制，对抗微信 bind/post 恢复可见性）；
    - 归拢文件夹（`ConvBoxServiceConversationUI`）：item 根 9-patch 背景替换为页面同色（深色模式取深灰背景、亮色兜底白——亮度阈值只认深色，防止中灰误判）；
    - 搜索页（`FTSMainUI`）：item 底部 `clipBounds` 裁剪 1px；
    - 兜底：`ListView.dividerHeight` 恒置 0 + `setDivider` 透明；Canvas `draw` 拦截未锁定的 1~6px 全宽线 View 不绘制。

- **💬 聊天增强 · 归拢文件夹长按菜单修复 + 摘要标签增强**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`
  - ① 文件夹容器长按菜单修复：`menuContext` 从 `args[0]`（anchor View）获取（`MMPopupMenu` 不是 View，`thisObject` 取不到），「移到/移出文件夹」菜单项在文件夹容器的长按菜单正常弹出；
  - ② 摘要标签 `[全体]` 改为 `[@全体]`；
  - ③ `@所有人` 识别增强（所有人/全体/@all/@everyone/all members），仅存在 @所有人 提及时不再误显示 `[有人@我]`。

- **🛠️ 构建/CI · APK 体积优化 -37%（35.4MB → 22.4MB）**
  - 涉及文件：`app/proguard-rules.pro`
  - 移除 `-keep class androidx.compose.** { *; }` 全量保留规则，让 R8 正常裁剪未使用的 Compose 代码（dex 3 个文件减为 2 个）；已实机验证设置页、归拢文件夹长按菜单、对话框均正常，功能与上一版完全一致。

### 2026-08-28

- **💬 聊天增强 · 自动同意好友申请完整打通（8.0.77 接受链路）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/contacts/AutoAcceptFriendRequests.kt`
  - 问题：8.0.77 同一申请会在 `VerifyRecordMsgInfo`/`fmessage_msginfo` 连插多条且 **ticket 每次插入都变**（一次性防重放），旧去重 key 含 ticket 导致重复接受/重复欢迎语；`NetSceneVerifyUser` 构造器校验 `opcode == MM_VERIFYUSER_VERIFYOK`，传 1/2 均报 `MUST use opcode == MM_VERIFYUSER_VERIFYOK`，实测通过值为 **3**。
  - 方案：
    - 双路径捕获申请：`onInsert`（VerifyRecordMsgInfo/fmessage_msginfo）+ message type=37 轮询；
    - 解析 8.0.77 属性形式 XML（`extractAttr` 属性解析，兼容子标签 `extractXmlValue`）；
    - 接受走 `NetSceneVerifyUser` 构造器 + `sendNetScene`，opcode 遍历 1..8 找到 3 通过校验（兼容其他版本，成功即止）；
    - 去重 key 改用 `encryptUsername`（稳定标识，ticket 不入 key），同申请只接受一次；
    - 欢迎语统一在 `acceptFriendRequest` 发送（移除 hookAfter 双发路径），实测 1 次 accept + 1 条欢迎语；
    - 免验证（type=1）仅记录日志不干预（纯净版）。

### 2026-08-27

- **💬 聊天增强 · 切换微信账号后归拢按账号隔离 + 自动对账**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`、`app/src/main/java/com/Johnny/wcx/features/api/core/WeDatabaseApi.kt`
  - 问题：微信 8.0.77 切换账号后，归拢文件夹仍显示其他账号的归拢（配置与自账号缓存跨账号共享）。
  - 方案：
    - 归拢配置按账号分文件存储（`chat_folders_<wxid>.json`），旧共享 `chat_folders.json` 首次使用时一次性迁移继承，之后各账号独立；
    - `WeDatabaseApi.coreStorage/configStorage` 由 `lazy` 缓存改为每次实时获取，避免切换后读到旧账号 self 信息；
    - `methodGetStorage` 检测到 storage 重初始化（账号切换）时重建 db 引用并派发 `notifyDatabaseSwitched`，归拢清缓存按新账号重载配置并重新对账到新库 + 刷新会话列表。

- **💬 聊天增强 · 归拢染色 5 色可配置（取色器选取）+ 暗色/亮色自动适配**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/items/chat/ConversationAggregation.kt`
  - 归拢文件夹摘要/标题 5 色全部可配置（设置页取色器 `WeColorField` 色块弹窗选取，`WePrefs` 持久化）：
    - 标题（默认橙 `#FF8800`）/ `[@全体]`、`[有人@我]` 提及（默认蓝 `#2E78E6`）/ `[N个聊天]`、`[N个消息]` 聊天数（默认黄 `#F2D200`）/ `[自己]`（默认深灰 `#222222`）/ `(群成员)` 括号（默认浅灰 `#E8E8E8`）；
    - 标题 / `[自己]` / `(群成员)` 3 项可单独开关（默认开，关闭回微信原生色）；
    - 暗色模式染色自动提亮（HSV 明度下限 0.78），保证深底可读（类似微信原生暗色白字），亮色模式用原色；
    - 摘要为 `NoMeasuredTextView`，dispatchDraw 阶段叠加彩色绘制覆盖原生灰色，无状态残留。

- **💬 聊天增强 · 自动同意好友申请修复（WCDB insert hook）**
  - 涉及文件：`app/src/main/java/com/Johnny/wcx/features/api/core/WeDatabaseListenerApi.kt`、`app/src/main/java/com/Johnny/wcx/features/items/contacts/AutoAcceptFriendRequests.kt`
  - 问题：微信 8.0.77 数据库走 WCDB（`com.tencent.wcdb`），framework `SQLiteDatabase.insertWithOnConflict` 不被触发 → `onInsert` 收不到好友申请消息 → 自动同意永久无效。
  - 方案：insert hook 同时覆盖 `android.database.sqlite.SQLiteDatabase`、`com.tencent.wcdb.compat.SQLiteDatabase`、`com.tencent.wcdb.database.SQLiteDatabase` 三个类（WCDB 同名方法签名兼容，runCatching 防 404）；`onInsert` 解析 `fromContentValues` 失败时输出 error 日志便于排查。

- **💰 红包 · 私聊红包可领 + 分裂群组假红包仅假群注入**
  - 涉及文件：红包相关 feature
  - 恢复红包功能原始实现，允许领取私聊红包（此前误限制为群聊）；分裂群组假红包仅对假群（`@@chatroom`）注入 key_type，普通群/私聊不受影响。

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

- **🔧 兼容适配 · 8.0.76 Dex 扫描兼容（通话记录/朋友圈视频）**
  - 涉及文件：`HideContacts.kt`、`WeMomentsApi.kt` 等
  - 8.0.76 下 `HideContacts` voip 通话记录插入存在双路径（Multiple methods found，allowMultiple 取第一个 ZIDL 与 8.0.77 同目标）；`WeMomentsApi` 朋友圈视频 `addSightObjectByPath` 在 8.0.76 不存在（allowFailure 降级，调用处 try/catch 兜底）。

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

### 2026-08-24

- **💬 聊天增强 · 归拢@提醒优化** — `ConversationAggregation.kt`：入口行被@时摘要显示「有人@我」（atMeCount 聚合到 folder 行）；染绿 hook 扩展双适配器；剥离摘要 WXID 前缀（后因稳定性回退，保留归拢基础功能）。
- **💬 聊天增强 · 解除消息多选数量限制 8.0.77** — `RemoveMessageSelectionLimit.kt`：DexKit 适配（ChattingDataAdapterV3 移除，allowFailure + placeholder 降级，onEnable guard）。
- **💬 聊天增强 · 自动同意好友申请 8.0.77**（已被 08-28 完整版取代）— hook 目标改构造器（`p3.<init>` 接受入口，DexMethodDelegate 无法解析构造器导致 `NoSuchMethodException`）。
- **💬 聊天增强 · 预见性返回动画 8.0.77** — hook 回调 null 保护 + `ActivityInfo.name` 为 null 降级 + 字段查找异常容错。
- **🎨 界面美化 · 侧边栏头像加载提速** — `HomeSidePanelFeature.kt`：解码按目标尺寸缩小（192px）+ 缓存读写缩小图 + selfWxId 等待 10s→4s + 重试 15→3 次，首次加载从 20s+ 降至秒级。
- **🎨 界面美化 · 天气卡片（3 项）** — `HomeSidePanelFeature.kt`：① Open-Meteo API 格式识别（`parseWeatherJson` 增加 `current` 字段）；② 湿度/风速独立行左右分布防挤压；③ 加载占位紧凑化、温度 48sp→40sp、湿度补 %/风速补 km/h。
- **🎨 界面美化 · fork 品牌化** — 版本号统一加 LC 后缀（Kimjaejiang/WCXLC），模块主页 WCX→WCXLC，设置页 GitHub/官方链接与作者署名改为 fork。
- **🎨 界面美化 · v245 UI 层对齐 + 侧边栏/主题商城** — 整体替换 UI（69 文件）+ 新增侧边栏 `HomeSidePanelFeature` / 主题商城，适配 hook 抽象 / DexKit / agent API。
- **🔧 兼容适配 · 8.0.77 通话栈/防御性加固** — PipVoip 通话栈 27 处 dex 解析批量 allowFailure；hook 回调 null 保护 / lazy 链式解析容错 / 微信类硬引用降级。
- **🔧 兼容适配 · 版本号对齐 fork Releases** — 默认版本号 v244.12→v252.1（本地构建 verCode 252001，可被 CI 自动发布覆盖升级）。
- **🛠️ 构建/CI · native/R8/CI 修复** — mp3lame-sys 改用 cc 编译、xtask bindgen versioned target、R8 保留 compose 类、Gradle IPv4 优先等。
- **🛠️ 构建/CI · 新功能分类生成器** — 复刻上游 GenerateNewFeaturesTask，按分类自动生成功能入口。

### 2026-08-23

- **📵 隐私与安全 · 禁止主页下滑（v244.7 → v246，最终回退干净版）**
  - 涉及文件：主页下滑拦截相关 feature
  - 迭代 4 版：① DOWN 直接消费顶部下拉手势（v244.7）→ ② DOWN 放行保顶栏手势 + MOVE 消费拦面板（v244.9）→ ③ 面板拦截改 DOWN 消费 + 入口模拟手势（v245.1）→ ④ v246 回退干净版（无 DexKit，onEnable 空 hook）。因拦截手势影响顶栏/搜索框滑动体验，保留基础实现。
- **🎨 界面美化 · 微信进程弹窗改系统 AlertDialog** — AppCompat 主题冲突改用系统 AlertDialog。
- **🛠️ 构建/CI · verCode 防撞号 + .gitignore 清理** — verCode 主号×1000+次号（单段补×1000），支持 `V244.1-100` 序列；APK/构建日志/备份/调试临时文件不入库。

### 📊 变更对照表（按模块分组）

> 与上文详细条目一一对应，快速浏览每个功能改了什么。

#### 💬 聊天增强

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 09-02 | **归拢 @所有人 显示 [@全体]** | `atCount` bit24（0x01000000）权威标志 + 文本关键词双重判定；聚合判断（任一未读成员命中即显示），不再误判 [有人@我] | `ConversationAggregation.kt` |
| 09-02 | **免打扰未读计入文件夹角标** | 免打扰会话 `unReadMuteCount>0` 直接计入 `mutedUnread`（此前 unread==0 分支永不进入导致漏统计），角标正确显示小圆点 | `ConversationAggregation.kt` |
| 08-31 | **归拢摘要颜色设置完善** | 新增独立配色设置项（弹窗内实时配色，改动即生效）；文件夹标题染色独立开关；设置搜索/分类过滤颜色项 | `ConversationAggregationColors.kt`、`ConversationAggregation.kt`、`FeaturesPager.kt` |
| 08-31 | **学校通知打开企业会话页** | 归拢文件夹内「学校通知」行点击打开 `EnterpriseConversationUI`（与首页行为一致） | `ConversationAggregation.kt` |
| 08-31 | **免打扰群聊角标闪现修复** | `parseChatRoomNotify` 返回 null（lvbuff 缺失/解析失败）时保守视为免打扰，不再闪现红色数字角标 | `ConversationAggregation.kt` |
| 08-30 | **归拢文件夹修复** | 免打扰群聊角标（unReadMuteCount 权威兜底 lvbuff 解析失败）+ @所有人识别扩展（全部人/全员）+ [@全体] 标签上色修复 + 已读后消失（补未读条件，与[有人@我]一致） | `ConversationAggregation.kt` |
| 08-30 | **通知头像增强** | 头像缓存目录 v3（小圆角样式）+ 单聊首条同步读盘立即显示 + 群聊昵称 LIKE 模糊兜底匹配 | `NotificationsEvolved.kt` |
| 08-29 | 归拢文件夹长按菜单 + 摘要标签 | 文件夹容器长按菜单修复（menuContext 取 anchor View）+ `[全体]`→`[@全体]` + @所有人识别增强 | `ConversationAggregation.kt` |
| 08-28 | 自动同意好友申请（完整打通） | 双路径捕获 + 属性 XML 解析 + `NetSceneVerifyUser` opcode=3 接受 + 去重 key 改 `encryptUsername`（ticket 每次插入都变）+ 欢迎语单发 | `AutoAcceptFriendRequests.kt` |
| 08-27 | 归拢染色 5 色可配置 | 标题/提及/聊天数/自己/成员括号 5 色取色器可调（默认橙/蓝/黄/深灰/浅灰），标题/自己/成员括号可开关，暗色自动提亮 | `ConversationAggregation.kt` |
| 08-27 | 切换账号归拢隔离 | 配置按账号分文件 + storage 实时化 + db 切换重载对账，各账号互不串扰 | `ConversationAggregation.kt`、`WeDatabaseApi.kt` |
| 08-27 | 自动同意好友申请 | insert hook 覆盖 framework + WCDB compat/database 三类，WCDB 下生效 | `WeDatabaseListenerApi.kt`、`AutoAcceptFriendRequests.kt` |
| 08-27 | 选择器排序加速 | 按联系人 id IN 限定查询（批量取最近消息时间）加速 | `ContactSelectors.kt` |
| 08-26 | 聊天工具栏 | 相册注入显示 / 收藏走 `FavoriteIndexUI` / 点击动态定位 / 按实际格子显示 | `ChatToolbar.kt` |
| 08-26 | 联系人/群聊选择器 | 默认按最近消息时间新-旧排序，DB 未就绪轮询重试 | `ContactSelectors.kt` |
| 08-25 | 群聊归拢摘要 | `[N个聊天]` 黄、`[有人@我]`/`[@全体]` 蓝（hook `setText` 注入 Spannable） | `ConversationAggregation.kt` |
| 更早 | 群聊归拢@提醒 | 被@时摘要显示「有人@我」（后因稳定性回退，保留归拢基础功能） | `ConversationAggregation.kt` |
| 更早 | 消息多选 | DexKit 适配 8.0.77 + allowFailure/placeholder 降级 | `RemoveMessageSelectionLimit.kt` |
| 更早 | 自动同意好友申请 | hook 目标改构造器（`p3.<init>` 接受入口）（已被 08-28 完整版取代） | 好友申请 feature |
| 更早 | 预见性返回动画 | hook 回调 null 保护 + 降级 + 字段查找容错 | 返回动画 feature |
#### 💰 红包与支付

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-27 | **私聊红包可领** | 恢复红包原始实现允许领取私聊红包；分裂群组假红包仅假群注入 key_type | 红包 feature |

#### 🔔 通知

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-27 | **通知头像（圆角矩形）** | 25% 半径圆角裁剪（微信默认样式）；磁盘缓存/本地路径/CDN 兜底 | `NotificationsEvolved.kt` |
| 08-25 | **新消息通知** | 头像缓存+异步预取 / 同会话合并 / cancel 转换 id 清空 history | 通知相关 feature |

#### 📞 联系人与群组

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-26 | **本地好友头像（归拢）** | hook `setImageDrawable` / 文件名时间戳+UUID / 清残留 tag / `onDisable` 可卸载 | `CustomLocalFriendAvatars.kt` |
| 08-25 | **本地好友头像持久化** | `content://` 复制到私有目录存 `file://` + 旧头像自动迁移 | `CustomLocalFriendAvatars.kt` |

#### 🎨 界面美化

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-29 | **隐藏对话列表分割线** | 会话页线 View GONE 锁定 + 归拢文件夹 9-patch 背景替换（深色取灰/亮色白）+ 搜索页 clipBounds 裁剪，按 Activity 限定不误伤其他页 | `HideConversationListDividers.kt` |
| 08-27 | **品牌名统一 WCXLC** | 主页/微信内入口/侧边栏/通知等文案统一 WCXLC（`BuildConfig.TAG` + 9 处硬编码） | `BuildConfig.TAG` 等 |
| 08-26 | **桌面图标重制** | 去白底 + 红色 `#E53935` 自适应背景 png（5 档 DPI） | `res/mipmap-*` |
| 08-25 | **侧边栏修复** | 入口可见性轮询 / 挂载条件增强 / 离开改隐藏 / LauncherUI 兼容 | `HomeSidePanelFeature.kt` |
| 08-25 | **莫奈引擎防护** | 主题色解析 try-catch，设备不支持动态取色回退默认色 | `ThemeStore.kt` |
| 08-24 | **侧边栏头像加载提速** | 解码缩小 192px + 缓存缩小图 + 等待 4s + 重试 3 次，秒级加载 | `HomeSidePanelFeature.kt` |
| 08-24 | **天气卡片** | 识别 `current` 字段 / 湿度风速分行 / 占位紧凑 | `HomeSidePanelFeature.kt` |
| 08-24 | **fork 品牌化** | WCXLC 品牌 + LC 版本后缀（Kimjaejiang/WCXLC） | 多处 |
| 08-24 | **v245 UI 对齐** | 整体替换 UI（69 文件）+ 新增侧边栏与主题商城 | UI 层 69 文件 |

#### 🛠️ 构建/CI

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-29 | **APK 体积优化 -37%** | 移除 Compose 全量 keep，R8 裁剪未用 Compose 代码：35.4MB → 22.4MB | `proguard-rules.pro` |
| 08-27 | **Maven 构建源** | 阿里云镜像置前 + 移除 GitHub Packages + CI 探测错误改纯文本 | `settings.gradle.kts` |
| 08-27 | **CI 海外镜像 502** | CI 设 `WCX_USE_ALIYUN=false` 直连官方源，本地默认镜像不受影响 | `settings.gradle.kts`、`ci.yml` |
| 08-27 | **模块内更新入口** | 首页「有更新」点击确认后模块内自动下载安装 | `HomePager.kt` |
| 08-27 | **本地打包 so** | 本地构建打包 `libwekit_native.so` 随 APK，与 CI 行为一致 | `build.gradle.kts` |
| 08-27 | **模块内更新安装加固** | 兜底匹配 APK 命名 / `releaseTag` 文件名 / `content://` 转 cacheDir + 自身 FileProvider | `AppUpdater.kt` |
| 08-26 | **模块内更新下载** | 兼容带 ABI 与不带 ABI 两种 APK 命名 | `AppUpdater.kt` |
| 08-26 | **模块内更新版本检测** | 12 位时间戳 tag 解析 / `ver.txt` 随 artifact 上传 | `AppUpdater.kt` |
| 08-24 | **native/R8/CI 修复** | cc 编译 / versioned target / R8 保留 compose 类 / Gradle IPv4 优先 | 构建配置 |

#### 💾 数据与配置

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-27 | **配置存储迁移** | 数据目录固定 `WCXLC`，首次访问自动合并旧 `WCX/`（不删旧、同名留新） | `KnownPaths.kt` 等 |
| 08-24 | **版本号对齐 fork** | v244.12 → v252.1（本地 verCode 252001，可被 CI 自动发布覆盖升级） | `app/build.gradle.kts` |

#### 🔧 兼容适配

| 日期 | 功能 | 变更说明 | 涉及文件 |
|---|---|---|---|
| 08-27 | **适配微信版本** | 推荐 8.0.76~8.0.77 / 维护 8.0.69~8.0.75 / 低版本 <8.0.69 | `MainActivity.kt` |
| 08-26 | **8.0.76 Dex 兼容** | HideContacts voip 双路径 allowMultiple + WeMomentsApi 朋友圈视频 allowFailure 降级 | `HideContacts.kt`、`WeMomentsApi.kt` |
| 08-24 | **8.0.77 通话栈加固** | PipVoip 27 处 dex 解析批量 allowFailure + 回调 null 保护 + 懒加载容错 | PipVoip 通话栈 |
| 08-24 | **禁止主页下滑** | v244.7→v246 迭代 4 版后回退干净版（拦截手势影响顶栏滑动体验） | 主页下滑 feature |

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
- **自动同意好友申请**：收到好友申请自动通过，支持延时/随机延时、欢迎语、黑名单（8.0.77 完整支持）
- **Markdown 渲染**：支持 Markdown 格式消息渲染
- **消息复读**：一键复读群消息
- **左划引用消息**：左划消息快速引用回复
- **贴纸/语音保存到本地**：一键保存到手机存储
- **虚拟视频通话**：自定义视频通话画面

### 🎨 界面美化
- **隐藏对话列表分割线**：隐藏会话列表/归拢文件夹/搜索页的分割线，界面更清爽
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
