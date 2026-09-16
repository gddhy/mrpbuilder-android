package net.gddhy.mrpbuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * bin.elf 校验（对应 web 版 tools/check-elf.py 的判定逻辑）。
 *
 * MRP 壳（start.mr + cfunction.ext）里的 ELF 加载器只处理 R_ARM_RELATIVE
 * 重定位，不加载 DT_NEEDED 依赖库，也不处理 DT_JMPREL。因此一个可用的
 * bin.elf 必须满足：
 *   1. 32 位小端 ARM ELF
 *   2. e_type == ET_DYN（静态 PIE）
 *   3. 有 PT_DYNAMIC
 *   4. 没有 DT_NEEDED
 *   5. .rel.dyn 里只有 R_ARM_RELATIVE / R_ARM_NONE
 */
public final class ElfChecker {

    private ElfChecker() {}

    private static final int ET_DYN = 3;
    private static final int EM_ARM = 40;
    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final int DT_NULL = 0;
    private static final int DT_NEEDED = 1;
    private static final int DT_PLTRELSZ = 2;
    private static final int DT_REL = 17;
    private static final int DT_RELSZ = 18;
    private static final int DT_RELENT = 19;
    private static final int DT_JMPREL = 23;
    private static final int R_ARM_NONE = 0;
    private static final int R_ARM_RELATIVE = 23;

    /** 校验结果：problems 为空即通过 */
    public static final class Result {
        public final List<String> problems = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public final StringBuilder info = new StringBuilder();
        public boolean passed() { return problems.isEmpty(); }
    }

    public static Result check(File elf) throws IOException {
        return check(Util.readAll(new java.io.FileInputStream(elf)));
    }

    public static Result check(byte[] data) throws IOException {
        Result r = new Result();
        if (data.length < 52 || data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F') {
            r.problems.add("不是 ELF 文件");
            return r;
        }
        int eiClass = data[4] & 0xff, eiData = data[5] & 0xff;
        if (eiClass != 1) { r.problems.add("期望 32 位 ELF，实际 class=" + eiClass); }
        if (eiData != 1) { r.problems.add("期望小端序，实际 data=" + eiData); }
        if (eiClass != 1 || eiData != 1) return r;

        int eType = le16(data, 16);
        int eMachine = le16(data, 18);
        long ePhoff = le32(data, 28);
        int ePhentsize = le16(data, 42);
        int ePhnum = le16(data, 44);

        r.info.append("架构: ").append(eMachine == EM_ARM ? "ARM" : ("unknown(" + eMachine + ")")).append('\n');
        r.info.append("类型: ").append(eType == ET_DYN ? "ET_DYN（静态 PIE，符合要求）" : ("e_type=" + eType + " —— 加载器要求 ET_DYN")).append('\n');
        r.info.append("入口: 0x").append(Long.toHexString(le32(data, 24))).append('\n');

        if (eMachine != EM_ARM) { r.problems.add("不支持的架构（需要 ARM）"); return r; }

        // 收集 PT_LOAD 与 PT_DYNAMIC
        long dynOff = -1, dynSize = 0;
        final List<long[]> loads = new ArrayList<>();
        for (int i = 0; i < ePhnum; i++) {
            int off = (int) (ePhoff + (long) i * ePhentsize);
            if (off + 32 > data.length) break;
            long pType = le32(data, off);
            long pOffset = le32(data, off + 4);
            long pVaddr = le32(data, off + 8);
            long pFilesz = le32(data, off + 16);
            if (pType == PT_LOAD) {
                loads.add(new long[]{pVaddr, pOffset, pFilesz});
            } else if (pType == PT_DYNAMIC) {
                dynOff = pOffset;
                dynSize = pFilesz;
            }
        }
        if (dynOff < 0) { r.problems.add("没有 PT_DYNAMIC —— 加载器靠它做重定位，必须有"); return r; }

        final long[] dyn = {dynOff, dynSize};
        // 解析 dynamic 标签
        java.util.Map<Long, Long> tags = new java.util.LinkedHashMap<>();
        for (long i = 0; i + 8 <= dynSize; i += 8) {
            long tag = le32(data, (int) (dynOff + i));
            long val = le32(data, (int) (dynOff + i + 4));
            if (tag == DT_NULL) break;
            tags.put(tag, val);
        }
        r.info.append("动态段标签: ").append(tags.size()).append(" 个\n");
        if (tags.containsKey((long) DT_NEEDED)) {
            r.problems.add("存在 DT_NEEDED（依赖外部库），加载器不会加载依赖");
        }
        if (tags.containsKey((long) DT_JMPREL)) {
            r.notes.add("存在 DT_JMPREL（加载器不读它，只要里面没有 JUMP_SLOT/GLOB_DAT 就无碍）");
        }

        // 统计重定位类型
        java.util.Map<Long, Integer> rel = countRelocs(data, loads, tags.get((long) DT_REL), tags.get((long) DT_RELSZ), tags.get((long) DT_RELENT), r, ".rel.dyn");
        countRelocs(data, loads, tags.get((long) DT_JMPREL), tags.get((long) DT_PLTRELSZ), 8L, r, ".rel.plt");

        StringBuilder sb = new StringBuilder(".rel.dyn 重定位: ");
        int total = 0;
        for (java.util.Map.Entry<Long, Integer> e : rel.entrySet()) {
            sb.append(typeName(e.getKey())).append('x').append(e.getValue()).append(' ');
            total += e.getValue();
            if (e.getKey() != R_ARM_NONE && e.getKey() != R_ARM_RELATIVE) {
                r.problems.add(".rel.dyn 含非 RELATIVE 重定位: " + typeName(e.getKey()) + "×" + e.getValue());
            }
        }
        if (total == 0) sb.append("（无）");
        r.info.append(sb).append('\n');

        return r;
    }

    private static java.util.Map<Long, Integer> countRelocs(byte[] data, List<long[]> loads,
                                                            Long tableVaddr, Long tableSize, Long entrySize,
                                                            Result r, String label) {
        java.util.Map<Long, Integer> counts = new java.util.LinkedHashMap<>();
        if (tableVaddr == null || tableSize == null || tableSize == 0) return counts;
        long off = vaddrToOff(loads, tableVaddr);
        if (off < 0) {
            r.problems.add(label + " 的虚拟地址无法映射到文件偏移");
            return counts;
        }
        long esz = entrySize == null ? 8 : entrySize;
        for (long i = 0; i + esz <= tableSize; i += esz) {
            long rInfo = le32(data, (int) (off + i + 4));
            long type = rInfo & 0xff;
            counts.put(type, counts.getOrDefault(type, 0) + 1);
        }
        return counts;
    }

    private static long vaddrToOff(List<long[]> loads, long vaddr) {
        for (long[] l : loads) {
            if (l[0] <= vaddr && vaddr < l[0] + l[2]) {
                return l[1] + (vaddr - l[0]);
            }
        }
        return -1;
    }

    private static String typeName(long t) {
        switch ((int) t) {
            case 0: return "R_ARM_NONE";
            case 23: return "R_ARM_RELATIVE";
            case 21: return "R_ARM_GLOB_DAT";
            case 22: return "R_ARM_JUMP_SLOT";
            default: return "type=" + t;
        }
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long le32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }
}
