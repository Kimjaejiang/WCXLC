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

| 文件 | 用途 |
|---|---|
| `plugin-<version>.apk` | 上传到 GitHub Release |
| `hot-update.json` | 上传到 GitHub Release |

两个文件都要传，且**必须是 latest release** —— 壳读取的是
`releases/latest/download/hot-update.json`，这个地址永久固定，
所以发新版不需要改壳里的代码。

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
