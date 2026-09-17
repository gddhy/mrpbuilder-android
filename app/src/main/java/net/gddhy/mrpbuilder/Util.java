package net.gddhy.mrpbuilder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** 通用小工具：GBK 编解码、zip 解压 */
public final class Util {

    private Util() {}

    private static final Charset GBK = Charset.forName("GBK");

    /** 转 GBK，超长截断（MRP 头部字段定长）；不可映射字符输出 '?'，与 web 版一致 */
    public static byte[] gbkFixed(String s, int maxLen) {
        byte[] b = gbkBytes(s);
        if (b.length > maxLen) {
            byte[] t = new byte[maxLen];
            System.arraycopy(b, 0, t, 0, maxLen);
            b = t;
        }
        return b;
    }

    /** 字符串 → GBK 字节 */
    public static byte[] gbkBytes(String s) {
        return s == null ? new byte[0] : s.getBytes(GBK);
    }

    /**
     * 规范化 zip 条目名：`\` 统一为 `/`（MT管理器等工具可能产出反斜杠分隔，
     * Android/Linux 下反斜杠不是路径分隔符，会导致子目录被解成单文件、资源相对路径失真）；
     * 去掉前导 `./` 与 `/`，压缩重复斜杠。空结果返回 null（跳过该条目）。
     */
    private static String normalizeEntryName(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) n = n.substring(2);
        while (n.startsWith("/")) n = n.substring(1);
        n = n.replaceAll("/{2,}", "/");
        return n.isEmpty() ? null : n;
    }

    /** 解压安全限制（防压缩炸弹 / 路径穿越） */
    public static final class UnzipLimits {
        public final long maxTotalBytes;      // 解压后累计总字节上限
        public final long maxSingleFileBytes; // 单个文件字节上限
        public final int maxEntries;          // 条目数上限
        public UnzipLimits() {
            this(512L * 1024 * 1024, 128L * 1024 * 1024, 10000);
        }
        public UnzipLimits(long total, long single, int entries) {
            maxTotalBytes = total;
            maxSingleFileBytes = single;
            maxEntries = entries;
        }
    }

    /** 默认解压限制：512MB 总量 / 128MB 单文件 / 10000 条目（内置 gcc 工具链解压约 67MB，安全通过） */
    public static final UnzipLimits DEFAULT_UNZIP_LIMITS = new UnzipLimits();

    public static void unzip(InputStream in, File destDir) throws IOException {
        unzip(in, destDir, DEFAULT_UNZIP_LIMITS);
    }

    /**
     * 安全解压：
     * - 防路径穿越（zip-slip）：解压路径 canonical 必须位于 destDir 内；
     *   （Android 的 ZipEntry 无 OpenJDK 的 getExternalAttributes，符号链接条目只会被
     *   解压成普通文本文件、不会创建真实链接，故无需单独检测）
     * - 防压缩炸弹：条目数 / 单文件大小 / 解压后总量全部限流，超限抛 IOException 并中止。
     */
    public static void unzip(InputStream in, File destDir, UnzipLimits limits) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目录: " + destDir);
        }
        String base = destDir.getCanonicalPath();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String entryName = normalizeEntryName(e.getName());
                if (entryName == null) continue;
                if (++count > limits.maxEntries) {
                    throw new IOException("压缩包条目数超过上限（" + limits.maxEntries + " 个），已中止（疑似压缩炸弹）");
                }
                File out = new File(destDir, entryName);
                String canon = out.getCanonicalPath();
                if (!canon.equals(base) && !canon.startsWith(base + File.separator)) {
                    throw new IOException("压缩包包含非法路径: " + entryName);
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录: " + parent);
                }
                long single = 0;
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        single += n;
                        total += n;
                        if (single > limits.maxSingleFileBytes) {
                            throw new IOException("压缩包单文件解压超过上限（"
                                    + (limits.maxSingleFileBytes >> 20) + "MB）: " + e.getName());
                        }
                        if (total > limits.maxTotalBytes) {
                            throw new IOException("压缩包解压总量超过上限（"
                                    + (limits.maxTotalBytes >> 20) + "MB），已中止（疑似压缩炸弹）");
                        }
                    }
                }
            }
        }
    }

    public static void unzipFile(File zipFile, File destDir) throws IOException {
        unzipFile(zipFile, destDir, DEFAULT_UNZIP_LIMITS);
    }

    /**
     * 基于中央目录（ZipFile）的安全解压：兼容流式写入（data descriptor）等 zip 变体，
     * 且不受 ZipInputStream 顺序读取限制。限制同 unzip(InputStream)。
     */
    public static void unzipFile(File zipFile, File destDir, UnzipLimits limits) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目录: " + destDir);
        }
        String base = destDir.getCanonicalPath();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int count = 0;
        try (ZipFile zf = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String entryName = normalizeEntryName(e.getName());
                if (entryName == null) continue;
                if (++count > limits.maxEntries) {
                    throw new IOException("压缩包条目数超过上限（" + limits.maxEntries + " 个），已中止（疑似压缩炸弹）");
                }
                File out = new File(destDir, entryName);
                String canon = out.getCanonicalPath();
                if (!canon.equals(base) && !canon.startsWith(base + File.separator)) {
                    throw new IOException("压缩包包含非法路径: " + entryName);
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录: " + parent);
                }
                long single = 0;
                try (InputStream is = zf.getInputStream(e);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        single += n;
                        total += n;
                        if (single > limits.maxSingleFileBytes) {
                            throw new IOException("压缩包单文件解压超过上限（"
                                    + (limits.maxSingleFileBytes >> 20) + "MB）: " + e.getName());
                        }
                        if (total > limits.maxTotalBytes) {
                            throw new IOException("压缩包解压总量超过上限（"
                                    + (limits.maxTotalBytes >> 20) + "MB），已中止（疑似压缩炸弹）");
                        }
                    }
                }
            }
        }
    }

    public static void deleteRecursive(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 读取流全部字节 */
    public static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** 文件大小可读化：<1KB 显示 B，<1MB 显示 KB，<1GB 显示 MB，否则 GB（保留 1 位小数） */
    public static String humanSize(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) return bytes + " B";
        long kb = bytes / 1024;
        if (kb < 1024) return kb + "." + (bytes % 1024 * 10 / 1024) + " KB";
        long mb = kb / 1024;
        if (mb < 1024) return mb + "." + (kb % 1024 * 10 / 1024) + " MB";
        long gb = mb / 1024;
        return gb + "." + (mb % 1024 * 10 / 1024) + " GB";
    }
}
