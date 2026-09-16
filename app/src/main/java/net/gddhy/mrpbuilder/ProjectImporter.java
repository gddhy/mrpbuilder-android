package net.gddhy.mrpbuilder;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipInputStream;

/** 通过 SAF 导入工程：单个文件 / zip 包 / 目录授权 / 内置 Demo */
public final class ProjectImporter {

    public interface Log { void log(String s); }

    private ProjectImporter() {}

    /** 通过 SAF 读取文件内容 */
    public static byte[] readUri(Context ctx, Uri uri) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法打开所选文件");
            return Util.readAll(in);
        }
    }

    /** 查询显示名（SAF） */
    public static String queryName(Context ctx, Uri uri) {
        String name = null;
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null) {
            String last = uri.getLastPathSegment();
            if (last != null) {
                int i = last.lastIndexOf('/');
                name = i >= 0 ? last.substring(i + 1) : last;
            }
        }
        return name == null ? "project" : name;
    }

    private static String safeName(String s) {
        String t = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (t.length() > 40) t = t.substring(t.length() - 40);
        return t.isEmpty() ? "project" : t;
    }

    private static File newProjectDir(Context ctx, String baseName, Log log) {
        File root = new File(ctx.getFilesDir(), "projects");
        File dir = new File(root, safeName(baseName));
        if (dir.exists()) {
            Util.deleteRecursive(dir);
            if (log != null) log.log("已清理旧缓存目录: " + dir.getAbsolutePath());
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** 单个文件（.c 或 .zip） */
    public static Project importFile(Context ctx, Uri uri, Log log) throws Exception {
        String name = queryName(ctx, uri);
        log.log("导入: " + name);
        byte[] bytes = readUri(ctx, uri);
        File dir = newProjectDir(ctx, name, log);
        String lower = name.toLowerCase();
        if (lower.endsWith(".zip")) {
            File zip = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(zip)) {
                fos.write(bytes);
            }
            try (ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(
                    new java.io.FileInputStream(zip)))) {
                Util.unzip(zis, dir);
            }
            //noinspection ResultOfMethodCallIgnored
            zip.delete();
            flattenSingleRoot(dir);
            log.log("已解压 zip 到 " + dir.getAbsolutePath());
            Project p = Project.scan(dir);
            logProject(log, p);
            return p;
        } else if (lower.endsWith(".c") || lower.endsWith(".h")) {
            File f = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(bytes);
            }
            Project p = Project.single(f);
            log.log("单文件模式: " + f.getAbsolutePath());
            return p;
        } else {
            throw new Exception("不支持的文件类型（请选择 .c / .h / .zip）");
        }
    }

    /** 目录授权导入 */
    public static Project importTree(Context ctx, Uri treeUri, Log log) throws Exception {
        File dir = newProjectDir(ctx, "tree_project", log);
        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        copyTree(ctx, treeUri, docUri, dir, log);
        // 去掉外层的树名包装目录
        File[] kids = dir.listFiles();
        if (kids != null && kids.length == 1 && kids[0].isDirectory()) {
            flattenSingleRoot(dir);
        }
        Project p = Project.scan(dir);
        logProject(log, p);
        return p;
    }

    private static void copyTree(Context ctx, Uri treeUri, Uri docUri, File dest, Log log) throws Exception {
        String mime = null, display = null, docId = null;
        try (Cursor c = ctx.getContentResolver().query(docUri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int iM = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int iN = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int iD = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                if (iM >= 0) mime = c.getString(iM);
                if (iN >= 0) display = c.getString(iN);
                if (iD >= 0) docId = c.getString(iD);
            }
        }
        if (display == null || docId == null) return;
        if (Project.treeSkip().contains(display)) return;

        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
            File sub = new File(dest, display);
            //noinspection ResultOfMethodCallIgnored
            sub.mkdirs();
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
            try (Cursor c = ctx.getContentResolver().query(children, null, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    int iD = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                    int iM = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                    String id = iD >= 0 ? c.getString(iD) : null;
                    String cm = iM >= 0 ? c.getString(iM) : null;
                    if (id == null) continue;
                    Uri childDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cm)) {
                        copyTree(ctx, treeUri, childDoc, sub, log);
                    } else {
                        copyFile(ctx, childDoc, new File(sub, displayNameOf(ctx, childDoc, "file")), log);
                    }
                }
            }
        } else {
            copyFile(ctx, docUri, new File(dest, display), log);
        }
    }

    private static void copyFile(Context ctx, Uri uri, File out, Log log) throws Exception {
        if (out.getName().startsWith(".")) return;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) return;
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            }
        }
    }

    private static String displayNameOf(Context ctx, Uri uri, String def) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        return def;
    }

    /** 内置 Demo：首次把 assets/demo 复制到私有目录 */
    public static Project loadDemo(Context ctx, Log log) throws Exception {
        File dir = new File(ctx.getFilesDir(), "projects/demo");
        File marker = new File(dir, ".extracted");
        if (!marker.exists()) {
            Util.deleteRecursive(dir);
            //noinspection ResultOfCallIgnored
            dir.mkdirs();
            copyAssetDir(ctx, "demo", dir, log);
            try (FileOutputStream fos = new FileOutputStream(marker)) {
                fos.write(1);
            }
            log.log("已展开内置 Demo");
        }
        Project p = Project.scan(dir);
        logProject(log, p);
        return p;
    }

    /** 展开运行时库（assets/demo/src + assets/runtime），供单文件/缺运行时工程使用 */
    public static synchronized File ensureRuntime(Context ctx, Log log) throws Exception {
        File dir = new File(ctx.getFilesDir(), "runtime");
        // v4：mrp_compat.c 新增 64 位移位弱符号（bionic 依赖消除），
        // 旧设备已展开的缓存必须重新部署
        File marker = new File(dir, ".extracted_v4");
        if (!marker.exists()) {
            Util.deleteRecursive(dir);
            //noinspection ResultOfCallIgnored
            dir.mkdirs();
            copyAssetDir(ctx, "demo/src", new File(dir, "src"), log);
            copyAsset(ctx, "runtime/mrp_compat.c", new File(dir, "src/mrp_compat.c"));
            try (FileOutputStream fos = new FileOutputStream(marker)) {
                fos.write(1);
            }
            log.log("已展开内置运行时库");
        }
        return new File(dir, "src");
    }

    /** 展开兼容层（assets/runtime/mrp_compat.c），供「自带运行时」的工程补齐
     *  sqrt/atan2/mrc_getSysMem/mrc_getMemoryRemain/raise/sin/cos 等缺件 */
    public static synchronized File ensureCompat(Context ctx, Log log) throws Exception {
        File dir = new File(ctx.getFilesDir(), "runtime_compat");
        // v3：mrp_compat.c 新增 drawBitmapBit/64 位移位弱符号兜底，
        // 旧设备上已展开的缓存必须重新部署
        File marker = new File(dir, ".extracted_v3");
        if (!marker.exists()) {
            Util.deleteRecursive(dir);
            //noinspection ResultOfCallIgnored
            dir.mkdirs();
            copyAsset(ctx, "runtime/mrp_compat.c", new File(dir, "mrp_compat.c"));
            try (FileOutputStream fos = new FileOutputStream(marker)) {
                fos.write(1);
            }
            log.log("已展开内置兼容层 mrp_compat.c");
        }
        return new File(dir, "mrp_compat.c");
    }

    private static void copyAssetDir(Context ctx, String assetDir, File dest, Log log) throws Exception {
        String[] kids = ctx.getAssets().list(assetDir);
        if (kids == null) return;
        for (String k : kids) {
            String path = assetDir + "/" + k;
            if (ctx.getAssets().list(path).length > 0) {
                copyAssetDir(ctx, path, new File(dest, k), log);
            } else {
                copyAsset(ctx, path, new File(dest, k));
            }
        }
    }

    private static void copyAsset(Context ctx, String asset, File out) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = ctx.getAssets().open(asset);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        }
    }

    private static void flattenSingleRoot(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null && kids.length == 1 && kids[0].isDirectory()) {
            File sub = kids[0];
            File[] subKids = sub.listFiles();
            if (subKids != null) {
                for (File f : subKids) {
                    File moveTo = new File(dir, f.getName());
                    if (f.renameTo(moveTo)) {
                        // ok
                    } else {
                        moveRecursive(f, moveTo);
                    }
                }
            }
            Util.deleteRecursive(sub);
        }
    }

    private static void moveRecursive(File from, File to) {
        if (from.isDirectory()) {
            //noinspection ResultOfCallIgnored
            to.mkdirs();
            File[] kids = from.listFiles();
            if (kids != null) for (File k : kids) moveRecursive(k, new File(to, k.getName()));
        } else {
            try (InputStream in = new java.io.FileInputStream(from);
                 FileOutputStream fos = new FileOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            } catch (Exception ignored) {
            }
        }
    }

    private static void logProject(Log log, Project p) {
        if (p == null) return;
        log.log("工程: " + p.name);
        log.log("类型: " + p.typeDesc());
        log.log("入口: " + (p.entry == null ? "（未检测到）" : p.entry.getName()));
        log.log("源码: " + p.sources.size() + " 个 .c，资源: " + p.resources.size() + " 个");
        if (p.runtimeMode == Project.RT_PARTIAL) {
            log.log("[不支持] ADS1.2/armcc 老 SDK 工程无法用 gcc 编译，仅支持 gcc 工程");
        } else {
            log.log("自带壳: " + (p.libStartMr != null) + "/" + (p.libCfunctionExt != null));
        }
    }
}
