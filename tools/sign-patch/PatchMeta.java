import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从导出的补丁 JSON 里读元信息，供 shell 脚本使用。
 *
 * 用法：
 *   java -cp . PatchMeta <adapt-patch.json>
 * 输出（单行，空格分隔）：
 *   <min> <max> <featureCount>
 *
 * 为什么单独一个工具而不是让 SignPatch 顺手解析：SignPatch 的职责是
 * 「对一个字节数组签名」。它**不解析 JSON** 是正确的设计 —— 签名覆盖的是
 * 文件原始字节，一旦引入「解析再序列化」的念头，键序/空白就可能变，
 * 签名立刻失效。元信息读取是另一件事，分开。
 *
 * 这里也不引第三方 JSON 库：签发工具要求零依赖，任何人 clone 下来
 * javac 就能跑。补丁结构固定，正则足够。
 */
public final class PatchMeta {

    // "wxVersionRange": { "min": 3180, "max": 3180 }
    private static final Pattern RANGE = Pattern.compile(
            "\"wxVersionRange\"\\s*:\\s*\\{[^}]*?\"min\"\\s*:\\s*(-?\\d+)[^}]*?\"max\"\\s*:\\s*(-?\\d+)",
            Pattern.DOTALL);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java -cp . PatchMeta <补丁json>");
            System.exit(2);
        }

        Path p = Paths.get(args[0]);
        if (!Files.isRegularFile(p)) {
            System.err.println("文件不存在: " + p);
            System.exit(1);
        }

        String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);

        Matcher m = RANGE.matcher(text);
        if (!m.find()) {
            System.err.println("缺 wxVersionRange");
            System.exit(1);
        }
        String min = m.group(1);
        String max = m.group(2);

        // 数 "anchors" 出现的次数而非解析 features 对象：
        // 每个功能恰好有一个 anchors 字段，够用且不依赖键序。
        int features = count(text, "\"anchors\"");
        if (features == 0) {
            // 退化情况：功能存在但锚点全空（解析未完成时会这样）
            features = count(text, "\"methodHash\"");
        }

        System.out.println(min + " " + max + " " + features);
    }

    private static int count(String text, String needle) {
        int n = 0, i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
