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

```bash
# 1. 编译签发工具（只需一次）
cd tools/sign-patch
javac -d . SignPatch.java PatchPublicKey.java

# 2. 对补丁签名（私钥路径改成你自己的）
java -cp . SignPatch "D:\MonkeyCode\_keystore\patch-signing.key" ..\..\patches\wx3180.json

# 3. 提交本体与签名
git add patches/wx3180.json patches/wx3180.json.sig
```

工具会在签完后**自动用模块内置公钥验一遍**，不配对会直接报错退出 ——
避免发出去才发现用户全都用不了。

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
