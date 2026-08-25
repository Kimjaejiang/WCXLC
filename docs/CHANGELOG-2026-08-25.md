# WCX 修改溯源（2026-08-25）

> 对应提交：`ca0959f`、`6f25f55`（均已推送 `origin/master`）
> 对应安装包：build22（`app-legacy-release.apk`，21:10 构建，已安装真机验证）

---

## 1. 归拢摘要染色（`ConversationAggregation.kt`，+878 行）

### 需求
微信会话列表「群聊归拢」文件夹（入口名「群聊归拢」）的摘要行，需对 `[有人@我]`/`[@全体]` 显示蓝色、`[N个聊天]` 显示黄色。

### 技术调研结论（可溯源）
| 方案 | 结果 |
|---|---|
| hook 系统 `TextView.onDraw` | ❌ 微信摘要控件非标准 TextView（`com.tencent.mm.ui.base.NoMeasuredTextView`，extends `X2CView`，非 TextView 子类） |
| hook 系统 `View.draw`（全局） | ❌ 系统 Framework 类 hook 对微信进程无效（实测 `VIEW_DRAW` 0 次触发；`onAttachedToWindow` 也不触发） |
| hook 微信 dex `ConversationListView.onDraw` | ✅ 微信 dex 类 hook 有效，用于 View 树 dump 定位 |
| **最终方案：hook `NoMeasuredTextView.setText`** | ✅ 摘要经 `setText(CharSequence)` 注入但 `getText()` 返回空；hookBefore 替换为 Spannable 上色，微信自绘渲染 span 颜色 |

### 修改点
1. `hookAllTextViewDraw()` 精简为纯定向 hook：
   - `com.tencent.mm.ui.base.NoMeasuredTextView` 全部 `setText` 变体，`hookBeforeDirectly` 命中归拢标记时 `args[0] = tintAggSummary(text)`
2. `isAggSummary(text)`：匹配 `[有人@我]` / `[@全体]` / `CHAT_COUNT_REGEX`（`\[[^\]]*个聊天\]`）
3. `tintAggSummary(text)`：生成 `SpannableString`，`ForegroundColorSpan` 上色（蓝 `0xFF4285F4`、黄 `0xFFFFCC00`）
4. 保留辅助诊断（`dumpTextViews`/`dumpFields`/`drawTintedOverlay` 等作死代码，不再调用）

### 验证
- build20 实测：`NMTV_TINT_SET` 命中 11 次，字段 dump 确认 `mText`/`i` 存完整摘要、`n:TextPaint` 可用
- 真机确认：`[2个聊天]` 黄色、`[有人@我]`/`[@全体]` 蓝色 ✅

---

## 2. 头像持久化修复（`CustomLocalFriendAvatars.kt`，+50 行）

### 需求
自选相册图作为本地好友头像，重启微信后空白。

### 根因
旧版把相册 `content://` URI 直接存设置，微信重启后对相册 `content://` 的读取权限失效。

### 修改点
1. 新增 `persistAvatarFile(uri)`：相册 `content://` 复制到模块私有目录，存 `file://` 路径
2. `onEnable` 自动迁移：`avatarMap` 中 `content://` 前缀项一次性迁移到 `file://`（`createDirsSafe`）
3. placeholder 命中与头像重定向补充日志（`avatar hook[index] placeholder`、`avatar redirect[index] wxId -> redirectedId`）

### 验证
真机重启微信后头像不再空白 ✅（build13+ 连续验证）

---

## 3. 侧边栏修复（`HomeSidePanelFeature.kt`，+347 行）

### 问题
入口按钮/触摸条在微信 Tab 切换（首页 ↔ 通讯录/发现/我）后显示状态错乱。

### 修改点（4 项）
1. **入口可见性轮询**：微信 Tab 切换不产生可 hook 的触摸事件，仅靠 `ACTION_DOWN` 无法及时隐藏非首页入口 → 新增 `Handler` 定时对账（`visibilityPollerRunning`/`lastPollerHome`），离开首页隐藏、回首页恢复
2. **触发按钮挂载条件增强**：`triggerButtonView == null` → `== null || parent == null`（按钮被父容器移除后也能重新挂载）
3. **离开首页 Tab 处理优化**：从「removeAllViews」改为「`visibility = GONE` 隐藏按钮/触摸条 + `removePanelInternal()` 关面板」，保留挂载由轮询恢复
4. **FragmentActivity 兼容**：微信 LauncherUI 非 androidx FragmentActivity（classloader 隔离，`isInstance=false`）无法枚举 Fragment → 改用 `ActionBarOverlayLayout` 可见性识别「微信」Tab（其余 Tab 是 `MultiTaskContainerView` 无 ActionBar），并加 `dumpLauncherDiagnostics`/`dumpTopBar` 启动诊断

---

## 4. MonetEngine 主题色异常防护（`MonetEngine.kt`，ca0959f +108 行、6f25f55 +7 行）

### 问题
部分设备不支持动态取色（`SeedResolver.materialScheme` 抛异常）导致模块崩溃或主题色解析失败。

### 修改点
1. `primaryColor` / `onPrimaryColor` lazy 初始化包 try-catch，异常时回退 `DEFAULT_COLOR`/`-1`（hook 变 no-op，不崩溃）
2. `scheme` lazy：`materialScheme` 调用包 try-catch，记录日志后重抛（保留下游可见失败原因）

### 验证
build22 编译通过；设备正常设备上行为不变。

---

## 5. 通知演进（`NotificationsEvolved.kt`，+97 行）

### 修改点
1. **发送者头像缓存**：`senderAvatarCache = LruCache<String, Icon>(64)`，`HistoryEntry` 增加 `senderKey`（会话|发送者）；`MessagingStyle.addMessage` 用 `Person.Builder().setIcon(...)` 带发送者头像
2. **异步预取**：`prefetchSenderAvatar(convWxId, senderName)`，下一条通知构建时生效
3. **同会话通知合并**：`hookNotifyIdMerge()` hook `NotificationManager.notify(int, Notification)`，同一会话通知 id 统一为会话哈希（通知栏只保留最新一条）

---

## 构建/验证时间线（溯源）

| 构建 | 内容 | 验证结果 |
|---|---|---|
| build16 | View.draw 全局 hook + onAttachedToWindow 验证 | VIEW_DRAW=0，系统类 hook 确认无效 |
| build17 | ConversationListView（微信 dex）hook + View 树 dump | CONV_ATTACH/CONV_DRAW fired，71+ TextView dump |
| build18 | 全 View 树 dump（非 TextView 类名） | 定位 NoMeasuredTextView（852x73，extends X2CView） |
| build19 | NoMeasuredTextView.setText/onDraw hook | setText 捕获 `[2个聊天]犬神志(...)` 完整摘要 |
| build20 | setText hookBefore Spannable 染色 + 字段 dump | `NMTV_TINT_SET` 命中，真机确认变色 ✅ |
| build21 | 精简为纯定向染色 hook | `NMTV tint hook installed`，无失败 |
| build22 | 最终版（含 MonetEngine 防护合并） | 已安装真机，hook 生效（21:12:57） |
