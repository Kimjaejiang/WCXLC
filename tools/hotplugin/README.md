# 热更新插件发布工具

把插件源码编译、签名、打包成可发布的 `plugin-<version>.apk`，并生成配套的
`hot-update.json`。

## 背景

模块采用「稳定壳 + 外部插件 APK」的热更新架构：

- **壳**：已安装的模块 APK，负责启动、UI、类加载，不随功能更新重装。
- **插件**：`plugin-x.y.z.apk`，承载具体功能逻辑。壳在启动时用
  `DexClassLoader` 从微信数据目录加载它，更新后只需重启微信。

因此插件是**独立打包、独立签名**的，不进主 APK。

## 用法

```cmd
publish-plugin.bat <版本号> <更新说明>
```

例：

```cmd
publish-plugin.bat 1.0.3 "修复了 xxx"
```

产出在 `release/`：

| 文件 | 传到哪里 | tag |
|---|---|---|
| `plugin-<version>.apk` | 新建的 Release | `v<version>` |
| `hot-update.json` | 固定 Release（覆盖资产） | `hot-latest` |

**分两处发布**，原因如下。

壳里写死的 manifest 地址是：

```
releases/download/hot-latest/hot-update.json
```

用的是**固定 tag** `hot-latest`，不是 `releases/latest` 别名。因为本仓的
Release 里还有模块整包（时间戳 tag），而 `latest` 指向「最近发布的那个」——
发一次模块整包就会把 `latest` 抢走，热更新地址随之 404。固定 tag 永远
指向插件，两者互不干扰。

APK 则放在**版本 tag**（`v1.0.3`）下：如果 APK 也塞进 `hot-latest`，
每次发布都会覆盖上一版的二进制，旧版本再也下不到，出问题无法回滚。

所以每次发插件：

1. 新建 Release，tag 填 `v<版本号>`，上传 `plugin-<version>.apk`
2. 在 `hot-latest` 这个 Release 里**替换** `hot-update.json` 资产
   （首次需新建，tag 填 `hot-latest`）

两步都不需要改壳里的代码。

### 发布时务必取消 "Set as the latest release"

这是最容易踩的坑。GitHub 新建 Release 时**默认勾选** "Set as the latest
release"，必须手动取消。

原因：模块自身的整包更新（`AppUpdater.checkForUpdate`）读的是
`api.github.com/.../releases/latest`。一旦插件 Release 被标成 latest：

- 该接口返回插件包，而不是模块整包
- tag `hot-latest` / `v1.0.3` 解析不出 12 位时间戳版本号，得 0
- 「检查更新」从此永远认为已是最新，模块再也收不到整包更新

注意 ``hot-latest`` 作为 tag **名字**并不会自动获得 latest 标记，只取决于
发布时那个勾选框。

## 依赖

| 依赖 | 说明 |
|---|---|
| JDK | 需要 `JAVA_HOME`，用其中的 `javac` / `jar` |
| Android Build-Tools 36.0.0 | `d8` / `aapt2` / `zipalign` / `apksigner` |
| Android SDK Platform 37 | 提供 `android.jar` |
| 签名密钥 | 默认找 `../../../_keystore/wcx-release.jks` |

前两项默认从 `%LOCALAPPDATA%\Android\Sdk` 找，装 Android Studio 即有。

## 签名密钥

**密钥不入库**（泄露后无法撤回，且插件必须与壳同签名，壳在下载时会校验
`verifySameSignature`，签名不一致直接拒绝安装）。

密钥按以下顺序解析，命中即用：

1. 环境变量 `HOTPLUGIN_KEYSTORE`（`.jks` 的完整路径）
2. `../../../_keystore/wcx-release.jks`（即仓库根目录的上一级）

凭据可用环境变量覆盖，用于自己的密钥：

```cmd
set HOTPLUGIN_KEYSTORE=D:\keys\my.jks
set HOTPLUGIN_KS_PASS=你的store密码
set HOTPLUGIN_KEY_ALIAS=你的别名
set HOTPLUGIN_KEY_PASS=你的key密码
publish-plugin.bat 1.0.3 "说明"
```

## 目录结构

```
apk/AndroidManifest.xml      插件的最小清单（无组件，仅承载 dex）
java/com/Johnny/wcx/hot/     SPI 桩，仅编译期可见，不打进 dex
java/com/Johnny/wcx/hotplugin/   插件实现，会被打进 dex
```

## 两个容易踩的坑

**① SPI 桩绝不能进 dex。**

`java/com/Johnny/wcx/hot/` 下的接口是为了让 `javac` 能解析插件的 import 而存在
的。壳已经提供了这些类型，若插件 dex 里再带一份，插件侧的 `HotPlugin` 与壳侧的
就**不是同一个类**，`as HotPlugin` 会抛 `ClassCastException`。

脚本因此把 SPI 单独编译到 `build-publish/spi/`（只用于 `-classpath`），
只有 `build-publish/classes/` 下的插件类会被 `d8` 打成 dex。

**② `zipalign` 必须在 `apksigner` 之前。**

反过来（先签名再对齐）会重写 zip 中央目录，抹掉 v2/v3 签名块，
产出的 APK 校验失败、装不上。脚本已按正确顺序调用，并在签名后
用 `apksigner verify` 复核，失败会直接报错而不是产出坏包。

## 为什么不用 Gradle

插件工程刻意不进主仓的 `settings.gradle.kts`，也没有自己的 `gradlew`。
只为产出几百 KB 的 dex 引入完整 Gradle 配置不划算，直接用
`javac` + SDK 工具链更直观、依赖更少。
