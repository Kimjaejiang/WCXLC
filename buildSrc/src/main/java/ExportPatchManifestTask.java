import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 导出「补丁清单」—— 模块版本 + 各功能的 methodHash + 锚点 key 列表（不含值）。
 *
 * <p>服务端要靠这份清单才能签发<strong>当前模块版本可用</strong>的补丁：
 *
 * <ul>
 *   <li>{@code methodHash} 是编译期源码 md5（见 {@link GenerateMethodHashesTask}），
 *       服务端算不出来，必须由构建产物导出后上传。</li>
 *   <li>锚点 key 列表用于服务端侧做白名单校验 —— 补丁只能填已存在的委托 key。</li>
 * </ul>
 *
 * <p>清单<strong>不含锚点值</strong>（那些要靠运行时解析或生产侧提供），所以它本身不是补丁，
 * 只是「这份补丁该长什么样」的骨架。
 *
 * <h2>为什么不自己算 hash</h2>
 *
 * <p>直接读 {@code generateMethodHashes} 的产物 {@code GeneratedMethodHashes.kt}。
 * 那份文件是运行时校验的<strong>唯一真相</strong>，读它就能保证清单与运行时
 * <strong>构造上一致</strong>；自己再实现一遍 md5 口径，早晚会跑偏，
 * 而跑偏的后果是补丁被静默拒绝、很难排查。
 */
public abstract class ExportPatchManifestTask extends DefaultTask {

    /** 功能源码目录，用于解析每个功能持有的委托 key 与 @Feature(name)。 */
    @InputDirectory
    public abstract DirectoryProperty getSourceDir();

    /** generateMethodHashes 的产物，从中读取 hash 表。 */
    @InputFile
    public abstract RegularFileProperty getHashesFile();

    @Input
    public abstract Property<Long> getModuleVersionCode();

    @Input
    public abstract Property<String> getModuleVersionName();

    @Input
    public abstract Property<String> getNamespace();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    private static final Pattern PACKAGE =
            Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);
    private static final Pattern CLASS_DECL =
            Pattern.compile("\\b(?:object|class)\\s+(\\w+)");
    private static final Pattern DELEGATE =
            Pattern.compile("\\bby\\s+dex(?:Class|Method|Constructor|Field)\\b");
    private static final Pattern PROP_NAME =
            Pattern.compile("(?:val|var)\\s+(\\w+)\\s*$");
    private static final Pattern FEATURE_NAME =
            Pattern.compile("@Feature\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern HASH_ENTRY =
            Pattern.compile("\"([\\w.$]+)\"\\s*to\\s*\"([0-9a-f]{32})\"");

    @TaskAction
    public void run() throws IOException {
        File src = getSourceDir().get().getAsFile();
        File hashesFile = getHashesFile().get().getAsFile();

        Map<String, String> hashes = readHashes(hashesFile);
        if (hashes.isEmpty()) {
            getLogger().warn("[patch-manifest] hash 表为空，跳过导出：" + hashesFile.getAbsolutePath());
            return;
        }

        List<File> sources = new ArrayList<>();
        try (var stream = Files.walk(src.toPath())) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".kt"))
                    .forEach(p -> sources.add(p.toFile()));
        }
        sources.sort((a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));

        List<String> entries = new ArrayList<>();
        int skipped = 0;

        for (File file : sources) {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (!content.contains("IResolveDex")) {
                continue;
            }

            Matcher pm = PACKAGE.matcher(content);
            Matcher cm = CLASS_DECL.matcher(content);
            if (!pm.find() || !cm.find()) {
                skipped++;
                continue;
            }
            String fullName = pm.group(1) + "." + cm.group(1);
            String hash = hashes.get(fullName);
            if (hash == null) {
                // 解释器/接口本身没有 hash，属正常；记下数量便于核对
                skipped++;
                continue;
            }

            String simpleName = cm.group(1);

            // 委托 key 形如 "ClassName:propName"。往回找最近的属性声明名。
            List<String> keys = new ArrayList<>();
            Matcher dm = DELEGATE.matcher(content);
            while (dm.find()) {
                Matcher propm = PROP_NAME.matcher(content.substring(0, dm.start()));
                String prop = null;
                while (propm.find()) {
                    prop = propm.group(1);
                }
                if (prop != null && !prop.isEmpty()) {
                    keys.add(simpleName + ":" + prop);
                }
            }
            keys = keys.stream().distinct().sorted().collect(Collectors.toList());

            Matcher fm = FEATURE_NAME.matcher(content);
            String name = fm.find() ? fm.group(1) : simpleName;

            entries.add("    {\n"
                    + "      \"name\": " + quote(name) + ",\n"
                    + "      \"class\": " + quote(fullName) + ",\n"
                    + "      \"methodHash\": \"" + hash + "\",\n"
                    + "      \"anchors\": [" + keys.stream().map(ExportPatchManifestTask::quote)
                            .collect(Collectors.joining(", ")) + "]\n"
                    + "    }");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": 1,\n");
        sb.append("  \"moduleVersionCode\": ").append(getModuleVersionCode().get()).append(",\n");
        sb.append("  \"moduleVersionName\": ").append(quote(getModuleVersionName().get())).append(",\n");
        sb.append("  \"features\": [\n");
        sb.append(String.join(",\n", entries));
        sb.append("\n  ]\n}\n");

        File out = getOutputFile().get().getAsFile();
        File parent = out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));

        getLogger().lifecycle("[patch-manifest] " + entries.size()
                + " 个功能（跳过 " + skipped + "）→ " + out.getAbsolutePath());
    }

    /**
     * 解析 GeneratedMethodHashes.kt 里的 {@code "类名" to "hash"} 对。
     *
     * <p>用正则而不是编译产物：文件格式由 GenerateMethodHashesTask 固定生成，很稳定。
     */
    private Map<String, String> readHashes(File file) throws IOException {
        Map<String, String> result = new TreeMap<>();
        if (!file.isFile()) {
            getLogger().warn("[patch-manifest] 找不到 hash 表：" + file.getAbsolutePath());
            return result;
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Matcher m = HASH_ENTRY.matcher(text);
        while (m.find()) {
            result.put(m.group(1), m.group(2));
        }
        return result;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
