#!/usr/bin/env bash
# 一键发补丁：真机导出 -> 取回 -> 改名 -> 签名 -> 自检 -> 暂存
#
# 用法：
#   ./publish-patch.sh              # 用设备上的 adapt-patch.json
#   ./publish-patch.sh 8079         # 覆盖微信 versionCode（默认从导出文件里读）
#   ./publish-patch.sh --dry-run    # 只做检查与签名，不 git add
#
# 为什么需要脚本：每次微信小版本更新都要手敲 4 步（pull/改名/签名/add），
# 且「改名」和「签名」这两步最容易错 —— 名字错了用户拉不到（静默回落全量解析，
# 不会有任何报错），签名漏了补丁直接被拒。这两处都不应该靠人记。
#
# 依赖：
#   - 设备已连（adb，注意本机 adb 不在 PATH）
#   - 私钥 D:\MonkeyCode\_keystore\patch-signing.key
#   - JDK 21（PATH 里的 java 是 JDK 8，不能用）
#   - tools/sign-patch 已编译（本脚本会自动编译）

set -euo pipefail

# ── 路径常量 ────────────────────────────────────────────────────────────────
ADB="/c/Users/Dell/AppData/Local/Android/Sdk/platform-tools/adb.exe"
JDK="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin"
PRIVATE_KEY='D:\MonkeyCode\_keystore\patch-signing.key'
DEVICE_DIR="/sdcard/Android/data/com.tencent.mm/WCXLC"
DEVICE_EXPORT="$DEVICE_DIR/adapt-patch.json"

# 脚本可能从任意目录调用，先锚定到仓库根（本文件所在目录的上一级）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

SIGN_DIR="$REPO_ROOT/tools/sign-patch"
PATCH_DIR="$REPO_ROOT/patches"
STAGE_DIR="$REPO_ROOT/.patch-stage"

DRY_RUN=false
WX_VER=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) WX_VER="$arg" ;;
  esac
done

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
fail() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
ok()   { printf '\033[32m✓ %s\033[0m\n' "$*"; }

# ── 1. 前置检查（早失败，别等到签名完才发现没私钥）────────────────────────
say "前置检查"
[ -x "$ADB" ] || fail "adb 不在 $ADB"
[ -x "$JDK/java" ] || fail "JDK 21 不在 $JDK（PATH 里的 java 是 JDK 8，不能用）"
[ -f "$PRIVATE_KEY" ] || fail "私钥不存在：$PRIVATE_KEY"
ok "adb / JDK21 / 私钥 均在位"

device_count=$("$ADB" devices | grep -cw "device" || true)
[ "$device_count" -ge 1 ] || fail "没有连接的设备（USB 连接不稳，重插再试）"
ok "设备已连接（$device_count 台）"

# ── 2. 拉取真机导出的锚点真值 ──────────────────────────────────────────────
say "从设备拉取 adapt-patch.json"
mkdir -p "$STAGE_DIR"

# adb.exe 是原生 Windows 程序，喂给它 MSYS 风格的 /d/... 路径会报
# "cannot create file/directory" —— 必须转成 D:\... 形式。
# cygpath 在部分 MSYS 安装里缺失，所以用 sed 手转，不依赖它。
win_path() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$p"
  else printf '%s' "$p" | sed -E 's|^/([a-zA-Z])/|\1:/|' | tr '/' '\\'
  fi
}

# 导出文件属主是应用，普通 adb pull 读得到；读不到才需要 su 转存
if ! "$ADB" pull "$DEVICE_EXPORT" "$(win_path "$STAGE_DIR/adapt-patch.json")" 2>/dev/null; then
  "$ADB" shell "su -c 'cp $DEVICE_EXPORT /data/local/tmp/adapt-patch.json && chmod 644 /data/local/tmp/adapt-patch.json'" \
    || fail "设备上没有 $DEVICE_EXPORT —— 先在模块里点一次「导出适配」"
  "$ADB" pull /data/local/tmp/adapt-patch.json "$(win_path "$STAGE_DIR/adapt-patch.json")" || fail "拉取失败"
fi
[ -s "$STAGE_DIR/adapt-patch.json" ] || fail "导出文件为空"
ok "已取回（$(wc -c < "$STAGE_DIR/adapt-patch.json") 字节）"

# ── 3. 解析并核对微信版本 ──────────────────────────────────────────────────
say "解析补丁元信息"
# 用 PatchMeta 而非 python：本机没有 python（只有 Microsoft Store 的占位符），
# 而且签发链路本来就依赖 JDK，不再额外引入一个运行时依赖。
cd "$SIGN_DIR"
if [ ! -f PatchMeta.class ]; then
  "$JDK/javac" -d . PatchMeta.java || fail "PatchMeta 编译失败"
fi
cd "$REPO_ROOT"

read -r META_MIN META_MAX META_FEATURES \
  <<<"$("$JDK/java" -cp "$(win_path "$SIGN_DIR")" PatchMeta "$(win_path "$STAGE_DIR/adapt-patch.json")")" \
  || fail "读取元信息失败"

[ "$META_MIN" != "-1" ] || fail "导出文件缺 wxVersionRange，无法确定适用版本"
[ "$META_FEATURES" -gt 0 ] || fail "补丁里没有任何功能，导出时可能还没解析完"

# 补丁必须精确绑定一个微信版本：混淆类名跨版本会重排，
# 拿 8078 的锚点套 8079 比没有补丁更危险，所以这里要拦住 min!=max 的情况
if [ "$META_MIN" != "$META_MAX" ]; then
  printf '\033[33m！警告：版本区间是 [%s,%s] 而非单版本。\033[0m\n' "$META_MIN" "$META_MAX"
  printf '  跨版本区间意味着你认为锚点在这些版本间通用 —— 确认无误再继续。\n'
fi

if [ -n "$WX_VER" ] && [ "$WX_VER" != "$META_MAX" ]; then
  printf '\033[33m！手动指定的 %s 与导出文件的 %s 不一致，采用手动值。\033[0m\n' "$WX_VER" "$META_MAX"
else
  WX_VER="$META_MAX"
fi

ok "微信 versionCode = $WX_VER，功能数 = $META_FEATURES"

# ── 4. 落位为 patches/wx<ver>.json ─────────────────────────────────────────
say "写入 patches/wx$WX_VER.json"
mkdir -p "$PATCH_DIR"
TARGET="$PATCH_DIR/wx$WX_VER.json"

if [ -f "$TARGET" ] && ! cmp -s "$STAGE_DIR/adapt-patch.json" "$TARGET"; then
  printf '\033[33m！patches/wx%s.json 已存在且内容不同，将被覆盖。\033[0m\n' "$WX_VER"
  printf '  同一微信版本只应有一份补丁 —— 确认这次导出的才是新的。\n'
fi

# dry-run 必须是只读的。
# 踩过：早先的版本在 dry-run 下照样把文件写进 patches/，
# 于是「只看一眼」的操作会污染工作区并改动已提交的补丁与签名 ——
# 与「dry-run 安全」的直觉完全相反。现在 dry-run 只写到暂存目录。
if [ "$DRY_RUN" = true ]; then
  cp "$STAGE_DIR/adapt-patch.json" "$STAGE_DIR/wx$WX_VER.json"
  TARGET="$STAGE_DIR/wx$WX_VER.json"
  printf '\033[33m（dry-run：仅写到 %s，不动 patches/）\033[0m\n' "$TARGET"
else
  cp "$STAGE_DIR/adapt-patch.json" "$TARGET"
fi
ok "已写入 $TARGET"

# ── 5. 签名 ────────────────────────────────────────────────────────────────
say "签名（RSA-2048 / SHA256withRSA）"
cd "$SIGN_DIR"
if [ ! -f SignPatch.class ]; then
  "$JDK/javac" -d . SignPatch.java PatchPublicKey.java || fail "签发工具编译失败"
  ok "签发工具已编译"
fi

# SignPatch 内部会用模块内置公钥自检，不配对直接非零退出
"$JDK/java" -cp "$(win_path "$SIGN_DIR")" SignPatch "$PRIVATE_KEY" "$(win_path "$TARGET")" || fail "签名失败（自检未通过）"
[ -f "$TARGET.sig" ] || fail "没有生成 .sig"
ok "已生成 $(basename "$TARGET").sig"

cd "$REPO_ROOT"

# ── 6. 收尾 ────────────────────────────────────────────────────────────────
say "结果"
ls -la "$PATCH_DIR/wx$WX_VER.json" "$PATCH_DIR/wx$WX_VER.json.sig"

if [ "$DRY_RUN" = true ]; then
  printf '\n\033[33m--dry-run：未执行 git add\033[0m\n'
  exit 0
fi

git add "$TARGET" "$TARGET.sig"
ok "已暂存，接着执行："
printf '\n  git commit -m "patch(wx%s): 适配补丁"\n  git push origin master\n\n' "$WX_VER"
printf '推送后用户下次启动即可拉到。注意：\n'
printf '  - 补丁跟**微信版本**走，不发新 APK 也能生效\n'
printf '  - 模块自身的代码修复必须发 GitHub Release，push 分支没用\n'
