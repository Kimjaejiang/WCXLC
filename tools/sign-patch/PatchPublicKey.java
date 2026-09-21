/**
 * 模块内置的补丁验签公钥（副本，**仅供工具自检**）。
 *
 * 权威定义在
 *   app/src/main/java/com/Johnny/wcx/dynamic/patch/PatchSignature.kt
 * 的 PUBLIC_KEY_X509_B64。改密钥时两处都要改 —— 这里只是为了让签发工具
 * 能在签完当场确认「这份签名模块那边验得过」，避免发出去才发现不配对。
 *
 * 私自钥在 D:\MonkeyCode\_keystore\patch-signing.key（离线保管，不进仓库）。
 *
 * RSA-2048。不用 Ed25519 的原因见 PatchSignature.kt 类头 —— Android 不支持它。
 */
public class PatchPublicKey {
    public static final String PUBLIC_KEY_X509_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxix+6hDE//gtbWGQSfQ9kBGRKHs7B//+"
          + "v+xwjIL7TYnZk0EwF2HJ5ZQoj0MH+iunhQcKGS8jHdJ9ZAjAufYma56Z/G042giB7gcefKkg2aW6"
          + "48f6TpeX4z/VRBzlR7y2z1eKaKkeBWQXMjWNBA9DyYrR/liSisQC06A8t5p51ZJTjw8kYOkvb6wg"
          + "HsY8NjLYQFRNCsEkLp/Ug6SZBBU7QehG58ib6sCjEoXqh78LOFlpA4z6RYNRUNUm/eUz/LL09opA"
          + "LA1eGDWvrjuqxpediWOfnV6L1C/aZ6sJdkt1c7DshqhQrKdlTk0bsCskUiuPRlYN1M7atSADLBXM"
          + "QsQR7wIDAQAB";
}
