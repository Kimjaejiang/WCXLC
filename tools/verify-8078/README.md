# 8.0.78 锚点验证工具

把模块的 DEX 锚点与某个微信版本的字符串表逐条比对，找出可能失效的锚点。

## 背景

模块有 700+ 个 `dexMethod`/`dexClass` 锚点，其中一百多处标了 `allowFailure = true`——
锚点没命中时静默降级，功能失效但界面上看不出来。微信升版本后，
需要一种方式快速找出「哪些锚点在新版里已经不存在」。

## 做法

1. 从目标微信 APK 提取全部 DEX 的 string_ids 字符串表
2. 从模块源码提取所有 `usingEqStrings` / `usingStrings` 的字面量
3. 比对，输出未命中的锚点

## 用法

### 1. 取微信 APK

```bash
adb shell pm path com.tencent.mm
adb pull /data/app/.../base.apk base.apk
unzip -o -q base.apk "classes*.dex" -d dex
```

> 不要在设备上直接 `grep -a <类名> base.apk` —— APK 是 zip，dex 非明文连续存放，
> 恒返回 0。第一次踩这个坑时查必然存在的 `LauncherUI` 也是 0，才确认方法无效。

### 2. 提取微信字符串表

`DexStrings.java` 直接解析 DEX 的 `string_ids` 段（权威字符串全集）。

```bash
javac -encoding UTF-8 -d . DexStrings.java
java -cp . DexStrings dex wxstrings.txt
```

> 为什么不用 `dexdump`：`dexdump -d` 只能看到代码里被引用的字符串，
> 而锚点可能锚在任何常量池位置。`string_ids` 才是全集。

### 3. 提取模块锚点

**必须用 `ExtractAnchors.pl`，不要用 grep 正则。**

```bash
perl ExtractAnchors.pl $(find app/src/main/java/com/Johnny/wcx -name "*.kt") > anchors.txt
```

早期版本用 `grep -oP '"[^"]*"'` 提取，有两个坑：

- **转义未反转义**：源码写 `"<appmsg appid=\""`，实际值是 `<appmsg appid="`。
  正则会把 `\"` 原样留下，导致这个锚点被误判为「未命中」。
- **`${'$'}` 未还原**：Kotlin 里 `$` 可写作 `${'$'}`，不还原就匹配不到。

### 4. 比对（**必须按「子串」而非「等值」**）

```bash
while IFS= read -r a; do
  [ -z "$a" ] && continue
  grep -qaF "$a" wxstrings.txt && echo "$a" >> hit.txt || echo "$a" >> miss.txt
done < anchors.txt
```

**这一步是早期最大的错误来源。** DexKit 的 `usingStrings` 默认是 `StringMatchType.Contains`
（子串），不是等值。用 `grep -qxF`（等值）会把大量**仍然可用**的锚点误报为失效：

| 锚点 | 8.0.78 实际 | 等值 | 子串 |
|------|------------|------|------|
| `Cannot resolve path or URI` | `Cannot resolve path or URI: ` | 未命中 | **命中** |
| `delChatContact username:` | `delChatContact username:%s  stack:%s` | 未命中 | **命中** |
| `initBackground:` | `initBackground: info:%s bgId:%s` | 未命中 | **命中** |
| `worker thread has not been se` | `worker thread has not been set` | 未命中 | **命中** |

这些都是「日志串加了参数」，锚点其实仍然有效。

## 2026-09-20 实测结果（微信 8.0.78 正式版，versionCode 3180）

- 微信字符串表：17 个 dex，857,369 条唯一字符串
- 模块锚点：**717 条**
- **命中 693 / 未命中 24（96.7% 有效）**

> 早期一轮曾报「560 条 / 未命中 33」，那组数字因上述两个提取 bug 与等值比对口径，
> **同时低估了锚点总数、又高估了失效数**，已废弃。以本文件为准。

未命中清单见 `anchors-missing.txt`，命中清单见 `anchors-hit.txt`，
按功能归类的解读见 README「适配版本」章。

## 判读未命中锚点：三步

**「字符串不存在」不等于「功能失效」。** 实测下来，未命中锚点分三类：

1. **有意保留的旧版 fallback** —— 例如 `AutoAcceptFriendRequests` 同时有
   「新锚点（8.0.76+ 断言日志）」与「旧锚点」，旧的自然失效，属于设计正确。
2. **AND 配对里的次要串** —— `usingStrings(A, B)` 是 **AND** 语义（两者都要满足）。
   任意一串消失，整个 matcher 就失败。典型：`Themes.classSmileyTabAdapter` 的
   `setSelection: %s` 消失，导致整个类定位失败。
3. **真的整体迁移** —— 例如 `MultiTalk` / `ILink` 控制链在 8.0.78 已换成新架构，
   信号是**一批锚点同时消失**，而非零散一两个。

需要再去源码看该锚点**是否有 `allowFailure`**、**使用处是否有 `isPlaceholder` 守卫**，
才能判定后果：

- 有守卫 → 失效即优雅跳过，无需修改
- 无守卫且调用 `.clazz` / `.method` → **会抛异常**。
  若发生在 `onEnable()`，会冒泡到 `BaseFeature.enable()` 的 `runCatching`，
  触发 `unhookAll()` + `isActive = false`：**已装上的 hook 被撤销、后续 hook 从不执行**，
  表现为整个功能静默失效。

## 局限

本工具只能判定「字符串是否存在」，**判不了语义是否等价**。

- 命中不代表行为正确：日志串可能保留，但所在方法已重构
- 未命中也不一定致命：见上方「三步判读」

语义等价性需要运行时验证（观察 hook 是否真正挂上、功能是否生效）。
