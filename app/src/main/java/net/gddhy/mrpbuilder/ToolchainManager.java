package net.gddhy.mrpbuilder;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.InputStream;

/**
 * 工具链管理：首次使用时把 assets/toolchain/gcc.zip（arm-linux-androideabi gcc，
 * 静态运行于 32 位 ARM 兼容层）解压到应用私有目录并加执行权限。
 *
 * 为什么用 32 位 ARM 工具链：MRP 的目标机是 ARMv5TE（MT6225/6235/6250/6276 等），
 * 输出必须是 32 位小端 ARM ELF；gcc 自身是 32 位 ARM 二进制，在 arm64 手机上
 * 通过内核 32 位兼容层执行。
 *
 * targetSdk 28 的意义：Android 10（API 29）起对应用私有目录施加 W^X 限制，
 * 禁止从 data 目录执行原生程序；targetSdk ≤ 28 时保持旧行为，解压出来的 gcc
 * 才能被 ProcessBuilder 执行。
 */
public final class ToolchainManager {

    /** 工具链版本标记：更换工具链时 +1 强制重解压 */
    private static final int VERSION = 1;

    private ToolchainManager() {}

    public static synchronized File ensure(Context ctx, ProjectImporter.Log log) throws Exception {
        File dir = new File(ctx.getFilesDir(), "gcc");
        File marker = new File(dir, ".ready_v" + VERSION);
        File gcc = new File(dir, "gcc/bin/arm-linux-androideabi-gcc");
        if (marker.exists() && gcc.isFile()) return dir;

        log.log("首次使用：解压 gcc 工具链（约 26MB，请稍候）...");
        Util.deleteRecursive(dir);
        //noinspection ResultOfCallIgnored
        dir.mkdirs();
        try (InputStream in = ctx.getAssets().open("toolchain/gcc.zip")) {
            Util.unzip(in, dir);
        }
        chmodRecursive(dir);
        if (!gcc.isFile()) throw new Exception("工具链解压不完整，缺少 " + gcc);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(marker);
        try {
            fos.write(VERSION);
        } finally {
            fos.close();
        }
        log.log("工具链就绪: " + dir.getAbsolutePath());
        return dir;
    }

    /** 找到 libgcc.a（gcc 版本目录下的静态库，-nostdlib 下唯一链接的库） */
    public static File findLibGcc(File toolDir) {
        File base = new File(toolDir, "gcc/lib/gcc/arm-linux-androideabi");
        File[] vers = base.listFiles();
        if (vers != null) {
            for (File v : vers) {
                File a = new File(v, "libgcc.a");
                if (a.isFile()) return a;
            }
        }
        return null;
    }

    public static String abi() {
        return Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
    }

    /** 全量加执行位（脚本、cc1、as、ld 等都需要） */
    private static void chmodRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) chmodRecursive(k);
        } else {
            //noinspection ResultOfMethodCallIgnored
            f.setExecutable(true, false);
        }
    }
}
