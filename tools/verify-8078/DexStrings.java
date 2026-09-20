import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * 从 DEX 文件批量提取 string_ids 字符串表。
 *
 * 为什么不用 dexdump：dexdump -d 只能看到代码里被引用的字符串，
 * 而模块的锚点可能锚在方法名/字段名/常量池任意位置。
 * string_ids 段是 DEX 的权威字符串全集，直接解析最可靠。
 *
 * DEX 头（小端）：
 *   0x38 string_ids_size (u4)
 *   0x3C string_ids_off  (u4)
 * 每项 u4 偏移指向 string_data_item：uleb128 utf16_size + MUTF-8 + 0x00
 */
public class DexStrings {

    public static void main(String[] args) throws Exception {
        Path dexDir = Paths.get(args[0]);
        Path outPath = Paths.get(args[1]);

        List<Path> dexFiles = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dexDir, "*.dex")) {
            for (Path p : ds) dexFiles.add(p);
        }
        Collections.sort(dexFiles);

        // 去重：同一字符串可能出现在多个 dex
        LinkedHashSet<String> all = new LinkedHashSet<>();
        long total = 0;

        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outPath.toFile()),
                java.nio.charset.StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)))) {
            for (Path p : dexFiles) {
                int n = extractOne(p, all);
                total += n;
                System.err.println(p.getFileName() + ": " + n);
            }
            for (String s : all) {
                w.write(s);
                w.write('\n');
            }
        }
        System.err.println("total=" + total + " unique=" + all.size());
    }

    /** 解析单个 dex，把字符串加进 out，返回该 dex 的字符串个数。 */
    private static int extractOne(Path p, Set<String> out) {
        byte[] data;
        try {
            data = Files.readAllBytes(p);
        } catch (IOException e) {
            System.err.println("read failed: " + p + " " + e);
            return 0;
        }
        if (data.length < 112
                || data[0] != 'd' || data[1] != 'e' || data[2] != 'x') {
            System.err.println("not a dex: " + p);
            return 0;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int stringIdsSize = buf.getInt(0x38);
        int stringIdsOff = buf.getInt(0x3C);
        if (stringIdsSize < 0 || stringIdsOff < 0
                || (long) stringIdsOff + (long) stringIdsSize * 4 > data.length) {
            System.err.println("bad string table in " + p);
            return 0;
        }

        int count = 0;
        for (int i = 0; i < stringIdsSize; i++) {
            int itemOff = buf.getInt(stringIdsOff + i * 4);
            if (itemOff < 0 || itemOff >= data.length) continue;
            String s = readMutf8(data, itemOff);
            if (s != null && !s.isEmpty()) {
                out.add(s);
                count++;
            }
        }
        return count;
    }

    /** 读 string_data_item：uleb128 长度 + MUTF-8 字节（0x00 结尾）。 */
    private static String readMutf8(byte[] data, int off) {
        // 跳过 uleb128 utf16_size
        int p = off;
        while (p < data.length && (data[p] & 0x80) != 0) p++;
        p++; // 最后那个字节
        if (p >= data.length) return null;

        // MUTF-8：与标准 UTF-8 不同之处是 U+0000 编码为 C0 80，且用代理对表示增补字符。
        // 这里先收集原始字节，再用对 MUTF-8 宽容的方式解码。
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (p < data.length && data[p] != 0) {
            bos.write(data[p]);
            p++;
        }
        byte[] raw = bos.toByteArray();

        // 手工解 MUTF-8，避免标准解码器对 C0 80 / 代理对报错
        StringBuilder sb = new StringBuilder(raw.length);
        int i = 0;
        while (i < raw.length) {
            int b = raw[i] & 0xFF;
            if (b < 0x80) {
                sb.append((char) b);
                i++;
            } else if ((b & 0xE0) == 0xC0) {
                if (i + 1 >= raw.length) break;
                int b2 = raw[i + 1] & 0xFF;
                sb.append((char) (((b & 0x1F) << 6) | (b2 & 0x3F)));
                i += 2;
            } else if ((b & 0xF0) == 0xE0) {
                if (i + 2 >= raw.length) break;
                int b2 = raw[i + 1] & 0xFF;
                int b3 = raw[i + 2] & 0xFF;
                sb.append((char) (((b & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F)));
                i += 3;
            } else {
                i++; // 非法字节，跳过
            }
        }
        return sb.toString();
    }
}
