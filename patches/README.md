# 适配补丁（patches/）

这里的文件由用户的模块**直接下载**（`PatchDownloader`），用来在微信版本更新后
跳过耗时的 DexKit 全量扫描。

## 文件命名

```
patches/wx<微信versionCode>.json        补丁本体
patches/wx<微信versionCode>.json.sig    分离签名（必需）
```

例如微信 `8.0.78`（versionCode `3180`）对应 `wx3180.json` + `wx3180.json.sig`。

**文件名只含微信版本，不含模块版本** —— 补丁跟的是微信版本，同一个微信版本
永远只有一份补丁，不会因模块迭代而堆积。

## 签名是必需的

`PatchStore` 会**强制校验**签名，没有 `.sig` 或验不过一律拒绝（回退本地解析）。

原因：补丁决定了每个功能 **hook 微信的哪个方法**。锚点指向哪儿就 hook 哪儿，
所以「它只是数据」并不构成安全边界。签名把信任根从「HTTPS 传输通道」
移到了离线保管的私钥上 —— 即使仓库被攻破或 CDN 被劫持，攻击者也签不出
一份能被接受的补丁。

## 怎么签发

### 推荐：用一键脚本

```bash
bash tools/publish-patch.sh            # 从设备拉取、改名、签名、暂存
bash tools/publish-patch.sh --dry-run  # 只验证，不写 patches/、不 git add
```

脚本会依次做：前置检查（adb/JDK21/私钥）→ `adb pull` 真机导出的
`adapt-patch.json` → 读出版本号并核对 → 落位成 `patches/wx<ver>.json` →
签名并自检 → `git add`。

**为什么值得用脚本**：改名和签名这两步错起来都不报错 ——
名字错了用户拉不到（静默回落全量解析），签名漏了补丁被直接拒绝。
这两处不该靠人记。

### 手工签发

```bash
# 1. 编译签发工具（只需一次）
cd tools/sign-patch
javac -d . SignPatch.java PatchPublicKey.java PatchMeta.java

# 2. 对补丁签名（私钥路径改成你自己的）
java -cp . SignPatch "D:\MonkeyCode\_keystore\patch-signing.key" ..\..\patches\wx3180.json

# 3. 提交本体与签名
git add patches/wx3180.json patches/wx3180.json.sig
```

工具会在签完后**自动用模块内置公钥验一遍**，不配对会直接报错退出 ——
避免发出去才发现用户全都用不了。

`PatchMeta` 只读元信息（`java -cp . PatchMeta <补丁.json>` →
输出 `<min> <max> <功能数>`），供脚本判断版本，不参与签名。

## 私钥

`D:\MonkeyCode\_keystore\patch-signing.key` —— **离线保管，绝不入库**。

- 支持 **RSA-2048 / SHA256withRSA**
- **不要**换成 Ed25519：Android 不支持它（实测 Android 16 上
  `Signature.getInstance("Ed25519")` 抛 `NoSuchAlgorithmException`）
- 泄露 = 签名机制失效，需立刻重新生成密钥对并发布新模块
- 丢失 = 重新生成密钥对 + 发布新模块（用户装上即恢复，无数据损失）

模块内置的公钥在 `app/src/main/java/com/Johnny/wcx/dynamic/patch/PatchSignature.kt`
的 `PUBLIC_KEY_X509_B64` —— **换密钥时这里和 `tools/sign-patch/PatchPublicKey.java`
两处都要改**。

## 补丁格式

```json
{
  "schema": 1,
  "moduleVersionCode": 3538260,
  "wxVersionRange": { "min": 3180, "max": 3180 },
  "createdAt": 1789982199000,
  "features": {
    "功能名": {
      "methodHash": "…",
      "anchors": { "委托key": "Lcom/tencent/mm/xxx;->a()Z" }
    }
  }
}
```

由模块内的「导出适配补丁」在**真机**上生成 —— 补丁里存的是**解析结果**
（真实类名），这个值只有 DexKit 真扫过微信的 dex 才有，电脑上算不出来。

因为每份补丁都绑定了具体的微信版本区间，微信升级后旧补丁会被
`PatchStore` 自动忽略（不是失败，是正常回落本地解析）。

---

# 两种「更新」走的是两条完全不同的路

这是最容易搞混的地方，搞混的代价是「推了代码但用户拿不到」。

| | 模块更新（改代码 / 修 bug） | 微信更新（如 8078 → 8079） |
|---|---|---|
| 载体 | **GitHub Release 里的 APK** | **`patches/wx8079.json`** |
| 拉取方式 | `api.github.com/.../releases/latest` | jsDelivr / raw，读**分支里的文件** |
| 用户动作 | 模块内点「检查更新」 | 什么都不用做 |
| 频率 | 你发版时 | 微信升级后补一次 |
| **需要发新 APK 吗** | 需要 | **不需要** |

## ⚠️ 改代码后只 `git push` 是没用的

`AppUpdater` 读的是 **Releases API**，不是仓库分支。代码推上去了，
用户那边点「检查更新」什么也看不到 —— 必须在 GitHub 上**发 Release**
并上传 APK。

APK 路径：`app/build/outputs/apk/<flavor>/release/app-<flavor>-release.apk`

Release 附件命名（`selectApkUrl` 按顺序找，命中即停）：

1. `app-<flavor>-<abi>-release.apk` —— 如 `app-standard-arm64-v8a-release.apk`（有 ABI splits 时）
2. `app-<flavor>-release.apk` —— 如 **`app-standard-release.apk`**（当前构建的产物名，最常用）
3. `*universal-release.apk`
4. 兜底：任意匹配 `app-.*-release\.apk`

当前 flavor 是 `standard`，所以直接传 `app-standard-release.apk` 即可。

## 微信升级后怎么补

```bash
# 1. 换到装了新微信版本的机器，装好模块，正常启动一次
#    （模块会自动导出 adapt-patch.json，无需手动操作）

# 2. 一条命令搞定拉取 + 改名 + 签名 + 暂存
bash tools/publish-patch.sh

# 3. 提交推送
git commit -m "patch(wx8079): 适配补丁"
git push origin master
```

推送后用户**下次启动**即可拉到（补丁在后台线程下载，本次启动不等它）。

## 为什么补丁要精确绑定单个微信版本

`wxVersionRange` 写的是 `{"min": 3180, "max": 3180}` 这样的**精确区间**，
不是「3180 以上都能用」。

微信换版本后混淆类名会**整体重排**，旧版本解析出的锚点大概率指向错误甚至
不存在的方法。**拿旧补丁硬套新版本，比没有补丁更危险** ——
后者只是慢一次，前者会让 hook 装到错误的位置上。

所以：新微信版本 = 新导出一份补丁，不要图省事复用旧的。

