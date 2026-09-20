# 8.0.78 锚点验证工具

把模块的 DEX 锚点与某个微信版本的字符串表逐条比对，找出可能失效的锚点。

## 背景

模块有 744 个 `dexMethod`/`dexClass` 锚点，其中 173 处标了 `allowFailure = true`——
锚点没命中时静默降级，功能失效但界面上看不出来。微信升版本后，
需要一种方式快速找出「哪些锚点在新版里已经不存在」。

## 做法

1. 从目标微信 APK 提取全部 DEX 的 string_ids 字符串表
2. 从模块源码提取所有 `usingEqStrings` / `usingStrings` 的字面量
3. 精确比对，输出未命中的锚点

## 用法

### 1. 取微信 APK

```bash
adb shell pm path com.tencent.mm
adb pull /data/app/.../base.apk base.apk
unzip -o -q base.apk "classes*.dex" -d dex
```

### 2. 提取微信字符串表

`DexStrings.java` 直接解析 DEX 的 `string_ids` 段（权威字符串全集）。

```bash
javac -encoding UTF-8 -d . DexStrings.java
java -cp . DexStrings dex wxstrings.txt
```

> 为什么不用 `dexdump`：`dexdump -d` 只能看到代码里被引用的字符串，
> 而锚点可能锚在任何常量池位置。`string_ids` 才是全集。

### 3. 提取模块锚点

仓库根目录执行：

```bash
grep -rhoP 'using(?:Eq)?Strings\(\s*\K("[^"]*"(?:\s*,\s*"[^"]*")*)' \
  --include=*.kt app/src/main/java/com/Johnny/wcx/ \
  | grep -oP '"[^"]*"' | sed 's/^"//; s/"$//' | sort -u > anchors.txt
```

### 4. 比对

```bash
while IFS= read -r a; do
  [ -z "$a" ] && continue
  grep -qxF "$a" wxstrings.txt && echo "$a" >> hit.txt || echo "$a" >> miss.txt
done < anchors.txt
```

## 2026-09-20 实测结果（微信 8.0.78 正式版，versionCode 3180）

- 微信字符串表：17 个 dex，857,369 条唯一字符串
- 模块锚点：560 条
- **命中 526 / 未命中 33**

未命中清单见 `anchors-missing.txt`，按功能归类的解读见 README「适配版本」章。

## 局限

本工具只能判定「字符串是否存在」，**判不了语义是否等价**。

- 命中不代表行为正确：日志串可能保留，但所在方法已重构
- 未命中也不一定致命：若该锚点有 `allowFailure` 或多候选兜底，可能仍能工作

语义等价性需要运行时验证（观察 hook 是否真正挂上、功能是否生效）。
