# WCXLC Release Notes

> 复制下面 `---` 之间的内容到 GitHub Release 正文。
> 版本号建议用 `260922290000`（与本机已构建的 versionName 一致）。

---

## 本次更新


**适配补丁链路正式上线。** 微信版本更新后，模块可直接下载适配补丁，
跳过耗时的全量 DexKit 扫描 —— 不用等模块发新版本。

### 新增：云端适配补丁

- 补丁**强制签名校验**（RSA-2048）。公钥内置在模块里，私钥离线保管。
  校验不过一律拒绝并回退本地解析，没有"验不了就放行"的路径。
- 补丁跟**微信版本**走，不跟模块版本走 —— 同一微信版本永远只有一份补丁。

### 修复

- **修复模块更新后误清全部 DEX 缓存**
  此前升级模块会被误判成"微信版本变化"，清空全部 148 个功能的缓存并弹
  「开始适配」。现在两条路径分开：微信版本变才清缓存，模块版本变不清。

- **修复补丁加载后功能仍报「未生效」**
  补丁命中后没有写缓存，导致部分功能每次启动都要重新补丁，并始终显示为
  未生效。现在补丁命中即落盘。

- **修复补丁下载地址错误**（jsDelivr 分支名写错，导致补丁一直拉不到）

- **修复若干 8.0.78 锚点失效**
  左划对话菜单、朋友圈菜单、自动同意好友申请、强制平板模式。
  另修复 `DexMethodDelegate` 两处恒假判据（影响面较大）。

- **修复导出适配真值时锚点表不完整**（合并缓存与内存两个来源）

### 已知问题

- **5 个锚点仍未命中**（3 个功能当前就在空转）：
  - 消息发送服务 `ctorNetSceneUploadMsgImg`
  - 会话列表 View 绑定监听服务 `methodLegacyGetView`
  - 朋友圈菜单增强扩展 3 个 `methodImproveOnItemSelected*`

  这些是微信 8.0.78 上真实失效的锚点，**不影响其他功能**，
  相关子功能可能静默不生效。详见仓库内 `锚点失效清单-8.0.78.md`。

---

## 安装

下载下方 APK 覆盖安装即可，**无需卸载**（不会丢数据）。

- 支持微信 **8.0.78**（versionCode 3180）
- 其他微信版本请以实际适配情况为准

## 完整变更

本次自上个发布以来共 17 个提交：

**新增**
- 云端适配补丁读取链路（本地读取 → 网络下发 → 强制签名）
- 按微信版本分文件的适配层

**修复**
- 模块更新误清 DEX 缓存
- 补丁命中后不写缓存
- 补丁下载分支名错误
- 补丁拉取阻塞主线程（NetworkOnMainThreadException）
- 导出锚点表合并双来源
- DexMethodDelegate 两处恒假判据
- 强制平板模式锚点（`isP8Pad` 分支）
- 左划对话菜单适配器锚点
- 朋友圈菜单锚点包限制
- 自动同意好友申请备用锚点
- 解除消息多选限制前置守卫
- 群通话画中画崩溃
- versionCode 跨天回退导致覆盖安装被拒

**其他**
- 「开关打开了但功能没生效」现在会在日志中暴露

---

# 怎么发这个 Release

```bash
# 1. 编译（版本号靠 VER 环境变量指定，与 changelog 标题保持一致）
cd /d/MonkeyCode/WXPRO
export WEKIT_KEYSTORE_FILE='D:\MonkeyCode\_keystore\wcx-release.jks'
export WEKIT_KEYSTORE_PASSWORD=102001 WEKIT_KEY_ALIAS=a WEKIT_KEY_PASSWORD=102001
export VER=260922290000
./gradlew.bat :app:assembleStandardRelease \
  -x lintVitalStandardRelease -x lintVitalReportStandardRelease \
  --offline --parallel --max-workers=8

# 2. 产物
#    app/build/outputs/apk/standard/release/app-standard-release.apk

# 3. 打 tag（tag 名 = versionName，与历史 tag 口径一致）
git tag 260922290000
git push origin 260922290000

# 4. 在 GitHub 上发 Release
#    - 选 tag 260922290000
#    - 标题：260922290000
#    - 正文：粘贴上面 --- 之间的内容
#    - 附件：app-standard-release.apk
```

## ⚠️ 两个必须注意的点

**1. 附件名必须是 `app-standard-release.apk`**

`AppUpdater.selectApkUrl` 按顺序匹配，第 2 顺位就是 `app-<flavor>-release.apk`
（当前 flavor = `standard`）。改名成别的会被降级到第 4 顺位的兜底正则，
虽然仍能匹配，但没必要冒险。

**2. 只 `git push` 代码是不够的**

`AppUpdater` 读的是 **Releases API**（`releases/latest`），
不是仓库分支 —— 代码推上去了但没发 Release，用户点「检查更新」
什么也看不到。

## 发布后

用户点「检查更新」即可拉到。versionCode 单调递增
（自 2020-01-01 起的分钟数），所以可以直接覆盖安装，无需卸载、不丢数据。

