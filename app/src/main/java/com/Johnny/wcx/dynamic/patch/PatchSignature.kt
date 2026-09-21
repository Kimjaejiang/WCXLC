package com.Johnny.wcx.dynamic.patch

import com.Johnny.wcx.utils.WeLogger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * 补丁签名校验（RSA-2048 / SHA256withRSA）。
 *
 * ## 为什么需要它
 *
 * 补丁决定了**每个功能去 hook 微信的哪个方法**。在此之前，这份能力唯一的
 * 保底是 HTTPS —— 也就是说：能往 `Kimjaejiang/WCXLC` 仓库推东西的人，
 * 或者能 MITM 掉 jsDelivr 的人，就能让所有用户的模块**把 hook 装到他指定的方法上**。
 *
 * 之前 `PatchDownloader` 里那句「补丁只是数据，不含可执行代码，最多把锚点指向
 * 某个类」是个**错误的安慰**：锚点指向哪儿，DexKit 就 hook 哪儿。把锚点指到
 * 登录、支付、加密相关的方法上，效果和下发代码没有区别。
 *
 * ## 信任根在公钥，不在域名
 *
 * 公钥硬编码在模块里（[PUBLIC_KEY_X509_B64]），私钥离线保管。
 * 这样即使仓库被攻破、CDN 被劫持，攻击者也**签不出**一份能被接受的补丁。
 *
 * 代价是换密钥必须发新版模块 —— 这是对的，公钥本来就该钉死。
 * 不存在「远程轮换公钥」这种设计，那等于把信任根又交回给网络。
 *
 * ## 为什么是 RSA 而不是 Ed25519
 *
 * Ed25519 更短更快，JDK 15+ 原生支持，本来是首选 —— 但**Android 不支持它**。
 * 真机实测（Android 16 / SDK 36）：
 *
 * ```
 * Signature.getInstance("Ed25519") → NoSuchAlgorithmException
 * KeyFactory.getInstance("Ed25519") → 落到 android.security.keystore2
 *     .AndroidKeyStoreKeyFactorySpi，报「请用 KeyGenParameterSpec 生成」
 * ```
 *
 * 即 Ed25519 既不在 AndroidOpenSSL 的算法表里，被 Keystore provider 接住后
 * 又只能用于硬件密钥。**只在 JDK 上验证会漏掉这个问题** —— 冒烟测试必须在
 * 真机（`app_process`）上跑，而不是本机 java。
 *
 * 实测可用的替代：SHA256withRSA / SHA256withECDSA（均走 AndroidOpenSSL）。
 * 选 RSA 是因为它没有 ECDSA 的 DER 签名编码坑，且签名只有一份、
 * 256 字节 vs 72 字节的差别在这个场景下无关紧要。
 *
 * ## 签名覆盖的是补丁本体的原始字节
 *
 * 校验的是 `patch-wxXXXX.json` 那份文件的**原始内容**，
 * 而不是重新序列化后的结果。原因：JSON 的键序、空白、数字格式都不唯一，
 * 重新序列化会得到字节不同但语义相同的内容 —— 那样签出来的东西验不过。
 *
 * ## 一律 fail-closed
 *
 * 任何异常（公钥解析失败、Base64 非法、签名长度不对、算法不可用）都返回 false。
 * **没有「校验不了就放行」这条路径** —— 那是把安全开关做成装饰。
 */
object PatchSignature {

    private const val TAG = "PatchSignature"

    /**
     * RSA-2048 公钥（X.509 SubjectPublicKeyInfo 的 Base64）。
     *
     * 对应的私钥在 `D:\MonkeyCode\_keystore\patch-signing.key`，**只离线保管**，
     * 绝不进仓库、绝不进 APK、绝不进 CI。
     *
     * 配套的签发工具见仓库 `tools/sign-patch/`。
     */
    private const val PUBLIC_KEY_X509_B64 =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxix+6hDE//gtbWGQSfQ9kBGRKHs7B//+" +
            "v+xwjIL7TYnZk0EwF2HJ5ZQoj0MH+iunhQcKGS8jHdJ9ZAjAufYma56Z/G042giB7gcefKkg2aW6" +
            "48f6TpeX4z/VRBzlR7y2z1eKaKkeBWQXMjWNBA9DyYrR/liSisQC06A8t5p51ZJTjw8kYOkvb6wg" +
            "HsY8NjLYQFRNCsEkLp/Ug6SZBBU7QehG58ib6sCjEoXqh78LOFlpA4z6RYNRUNUm/eUz/LL09opA" +
            "LA1eGDWvrjuqxpediWOfnV6L1C/aZ6sJdkt1c7DshqhQrKdlTk0bsCskUiuPRlYN1M7atSADLBXM" +
            "QsQR7wIDAQAB"

    /** 签名算法。见类头「为什么是 RSA 而不是 Ed25519」。 */
    private const val ALGORITHM = "RSA"
    private const val SIGN_ALGORITHM = "SHA256withRSA"

    /** RSA-2048 签名固定 256 字节。只作快速否决，真正判定交给 [Signature.verify]。 */
    private const val SIGNATURE_BYTES = 256

    private val publicKey: PublicKey? by lazy {
        runCatching {
            val der = android.util.Base64.decode(PUBLIC_KEY_X509_B64, android.util.Base64.DEFAULT)
            KeyFactory.getInstance(ALGORITHM)
                .generatePublic(X509EncodedKeySpec(der))
        }.onFailure {
            // 到这一步说明公钥常量本身写错了 —— 是编译期就该发现的问题，
            // 但真发生了要让所有补丁被拒（而不是放行）。
            WeLogger.e(TAG, "内置公钥解析失败，所有补丁将被拒绝", it)
        }.getOrNull()
    }

    /**
     * 校验补丁本体的签名。
     *
     * @param body 补丁文件的**原始字节**（不是解析后的对象）
     * @param signatureB64 索引里 `signature` 字段的 Base64 值
     * @return 签名有效返回 true；签名缺失、格式错误或验证失败返回 false
     */
    fun verify(body: ByteArray, signatureB64: String?): Boolean {
        if (signatureB64.isNullOrBlank()) {
            // 缺失签名与「签名错误」在这里是同一件事：都不接受。
            // 区分开来打印只是为了排查方便。
            WeLogger.w(TAG, "补丁无签名，拒绝使用")
            return false
        }

        val key = publicKey ?: run {
            WeLogger.w(TAG, "无可用公钥，拒绝补丁")
            return false
        }

        val sig = try {
            android.util.Base64.decode(signatureB64, android.util.Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            WeLogger.w(TAG, "签名不是合法 Base64：${e.message}")
            return false
        }

        if (sig.size != SIGNATURE_BYTES) {
            WeLogger.w(TAG, "签名长度异常：${sig.size} != $SIGNATURE_BYTES")
            return false
        }

        return try {
            val verifier = Signature.getInstance(SIGN_ALGORITHM)
            verifier.initVerify(key)
            verifier.update(body)
            val ok = verifier.verify(sig)
            if (!ok) WeLogger.w(TAG, "补丁签名验证失败 —— 内容与签名不符，拒绝使用")
            ok
        } catch (e: Throwable) {
            WeLogger.w(TAG, "签名验证异常，拒绝补丁：${e.message}")
            false
        }
    }
}
