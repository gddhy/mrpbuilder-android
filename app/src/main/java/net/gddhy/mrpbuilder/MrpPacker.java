package net.gddhy.mrpbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

/**
 * MRP 容器打包器 —— 复刻社区 mrpbuilder（Go 版 mrp.go / web 版 mrp-pack.js）的格式。
 *
 * 容器结构（全部小端）：
 *   240 字节固定文件头（"MRPG" 魔数、fileStart、总长度、显示名/内部名/appid/版本/开发者/介绍、
 *   CRC32、plat=1 等）
 *   文件列表区（每项：名称长度 u32 + 名称+\0 + 偏移 u32 + 长度 u32 + 保留 u32）
 *   文件数据区（每项：名称长度 u32 + 名称+\0 + 长度 u32 + gzip 数据）
 *
 * 已知细节：
 *   - fileStart = 240 + 列表区长度 - 8（社区实测必须减 8）
 *   - CRC32 对整文件计算，计算时 CRC 字段置 0
 *   - 每个文件单独 gzip（start.mr / bin.elf / 资源 / cfunction.ext 都压）
 *   - 名称/显示名/开发者/介绍按 GBK 编码、定长截断
 */
public final class MrpPacker {

    private MrpPacker() {}

    public static final class Meta {
        public String fileName;   // 内部名（.mrp 文件名，如 demo.mrp）
        public String display;    // 显示名
        public long appid;        // appid
        public long version;      // 版本
        public String vendor;     // 开发者
        public String desc;       // 介绍
    }

    public static final class Item {
        public final String name;
        public final byte[] data;
        public Item(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    private static byte[] gzip(byte[] in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(in.length / 2 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(in);
        }
        return bos.toByteArray();
    }

    private static byte[] u32(long v) {
        return new byte[]{
                (byte) (v & 0xff), (byte) ((v >> 8) & 0xff),
                (byte) ((v >> 16) & 0xff), (byte) ((v >> 24) & 0xff)};
    }

    private static byte[] u16(int v) {
        return new byte[]{(byte) (v & 0xff), (byte) ((v >> 8) & 0xff)};
    }

    private static byte[] be32(long v) {
        return new byte[]{
                (byte) ((v >> 24) & 0xff), (byte) ((v >> 16) & 0xff),
                (byte) ((v >> 8) & 0xff), (byte) (v & 0xff)};
    }

    private static byte[] nameZ(String name) {
        byte[] b = Util.gbkBytes(name);
        byte[] z = new byte[b.length + 1];
        System.arraycopy(b, 0, z, 0, b.length);
        return z;
    }

    /** 写定长 GBK 字段：不足补 0（缓冲区已清零），超长截断 */
    private static void writeField(byte[] dst, int off, int len, String s) {
        byte[] b = Util.gbkFixed(s, len);
        System.arraycopy(b, 0, dst, off, Math.min(b.length, len));
    }

    public static byte[] pack(Meta meta, List<Item> items) throws IOException {
        // 1. 每个文件单独 gzip
        byte[][] gz = new byte[items.size()][];
        long listLen = 0, dataLen = 0;
        for (int i = 0; i < items.size(); i++) {
            byte[] name = nameZ(items.get(i).name);
            gz[i] = gzip(items.get(i).data);
            listLen += name.length + 16;
            dataLen += name.length + 8 + gz[i].length;
        }
        if (listLen > Integer.MAX_VALUE || dataLen > Integer.MAX_VALUE) {
            throw new IOException("打包内容过大");
        }
        int lLen = (int) listLen, dLen = (int) dataLen;
        long headerSize = 240;
        long totalLen = headerSize + lLen + dLen;
        long fileStart = headerSize + lLen - 8;

        byte[] header = new byte[240];
        // magic
        header[0] = 'M'; header[1] = 'R'; header[2] = 'P'; header[3] = 'G';
        System.arraycopy(u32(fileStart), 0, header, 4, 4);
        System.arraycopy(u32(totalLen), 0, header, 8, 4);
        System.arraycopy(u32(240), 0, header, 12, 4);
        // 16: 内部名 12B（GBK，超长截断、余位补 0）
        writeField(header, 16, 12, meta.fileName);
        // 28: 显示名 24B
        writeField(header, 28, 24, meta.display);
        // 52: authStr 16B 全零
        // 68: appid
        System.arraycopy(u32(meta.appid & 0xffffffffL), 0, header, 68, 4);
        // 72: version
        System.arraycopy(u32(meta.version & 0xffffffffL), 0, header, 72, 4);
        // 76: flag = 显示(1) + CPU(3)<<1 + shell(0)<<3 = 7
        System.arraycopy(u32(7), 0, header, 76, 4);
        // 80: builderVersion
        System.arraycopy(u32(10002), 0, header, 80, 4);
        // 84: crc 置 0
        // 88: 开发者 40B
        writeField(header, 88, 40, meta.vendor);
        // 128: 介绍 64B
        writeField(header, 128, 64, meta.desc);
        // 192: appidBE / 196: versionBE
        System.arraycopy(be32(meta.appid & 0xffffffffL), 0, header, 192, 4);
        System.arraycopy(be32(meta.version & 0xffffffffL), 0, header, 196, 4);
        // 200: 保留
        // 204: 屏宽 0, 206: 屏高 0
        System.arraycopy(u16(0), 0, header, 204, 2);
        System.arraycopy(u16(0), 0, header, 206, 2);
        // 208: plat = 1 (mtk/mstar)
        header[208] = 1;
        // 209..239: 保留 31B 全零

        // 2. 文件列表区
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        long filePos = headerSize + lLen;
        long[] pos = new long[items.size()];
        for (int i = 0; i < items.size(); i++) {
            byte[] name = nameZ(items.get(i).name);
            filePos += name.length + 8;
            pos[i] = filePos;
            filePos += gz[i].length;
            list.write(u32(name.length));
            list.write(name);
            list.write(u32(pos[i]));
            list.write(u32(gz[i].length));
            list.write(u32(0));
        }

        // 3. 文件数据区
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < items.size(); i++) {
            byte[] name = nameZ(items.get(i).name);
            data.write(u32(name.length));
            data.write(name);
            data.write(u32(gz[i].length));
            data.write(gz[i]);
        }

        // 4. 拼装 + CRC
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header);
        out.write(list.toByteArray());
        out.write(data.toByteArray());
        byte[] buf = out.toByteArray();

        CRC32 crc = new CRC32();
        crc.update(buf);
        long crcVal = crc.getValue();
        buf[84] = (byte) (crcVal & 0xff);
        buf[85] = (byte) ((crcVal >> 8) & 0xff);
        buf[86] = (byte) ((crcVal >> 16) & 0xff);
        buf[87] = (byte) ((crcVal >> 24) & 0xff);
        return buf;
    }
}
