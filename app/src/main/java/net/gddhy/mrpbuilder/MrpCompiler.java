package net.gddhy.mrpbuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 调用 arm-linux-androideabi-gcc 编译 bin.elf。
 * 参数对齐 TinalIDE（手机端 arm-none-eabi-gcc 真机验证可运行）：
 *
 *   gcc -o bin.elf <入口.c> <src/*.c> -I<头文件目录> \
 *       -Os -Wall -Wno-implicit-function-declaration -Wno-implicit-int \
 *       -Wno-builtin-declaration-mismatch \
 *       -marm -march=armv5te -mfloat-abi=soft \
 *       -ffixed-r9 -ffixed-r10 \
 *       -ffunction-sections -fdata-sections -fno-common -fshort-enums \
 *       -fPIC -fno-tree-loop-distribute-patterns \
 *       -nostdlib -nostartfiles -pie -Wl,--entry=_start \
 *       -Wl,--gc-sections -Wl,--strip-all -Wl,--strip-debug <libgcc.a>
 *
 * 关键点：
 * - -ffixed-r9 -ffixed-r10：老 MTK 功能机（MT6225/6235/6250/6276 等）的 VMF 平台
 *   由 ADS/armcc 按 APCS 编译，约定 r9=静态基址(SB)、r10=栈限制(SL)，中断/回调
 *   依赖这两个寄存器。若 gcc 把 r9/r10 当普通寄存器使用，bin.elf 运行中被平台
 *   中断打断后壳状态被破坏 -> 真机卡启动页（模拟器不触发，表现正常）。
 *   实测：加该参数后产物 r9/r10 使用从 109 次降为 0。
 * - -mfloat-abi=soft：ARMv5TE 无 FPU，禁止 VFP 指令，浮点走 libgcc 软浮点例程。
 * - -Os + -ffunction-sections/-fdata-sections + --gc-sections：死代码消除，
 *   减小体积（老机内存受限）。
 * - -fno-common：避免未初始化全局变量合并 common 块，各符号独立 .bss 项。
 * - -fshort-enums：枚举按最小宽度，减小结构体体积（工程内自洽）。
 * - -Wl,--strip-all/--strip-debug：产物去除符号表；.dynsym 保留，动态段
 *   仍只有 R_ARM_RELATIVE 重定位，真机 elfloader 兼容（已实测验证）。
 * - 不链接 nano libc（-lc -lm）：arm-linux-androideabi 工具链不带 newlib，
 *   C 库依赖由 mrp_compat.c 弱符号补齐（memcpy/memset/sqrt/sin/cos/raise 等）。
 */
public final class MrpCompiler {

    private MrpCompiler() {}

    public static final List<String> BASE_FLAGS = Arrays.asList(
            "-Os",
            "-Wall",
            "-Wno-implicit-function-declaration",
            "-Wno-implicit-int",
            "-Wno-builtin-declaration-mismatch",
            "-marm",
            "-march=armv5te",
            "-mfloat-abi=soft",
            "-ffixed-r9",
            "-ffixed-r10",
            "-ffunction-sections",
            "-fdata-sections",
            "-fno-common",
            "-fshort-enums",
            "-fPIC",
            "-fno-tree-loop-distribute-patterns",
            "-nostdlib",
            "-nostartfiles",
            "-pie",
            "-Wl,--entry=_start",
            "-Wl,--gc-sections",
            "-Wl,--strip-all",
            "-Wl,--strip-debug"
    );

    public interface Line { void line(String s); }

    /** 返回退出码 */
    public static int run(File toolDir, File gccPath, File workDir,
                          File libgcc, List<File> sources, List<File> incDirs,
                          List<String> extraFlags, Line out) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(gccPath.getAbsolutePath());
        cmd.add("-o");
        cmd.add(new File(workDir, "bin.elf").getAbsolutePath());
        for (File s : sources) cmd.add(s.getAbsolutePath());
        for (File d : incDirs) cmd.add("-I" + d.getAbsolutePath());
        cmd.addAll(BASE_FLAGS);
        if (extraFlags != null) cmd.addAll(extraFlags);
        if (libgcc != null) cmd.add(libgcc.getAbsolutePath());
        return exec(toolDir, workDir, cmd, out);
    }

    private static int exec(File toolDir, File workDir, List<String> cmd, Line out)
            throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder("$ ");
        for (String c : cmd) {
            sb.append(c.contains(" ") ? "\"" + c + "\" " : c + " ");
        }
        if (out != null) out.line(sb.toString().trim());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        Map<String, String> env = pb.environment();
        String tcBin = new File(toolDir, "gcc/bin").getAbsolutePath();
        String tgtBin = new File(toolDir, "gcc/arm-linux-androideabi/bin").getAbsolutePath();
        env.put("PATH", tcBin + File.pathSeparator + tgtBin
                + File.pathSeparator + "/system/bin" + File.pathSeparator + "/system/xbin");
        File tmp = new File(workDir, ".tmp");
        //noinspection ResultOfCallIgnored
        tmp.mkdirs();
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("HOME", workDir.getAbsolutePath());
        env.put("LC_ALL", "C");
        pb.redirectErrorStream(true);

        Process p = pb.start();
        Thread pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out != null) out.line(line);
                }
            } catch (IOException ignored) {
            }
        }, "gcc-output");
        pump.setDaemon(true);
        pump.start();

        // 最多等 6 分钟（大工程 + 低端机）。
        // 注意：waitFor(long,TimeUnit)/isAlive() 是 API 26+，这里用
        // exitValue() 抛 IllegalThreadStateException 的方式轮询，保证 minSdk 24 可用。
        long deadline = System.currentTimeMillis() + 6 * 60 * 1000;
        int rc = -1;
        boolean exited = false;
        while (System.currentTimeMillis() < deadline) {
            try {
                rc = p.exitValue();
                exited = true;
                break;
            } catch (IllegalThreadStateException notYet) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (!exited) {
            p.destroy();
            if (out != null) out.line("编译超时（6 分钟），已终止");
            return -1;
        }
        pump.join(2000);
        return rc;
    }

    /** 查询 gcc 版本（用于日志） */
    public static String version(File gccPath) {
        try {
            Process p = new ProcessBuilder(gccPath.getAbsolutePath(), "--version").start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String first = r.readLine();
                long deadline = System.currentTimeMillis() + 3000;
                boolean exited = false;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        p.exitValue();
                        exited = true;
                        break;
                    } catch (IllegalThreadStateException notYet) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                if (!exited) p.destroy();
                return first == null ? "" : first.trim();
            }
        } catch (Exception e) {
            return "无法获取版本: " + e.getMessage();
        }
    }
}
