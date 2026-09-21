import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 补丁签发工具（离线运行，不进 APK、不进 CI）。
 *
 * 用法：
 *   java SignPatch <私钥> <补丁json> [输出的.sig]
 *
 * 例：
 *   java SignPatch "D:\MonkeyCode\_keystore\patch-signing.key" \\
 *        out/patches/wx3180.json
 *   → 生成 out/patches/wx3180.json.sig
 *
 * 产出的两个文件都放进仓库 patches/ 目录：
 *   patches/wx3180.json      补丁本体
 *   patches/wx3180.json.sig  分离签名（模块下载时与本体一起取）
 *
 * ## 为什么签名是独立文件而不是补丁里的字段
 *
 * 签名必须覆盖补丁的**原始字节**。若把签名字段写进补丁本体，就变成
 * 「签名包含自己」—— 只能靠「剔除该字段后再序列化」来绕，而 JSON 的
 * 键序/空白不唯一，那种做法依赖序列化实现稳定，非常脆。
 * 分离签名没有这个问题：签什么就是什么，字节对字节。
 *
 * ## 私钥绝不外泄
 *
 * 这个工具要读私钥，所以**只能在你的离线机器上跑**，产物（.sig）才进仓库。
 * 永远不要把私钥本身提交进任何仓库、不要放进 CI secrets、不要贴进聊天。
 */
public class SignPatch {

    private static final String ALGORITHM = "RSA";
    private static final String SIGN_ALGORITHM = "SHA256withRSA";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java SignPatch <私钥> <补丁json> [输出的.sig]");
            System.err.println("例:   java SignPatch \"D:\\MonkeyCode\\_keystore\\patch-signing.key\" patches/wx3180.json");
            System.exit(2);
        }

        Path keyPath = Paths.get(args[0]);
        Path patchPath = Paths.get(args[1]);
        Path sigPath = args.length >= 3
                ? Paths.get(args[2])
                : Paths.get(patchPath.toString() + ".sig");

        if (!Files.isRegularFile(keyPath)) {
            System.err.println("私钥不存在: " + keyPath);
            System.exit(1);
        }
        if (!Files.isRegularFile(patchPath)) {
            System.err.println("补丁不存在: " + patchPath);
            System.exit(1);
        }

        byte[] keyBytes = Files.readAllBytes(keyPath);
        PrivateKey privateKey = KeyFactory.getInstance(ALGORITHM)
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        // 签名覆盖原始字节 —— 不做任何解析、规范化、重新序列化。
        // 模块端 PatchSignature 验的也是这批字节，两边必须完全一致。
        byte[] body = Files.readAllBytes(patchPath);

        Signature signer = Signature.getInstance(SIGN_ALGORITHM);
        signer.initSign(privateKey);
        signer.update(body);
        byte[] sig = signer.sign();

        String sigB64 = Base64.getEncoder().encodeToString(sig);
        Files.write(sigPath, (sigB64 + "\n").getBytes("UTF-8"));

        System.out.println("已签名: " + patchPath);
        System.out.println("补丁大小: " + body.length + " 字节");
        System.out.println("签名文件: " + sigPath);
        System.out.println("签名(Base64): " + sigB64);

        // 自检：签完立刻用内置公钥验一遍。
        // 这一步是为了在**签发时**就发现「私钥和模块内置公钥不配对」——
        // 否则要等用户装上模块、下载失败才暴露，那时排查成本高得多。
        verifySelfCheck(body, sig);
    }

    private static void verifySelfCheck(byte[] body, byte[] sig) {
        try {
            String pubB64 = PatchPublicKey.PUBLIC_KEY_X509_B64;
            PublicKey pub = KeyFactory.getInstance(ALGORITHM).generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)));
            Signature v = Signature.getInstance(SIGN_ALGORITHM);
            v.initVerify(pub);
            v.update(body);
            if (v.verify(sig)) {
                System.out.println("自检: 模块内置公钥可验证此签名 ✓");
            } else {
                System.err.println("自检失败: 模块内置公钥**验不过**此签名！");
                System.err.println("      说明你用的私钥与 PatchSignature.kt 里的公钥不配对。");
                System.err.println("      这份补丁发出去没人能用，请先核对密钥。");
                System.exit(3);
            }
        } catch (Exception e) {
            System.err.println("自检异常（无法确认配对关系）: " + e.getMessage());
            System.exit(3);
        }
    }
}
