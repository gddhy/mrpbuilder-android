package net.gddhy.mrpbuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 一个 mrp 工程（应用私有目录下的镜像） */
public final class Project {

    public File dir;
    public String name;
    public File entry;                 // 入口 .c
    public List<File> sources = new ArrayList<>();   // 参与编译的 .c
    public List<File> incDirs = new ArrayList<>();   // -I 目录
    public List<File> resources = new ArrayList<>(); // 可打入 mrp 的资源文件
    public File libStartMr;            // 工程自带壳文件（可为 null）
    public File libCfunctionExt;       // 工程自带壳文件（可为 null）
    public boolean hasRuntime;         // 工程是否自带 mythroad 运行时（_start）
    public int runtimeMode = RT_NONE;  // 运行时模式（见 RT_*）
    public boolean singleFile;         // 单文件模式

    /** 运行时模式：无任何运行时（标准 gcc 工程，编译时自动附加内置运行时） */
    public static final int RT_NONE = 0;
    /** 运行时模式：自带部分运行时但无 _start（ADS1.2/armcc 老 SDK 工程，如合成大金鱼）→ 不支持编译 */
    public static final int RT_PARTIAL = 1;
    /** 运行时模式：自带完整运行时（定义了 _start） */
    public static final int RT_FULL = 2;

    /** 工程类型文字描述（日志/界面提示用） */
    public String typeDesc() {
        switch (runtimeMode) {
            case RT_FULL: return "gcc 工程（自带完整运行时 _start）";
            case RT_NONE: return "gcc 工程（无自带运行时，将附加内置运行时库）";
            default: return "老 SDK 工程（ADS1.2/armcc，不支持编译）";
        }
    }

    /** 是否为可编译的 gcc 工程（老 SDK 工程不支持） */
    public boolean isGccProject() {
        return runtimeMode != RT_PARTIAL;
    }

    /** 编译时排除的目录名 */
    private static final Set<String> SKIP_DIRS =
            new HashSet<>(Arrays.asList(".git", ".gradle", ".idea", "build", "out", "bin", "lib"));
    /** 目录树导入时额外排除 */
    private static final Set<String> TREE_SKIP =
            new HashSet<>(Arrays.asList(".git", ".gradle", ".idea", "build"));

    /** 内置 mythroad 运行时文件名（与 assets/demo/src 一致）。
     *  这些文件实现 mrp 运行时库（绘图/字体/内存/文件等），不可能是入口程序，
     *  入口选择弹窗与智能判断都会跳过它们。 */
    private static final Set<String> RUNTIME_SRC = new HashSet<>(Arrays.asList(
            "base.c", "bitmap.c", "display_object.c", "emoji.c", "encode.c",
            "mpc.c", "mrc_android.c", "mrc_base.c", "mrc_graphics.c",
            "mrc_image.c", "mrc_ram.c", "mrc_sound.c", "mrc_win.c",
            "uc3_font.c", "uc3_mfont.c", "xl_bmp.c", "xl_coding.c",
            "xl_debug.c", "buffer.c", "fopen.c"));

    /** 是否为运行时实现文件（不可能是入口程序）。
     *  规则：mrc_/xl_/uc3_/mrp_ 前缀（覆盖各代运行时命名），
     *  或命中内置运行时文件名清单。 */
    public static boolean isRuntimeSource(File f) {
        String n = f.getName().toLowerCase();
        if (n.startsWith("mrc_") || n.startsWith("xl_")
                || n.startsWith("uc3_") || n.startsWith("mrp_")) return true;
        return RUNTIME_SRC.contains(n);
    }

    /** 不算「资源」的扩展名（与 web 版 NON_RESOURCE_EXT 一致） */
    private static final Set<String> NON_RESOURCE_EXT = new HashSet<>(Arrays.asList(
            "c", "h", "md", "elf", "o", "obj", "mr", "ext", "json", "txt", "mk"));

    private Project() {}

    /** 扫描一个完整工程目录 */
    public static Project scan(File dir) {
        Project p = new Project();
        p.dir = dir;
        p.name = dir.getName();
        List<File> all = new ArrayList<>();
        walk(dir, all);
        for (File f : all) {
            String n = f.getName().toLowerCase();
            int dot = n.lastIndexOf('.');
            String ext = dot >= 0 ? n.substring(dot + 1) : "";
            if ("c".equals(ext)) {
                p.sources.add(f);
            } else if ("h".equals(ext)) {
                File d = f.getParentFile();
                if (d != null && !p.incDirs.contains(d)) p.incDirs.add(d);
            } else if (!"makefile".equals(n) && !NON_RESOURCE_EXT.contains(ext)) {
                p.resources.add(f);
            }
        }
        // 壳文件：lib/ 目录下
        File libDir = new File(dir, "lib");
        if (libDir.isDirectory()) {
            File[] kids = libDir.listFiles();
            if (kids != null) {
                for (File f : kids) {
                    String n = f.getName().toLowerCase();
                    if ("start.mr".equals(n)) p.libStartMr = f;
                    else if ("cfunction.ext".equals(n)) p.libCfunctionExt = f;
                }
            }
        }
        // 是否自带运行时（定义了 ELF 入口 _start）
        String probe = "outFuncs_st *_start(inFuncs_st *in)";
        for (File f : p.sources) {
            if (fileContains(f, probe)) { p.hasRuntime = true; break; }
        }
        if (p.hasRuntime) {
            p.runtimeMode = RT_FULL;
        } else {
            // 自带部分运行时：无 _start，但自带 mrc_*/xl_*/mpc/uc3_ 等 mythroad 子集
            // （老 SDK 工程，如「合成大金鱼」——自己实现了 mrc_graphics/uc3_font 等）
            for (File f : p.sources) {
                String n = f.getName().toLowerCase();
                if (n.startsWith("mrc_") || n.startsWith("xl_")
                        || n.startsWith("uc3_") || n.equals("mpc.c")) {
                    p.runtimeMode = RT_PARTIAL;
                    break;
                }
            }
        }
        p.entry = detectEntry(p);
        return p;
    }

    /** 单文件模式：把用户选的 .c/.h 当作唯一源码 */
    public static Project single(File file) {
        Project p = new Project();
        p.dir = file.getParentFile();
        p.name = stripExt(file.getName());
        p.incDirs.add(file.getParentFile());
        p.singleFile = true;
        if (file.getName().toLowerCase().endsWith(".c")) {
            p.entry = file;
            p.sources.add(file);
        }
        return p;
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static boolean fileContains(File f, String s) {
        try {
            String t = new String(Util.readAll(new java.io.FileInputStream(f)),
                    java.nio.charset.StandardCharsets.UTF_8);
            return t.contains(s);
        } catch (Exception e) {
            return false;
        }
    }

    /** 入口检测：根目录 main.c → 任意 main.c → 含 mrc_init( 的源 → 含 main( 的源 → 首个业务源。
     *  运行时实现文件（mrc_、xl_、uc3_、mpc.c 等）不可能是入口程序，一律跳过。 */
    private static File detectEntry(Project p) {
        if (!p.sources.isEmpty()) {
            File rootMain = new File(p.dir, "main.c");
            if (rootMain.isFile()) return rootMain;
            for (File f : p.sources) {
                if ("main.c".equalsIgnoreCase(f.getName()) && !isRuntimeSource(f)) return f;
            }
        }
        File mrcInit = null, plainMain = null;
        for (File f : p.sources) {
            if (isRuntimeSource(f)) continue;
            String t = stripComments(fileContains2(f));
            if (t == null) continue;
            if (t.contains("mrc_init(")) { if (mrcInit == null) mrcInit = f; }
            else if (t.contains("main(")) { if (plainMain == null) plainMain = f; }
        }
        if (mrcInit != null) return mrcInit;
        if (plainMain != null) return plainMain;
        for (File f : p.sources) {
            if (!isRuntimeSource(f)) return f;
        }
        return p.sources.isEmpty() ? null : p.sources.get(0);
    }

    private static String fileContains2(File f) {
        try {
            return new String(Util.readAll(new java.io.FileInputStream(f)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 粗略去掉注释/字符串，用于入口检测 */
    static String stripComments(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i += 2;
            } else if (c == '"') {
                sb.append(' ');
                i++;
                while (i < n && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\' && i + 1 < n) i++;
                    i++;
                }
                i++;
            } else if (c == '\'') {
                sb.append(' ');
                i++;
                while (i < n && s.charAt(i) != '\'') {
                    if (s.charAt(i) == '\\' && i + 1 < n) i++;
                    i++;
                }
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static void walk(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                if (!SKIP_DIRS.contains(f.getName())) walk(f, out);
            } else {
                out.add(f);
            }
        }
    }

    public static Set<String> treeSkip() {
        return TREE_SKIP;
    }
}
