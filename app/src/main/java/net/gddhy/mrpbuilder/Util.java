package net.gddhy.mrpbuilder;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.zip.ZipEntry;
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

    /** 防 zip-slip 的解压 */
    public static void unzip(InputStream in, File destDir) throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目录: " + destDir);
        }
        String base = destDir.getCanonicalPath();
        byte[] buf = new byte[64 * 1024];
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                File out = new File(destDir, e.getName());
                String canon = out.getCanonicalPath();
                if (!canon.equals(base) && !canon.startsWith(base + File.separator)) {
                    throw new IOException("压缩包包含非法路径: " + e.getName());
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录: " + parent);
                }
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) != -1) fos.write(buf, 0, n);
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
