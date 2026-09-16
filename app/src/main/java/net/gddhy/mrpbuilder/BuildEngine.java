package net.gddhy.mrpbuilder;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 构建引擎：把「编译 bin.elf」和「打包 .mrp」串起来。
 *
 * 两阶段：
 *   一阶段 gcc 编译：.c/.h -> bin.elf（静态 PIE，_start 入口）
 *   二阶段打包：start.mr + bin.elf + 资源 + cfunction.ext 按 MRP 容器格式打包
 */
public final class BuildEngine {

    /** 日志回调统一用 ProjectImporter.Log */
    public interface Log extends ProjectImporter.Log {}

    private BuildEngine() {}

    public static class BuildException extends Exception {
        public BuildException(String msg) { super(msg); }
    }

    /** 一阶段：编译 bin.elf，成功返回 bin.elf 文件 */
    public static File compile(Context ctx, Project p, Log log) throws Exception {
        File toolDir = ToolchainManager.ensure(ctx, log);
        File gccPath = new File(toolDir, "gcc/bin/arm-linux-androideabi-gcc");
        log.log("GCC: " + MrpCompiler.version(gccPath));
        File libgcc = ToolchainManager.findLibGcc(toolDir);
        log.log("libgcc: " + (libgcc == null ? "未找到" : libgcc.getAbsolutePath()));
        if (libgcc == null) throw new BuildException("工具链缺少 libgcc.a");

        if (p.entry == null) throw new BuildException("未检测到入口文件（main.c / 包含 main 或 mrc_init 的 .c）");
        if (p.runtimeMode == Project.RT_PARTIAL) {
            throw new BuildException("不支持编译：检测到 ADS1.2/armcc 老 SDK 工程"
                    + "（无 mythroad 运行时入口 _start，依赖旧版 SkySDK 头文件与厂商 .lib）。"
                    + "本工具仅支持 gcc 工程（自带完整运行时，或采用内置运行时库的工程）。");
        }

        File binElf = new File(p.dir, "bin.elf");
        //noinspection ResultOfCallIgnored
        binElf.delete();
        // 清掉上次编译的中间产物（.tmp / bin.elf），避免新旧混杂
        Util.deleteRecursive(new File(p.dir, ".tmp"));

        int rc;
        boolean needCompat = false;
        // 工程全部 .c 参与编译（入口文件也在其中）
        List<File> sources = new ArrayList<>(p.sources);
        List<File> incDirs = new ArrayList<>(p.incDirs);
        if (!p.hasRuntime) {
            File rtSrc = ProjectImporter.ensureRuntime(ctx, log);
            File[] files = rtSrc.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".c")) {
                        sources.add(f);
                        if (f.getName().equals("mrp_compat.c")) needCompat = true;
                    }
                }
            }
            incDirs.add(rtSrc);
            log.log("工程未自带运行时，已附加内置 mythroad 运行时库（" + sources.size() + " 个源）");
        } else {
            // 工程自带完整运行时（RT_FULL）
            // 1) 仍追加兼容层，补齐工程运行时缺失的
            //    sqrt/atan2/mrc_getSysMem/mrc_getMemoryRemain/raise/sin/cos 等符号
            //    （全部弱符号，工程已有同名强定义时以工程为准）
            File compat = ProjectImporter.ensureCompat(ctx, log);
            if (compat != null && compat.isFile()) {
                sources.add(compat);
                needCompat = true;
                incDirs.add(compat.getParentFile());
                log.log("已附加内置兼容层 mrp_compat.c（弱符号补齐 libm/mythroad 缺件）");
            }
            // 2) 缺失运行时文件自动补齐：官方模板/仓库可能漏传运行时文件
            //    （如 gcc开发模版 缺 uc3_mfont/emoji），工程源码 include 了内置运行时
            //    独有头时，放开头文件查找并自动附加对应实现源文件
            File rtSrc = ProjectImporter.ensureRuntime(ctx, log);
            incDirs.add(rtSrc);   // 兜底头目录：工程头优先，只命中工程缺失的头
            java.util.Set<String> missing = missingQuotedHeaders(p);
            java.util.List<String> patched = new ArrayList<>();
            for (String h : missing) {
                String bn = h;
                int slash = Math.max(h.lastIndexOf('/'), h.lastIndexOf('\\'));
                if (slash >= 0) bn = h.substring(slash + 1);
                File hf = new File(rtSrc, bn);
                if (!hf.isFile()) continue;
                String base = bn.length() > 2 ? bn.substring(0, bn.length() - 2) : bn;
                File cf = new File(rtSrc, base + ".c");
                if (cf.isFile() && !hasSource(p, base + ".c")) {
                    sources.add(cf);
                    patched.add(base + ".c");
                }
            }
            if (!patched.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < patched.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(patched.get(i));
                }
                log.log("[补齐] 工程缺失运行时文件，已附加内置实现: " + sb);
            }
        }
        log.log("入口: " + p.entry.getName() + "，参与编译 " + sources.size() + " 个 .c");
        rc = MrpCompiler.run(toolDir, gccPath, p.dir, libgcc, sources, incDirs, null, log::log);
        if (rc != 0) throw new BuildException("gcc 编译失败（退出码 " + rc + "），详见上方日志");
        if (!binElf.isFile() || binElf.length() == 0) throw new BuildException("未生成 bin.elf");

        log.log("");
        log.log("--- bin.elf 校验 ---");
        ElfChecker.Result er = ElfChecker.check(binElf);
        log.log(er.info.toString().trim());
        if (!er.notes.isEmpty()) {
            for (String n : er.notes) log.log("[提示] " + n);
        }
        if (!er.problems.isEmpty()) {
            for (String pr : er.problems) log.log("[错误] " + pr);
            throw new BuildException("bin.elf 不满足 MRP 加载要求（加载器只支持 R_ARM_RELATIVE 静态 PIE）");
        }
        log.log("校验通过: " + binElf.getName() + " (" + Util.humanSize(binElf.length()) + ")");
        if (needCompat) log.log("[提示] 已链接 mrp_compat.c（memcpy/memset/sqrt 等实现）");
        return binElf;
    }

    /** 扫描工程 .c 的 #include "x.h"（引号形式），返回工程内找不到的头文件名集合。
     *  尖括号 include（<x.h>）为工具链/系统头，不做补齐。 */
    private static java.util.Set<String> missingQuotedHeaders(Project p) {
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        java.util.regex.Pattern pat =
                java.util.regex.Pattern.compile("(?m)^\\s*#\\s*include\\s+\"([^\"]+)\"");
        for (File f : p.sources) {
            String t;
            try {
                t = new String(Util.readAll(new java.io.FileInputStream(f)),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                continue;
            }
            java.util.regex.Matcher m = pat.matcher(t);
            while (m.find()) {
                String h = m.group(1);
                File rel = new File(h);
                boolean found = false;
                for (File d : p.incDirs) {
                    if (new File(d, rel.getPath()).isFile()) { found = true; break; }
                }
                if (!found) missing.add(h);
            }
        }
        return missing;
    }

    /** 工程源码里是否存在同名源文件（避免重复编译） */
    private static boolean hasSource(Project p, String name) {
        for (File f : p.sources) if (f.getName().equals(name)) return true;
        return false;
    }

    /** 二阶段：打包，返回 .mrp 字节 */
    public static byte[] pack(Context ctx, Project p, File binElf,
                              MrpPacker.Meta meta, List<String> checkedResources) throws Exception {
        // 1. 必备三项数据源
        byte[] startMr = readShell(ctx, p.libStartMr, "lib/start.mr");
        byte[] cfunction = readShell(ctx, p.libCfunctionExt, "lib/cfunction.ext");
        byte[] elf = Util.readAll(new java.io.FileInputStream(binElf));

        List<MrpPacker.Item> items = new ArrayList<>();
        items.add(new MrpPacker.Item("start.mr", startMr));
        items.add(new MrpPacker.Item("bin.elf", elf));

        // 2. 勾选的资源（保持工程里的相对路径名）
        if (checkedResources != null) {
            for (String rel : checkedResources) {
                File f = new File(p.dir, rel);
                if (f.isFile()) {
                    items.add(new MrpPacker.Item(rel.replace('\\', '/'),
                            Util.readAll(new java.io.FileInputStream(f))));
                }
            }
        }
        items.add(new MrpPacker.Item("cfunction.ext", cfunction));
        return MrpPacker.pack(meta, items);
    }

    /** 读取壳文件：工程 lib/ 优先，否则用内置 assets */
    private static byte[] readShell(Context ctx, File projectFile, String assetPath) throws IOException {
        if (projectFile != null && projectFile.isFile()) {
            return Util.readAll(new java.io.FileInputStream(projectFile));
        }
        try (java.io.InputStream in = ctx.getAssets().open(assetPath)) {
            return Util.readAll(in);
        }
    }

    /** 工程内所有可勾选资源（相对路径） */
    public static List<String> resourceRelPaths(Project p) {
        List<String> out = new ArrayList<>();
        String base = p.dir.getAbsolutePath();
        for (File f : p.resources) {
            String abs = f.getAbsolutePath();
            if (abs.startsWith(base + File.separator)) {
                out.add(abs.substring(base.length() + 1).replace('\\', '/'));
            }
        }
        return out;
    }
}
