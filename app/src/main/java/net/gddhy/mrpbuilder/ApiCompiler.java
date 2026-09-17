package net.gddhy.mrpbuilder;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * API 编译服务：接收 zip 工程 + mrp 元信息 + 入口文件，后台执行
 * 「解压 → 工程识别 → gcc 编译 → 打包」，任务状态可被 HTTP 接口轮询。
 */
public final class ApiCompiler {

    public interface Log { void log(String s); }

    /** 单个编译任务 */
    public static final class Task {
        public String id;
        public volatile String status = "queued";   // queued / running / success / failed
        public volatile String phase = "waiting";   // extracting / building / packaging
        public volatile int progress;
        public volatile String currentTask;
        public volatile String error;
        public final StringBuilder log = new StringBuilder();
        public volatile long createdAt = System.currentTimeMillis();
        public volatile long startedAt;
        public volatile long finishedAt;
        public volatile String artifactName;

        public synchronized void log(String s) {
            if (log.length() > 0) log.append('\n');
            log.append(s);
        }
        public synchronized String logSince(int offset) {
            if (offset >= log.length()) return "";
            return log.substring(offset);
        }
        public synchronized int logLength() { return log.length(); }
    }

    private final Context ctx;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private static final SecureRandom RND = new SecureRandom();

    public ApiCompiler(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public Task get(String id) { return tasks.get(id); }
    public Map<String, Task> all() { return tasks; }

    /** 生成 12 位十六进制 buildid */
    public static String genId() {
        byte[] b = new byte[6];
        RND.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** 校验打包元信息（与 UI 弹窗规则一致）。返回错误信息；null 表示通过 */
    public static String validateMeta(String display, String fileName, String appId, String ver, String vendor) {
        if (display == null || display.trim().isEmpty()) return "显示名不能为空";
        if (fileName == null || !fileName.matches("(?i)^[a-z0-9_.-]+\\.mrp$"))
            return "内部名必须为英文且以 .mrp 结尾，如 xxx.mrp";
        if (appId == null || appId.trim().isEmpty()) return "AppID 不能为空";
        if (ver == null || ver.trim().isEmpty()) return "版本不能为空";
        if (vendor == null || vendor.trim().isEmpty()) return "开发者不能为空";
        return null;
    }

    /** 提交任务：返回 Task；元信息不合法时抛 IllegalArgumentException */
    public Task submit(byte[] zipBytes, String entryName,
                       String display, String fileName, String appId, String ver,
                       String vendor, String desc) throws Exception {
        String err = validateMeta(display, fileName, appId, ver, vendor);
        if (err != null) throw new IllegalArgumentException(err);
        if (zipBytes == null || zipBytes.length == 0) throw new IllegalArgumentException("未收到源码 zip 文件");
        final Task t = new Task();
        t.id = genId();
        tasks.put(t.id, t);
        pool.execute(() -> runBuild(t, zipBytes, entryName, display, fileName, appId, ver, vendor, desc));
        return t;
    }

    private void runBuild(final Task t, byte[] zipBytes, String entryName,
                          String display, String fileName, String appId, String ver,
                          String vendor, String desc) {
        t.status = "running";
        t.startedAt = System.currentTimeMillis();
        BuildEngine.Log log = t::log;
        try {
            // ---- 解压 ----
            t.phase = "extracting";
            t.currentTask = "解压源码";
            File root = new File(ctx.getFilesDir(), "api_builds/" + t.id);
            File src = new File(root, "src");
            Util.deleteRecursive(src);
            if (!src.mkdirs()) throw new Exception("无法创建工程目录");
            File zip = new File(root, "input.zip");
            try (FileOutputStream fos = new FileOutputStream(zip)) {
                fos.write(zipBytes);
            }
            log.log("[API] 解压源码 zip（" + Util.humanSize(zipBytes.length) + "）...");
            Util.unzipFile(zip, src);
            zip.delete();
            ProjectImporter.flattenSingleRoot(src);

            // ---- 工程识别 ----
            Project p = Project.scan(src);
            log.log("工程: " + p.name);
            log.log("类型: " + p.typeDesc());
            log.log("源码: " + p.sources.size() + " 个 .c，资源: " + p.resources.size() + " 个");
            if (p.runtimeMode == Project.RT_PARTIAL) {
                throw new Exception("不支持编译：检测到 ADS1.2/armcc 老 SDK 工程"
                        + "（含 .mpr 工程配置或自带部分运行时但无 _start），仅支持 gcc 工程");
            }
            if (p.sources.isEmpty()) throw new Exception("工程中没有 .c 源文件");

            // ---- 入口文件 ----
            File entry = null;
            if (entryName != null && !entryName.trim().isEmpty()) {
                String en = entryName.trim().replace('\\', '/');
                entry = findEntry(src, en);
                if (entry == null) throw new Exception("未找到指定入口文件: " + en);
            }
            if (entry == null) entry = p.entry;
            if (entry == null) throw new Exception("未检测到入口文件（可指定入口文件参数）");
            p.entry = entry;
            log.log("入口: " + relPath(src, entry));

            // ---- 编译 ----
            t.phase = "building";
            t.currentTask = "gcc 编译";
            log.log("========== 开始编译 ==========");
            File binElf = BuildEngine.compile(ctx, p, log);

            // ---- 打包 ----
            t.phase = "packaging";
            t.currentTask = "打包 mrp";
            log.log("========== 开始打包 ==========");
            MrpPacker.Meta meta = new MrpPacker.Meta();
            meta.display = display;
            meta.fileName = fileName;
            meta.appid = parseLong(appId, 1);
            meta.version = parseLong(ver, 1);
            meta.vendor = vendor;
            meta.desc = desc == null ? "" : desc;
            List<String> res = BuildEngine.resourceRelPaths(p);
            log.log("资源文件 " + res.size() + " 个（含子目录资源，全部打包入 mrp）:");
            for (String r : res) log.log("  + " + r);
            byte[] mrp = BuildEngine.pack(ctx, p, binElf, meta, res);
            File out = new File(root, fileName);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(mrp);
            }
            t.artifactName = fileName;
            log.log("打包完成: " + fileName + "（" + Util.humanSize(mrp.length) + "）");
            log.log("========== 完成 ==========");

            t.status = "success";
            t.progress = 100;
            t.currentTask = "";
        } catch (Exception e) {
            t.status = "failed";
            t.error = String.valueOf(e.getMessage());
            log.log("[失败] " + t.error);
        }
        t.finishedAt = System.currentTimeMillis();
    }

    /** 在 src 下查找入口（相对路径或文件名） */
    private static File findEntry(File src, String rel) {
        File f = new File(src, rel);
        if (f.isFile()) return f;
        // 只给了文件名，全树查找
        return findByName(src, rel);
    }

    private static File findByName(File dir, String name) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File f : kids) {
            if (f.isDirectory()) {
                File r = findByName(f, name);
                if (r != null) return r;
            } else if (f.getName().equalsIgnoreCase(name)) {
                return f;
            }
        }
        return null;
    }

    private static String relPath(File base, File f) {
        String b = base.getAbsolutePath();
        String a = f.getAbsolutePath();
        return a.startsWith(b + File.separator) ? a.substring(b.length() + 1).replace('\\', '/') : f.getName();
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    /** 清理 3 天前的构建目录 */
    public void cleanup() {
        File root = new File(ctx.getFilesDir(), "api_builds");
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        long now = System.currentTimeMillis();
        for (File d : dirs) {
            if (d.isDirectory() && now - d.lastModified() > 3L * 24 * 3600 * 1000) {
                Util.deleteRecursive(d);
            }
        }
    }
}
