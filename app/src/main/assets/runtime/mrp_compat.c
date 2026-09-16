/*
 * mrp_compat.c —— gcc 路线下必须显式补齐的“C 库 / libm / mythroad 缺件”
 *
 * 为什么需要这个文件
 * ------------------
 * MRP 壳（start.mr + cfunction.ext）里的 ELF 加载器只实现了 R_ARM_RELATIVE
 * 重定位，所以编译必须带 -nostdlib -nostartfiles，不链接任何 C 库 / libm。
 * 但工程源码里有三类东西会隐式依赖它们，链接期表现为
 * "undefined reference to ..."：
 *
 *   1) GCC 在 -O2 下会把「手写循环」识别成标准库调用后直接发调用指令
 *      （tree-loop-distribute-patterns + 内建函数优化）：
 *          for (...) dst[i] = src[i];   ->  memmove / memcpy
 *          while (*p++) len++;          ->  strlen
 *          数组填充 / 结构体清零         ->  memset
 *      其中结构体拷贝与清零属于 ABI 约定，编译器无论如何都会发调用，
 *      加 -fno-builtin 也挡不掉，唯一可靠的办法是自己提供实现。
 *
 *   2) bitmap.c 直接 #include <math.h>，调用了 sqrt / atan2。
 *
 *   3) mrc_getSysMem / mrc_getMemoryRemain 在 mrc_base.h 里有声明，
 *      全工程却没有实现 —— armcc 时代这两个由厂商 .lib 提供。
 *      在 mythroad 里它们就是读 mr_table 的 LG_mem_len / LG_mem_left
 *      这两个「指向值的指针」字段，这里按同样语义接上。
 *
 * 所有实现都是纯算术，不引入任何外部依赖，
 * 产物的动态重定位依然只有 R_ARM_RELATIVE（用 tools/check-elf.py 校验）。
 */

#include "mrc_base.h"

/* size_t 在 SDK 里没有统一定义（base.h 与 mpc.h 各有一份，且有符号差异），
   这里自带一个。ARM EABI 下 size_t 就是 unsigned int，与编译器优化后
   生成的调用完全一致；C 函数链接不做名字修饰，符号能准确对上。 */
typedef unsigned int mrp_size_t;

/* 所有补齐符号一律声明为弱符号：
   - 工程 / 自带运行时若已有同名强定义（例如 mrc_base.c 自己实现了 memcpy），
     链接器以强定义为准，不会报 multiple definition；
   - 工程缺失这些符号时（最常见的：自带 mythroad 运行时的工程也往往不实现
     sqrt/atan2/mrc_getSysMem 等），由这里兜底，保证链接不失败。 */
#define MRP_WEAK __attribute__((weak))

/* =========================================================================
 * 一、编译器内建函数降级产生的 libc 调用
 * ========================================================================= */

MRP_WEAK void *memset(void *dst, int c, mrp_size_t n)
{
    unsigned char *d = (unsigned char *)dst;
    unsigned char v = (unsigned char)c;

    while (n--)
        *d++ = v;

    return dst;
}

MRP_WEAK void *memmove(void *dst, const void *src, mrp_size_t n)
{
    unsigned char *d = (unsigned char *)dst;
    const unsigned char *s = (const unsigned char *)src;

    if (d == s || n == 0)
        return dst;

    if (d < s)
    {
        /* 目标在前，正序拷贝 */
        while (n--)
            *d++ = *s++;
    }
    else
    {
        /* 目标在后（有重叠），倒序拷贝 */
        d += n;
        s += n;
        while (n--)
            *--d = *--s;
    }

    return dst;
}

MRP_WEAK mrp_size_t strlen(const char *s)
{
    const char *p = s;

    while (*p)
        p++;

    return (mrp_size_t)(p - s);
}

/* 说明：memcpy 已在 mrc_base.c 里实现，这里不重复定义。 */

/*
 * abs / labs —— mrc_base.c 的 mrc_drawLine 会用到。
 * 大多数情况下 GCC 会把 abs() 内联成 cmp/rsb，根本不会发调用；
 * 但一旦内联条件不满足（例如 -fno-builtin），它就会退化成外部调用，
 * 所以这里补上一份，让链接结果不依赖编译器的内联决策。
 */
MRP_WEAK int abs(int v)
{
    return v < 0 ? -v : v;
}

MRP_WEAK long labs(long v)
{
    return v < 0 ? -v : v;
}

/* =========================================================================
 * 二、libm：bitmap.c 用到的软浮点数学函数
 *
 * ARM7TDMI（MT6225/6235 等）没有 FPU，double 运算由 libgcc 的软浮点例程
 * 实现（Makefile 里已显式链入 libgcc.a）。这里只用四则运算，不额外引入依赖。
 * ========================================================================= */

#define MRP_PI 3.14159265358979323846

static double mrp_fabs(double v)
{
    return v < 0.0 ? -v : v;
}

/*
 * 牛顿迭代求平方根。
 * 初值用「指数减半、尾数保留」的位技巧得到（误差 < 10%），
 * 之后每次迭代精度翻倍，6 次足以收敛到 double 的极限精度。
 */
MRP_WEAK double sqrt(double x)
{
    union
    {
        double d;
        struct
        {
            uint32 lo; /* 低位在前：ARM 小端 */
            uint32 hi;
        } w;
    } v;
    double g;
    int i;

    /* 这一写法同时覆盖 x == 0、x < 0 与 NaN */
    if (!(x > 0.0))
        return 0.0;

    v.d = x;
    /*
     * 原指数 E（含偏移 1023），新指数 E' = (E + 1023) / 2：
     *   x = 2^k * 1.m  ->  sqrt(x) ≈ 2^(k/2)，E = k + 1023
     *   E' = k/2 + 1023 = (E + 1023) / 2
     */
    v.w.hi = (v.w.hi & 0x800FFFFFu) |
             (((((v.w.hi >> 20) & 0x7FFu) + 1023u) >> 1) << 20);
    g = v.d;

    for (i = 0; i < 6; i++)
        g = 0.5 * (g + x / g);

    return g;
}

/*
 * atan2 的多项式近似（经典三次拟合，误差约 0.01 rad）。
 * 对图形绘制（旋转、扇形、阴影）足够；不追求 libm 的 1ulp 精度。
 */
MRP_WEAK double atan2(double y, double x)
{
    double ay, r, angle;

    if (x == 0.0 && y == 0.0)
        return 0.0;

    ay = mrp_fabs(y);

    if (x >= 0.0)
    {
        /* x + ay > 0（等号只在两者都为 0 时成立，已提前返回） */
        r = (x - ay) / (x + ay);
        angle = 0.1963 * r * r * r - 0.9817 * r + MRP_PI / 4.0;
    }
    else
    {
        /* x < 0，ay - x > 0 */
        r = (x + ay) / (ay - x);
        angle = 0.1963 * r * r * r - 0.9817 * r + 3.0 * MRP_PI / 4.0;
    }

    return (y < 0.0) ? -angle : angle;
}

/*
 * sin / cos —— 老 SDK 工程（如「合成大金鱼」）的绘图代码直接调用 math.h 的
 * sin()/cos()（armcc 时代由 ADS 的 math 库提供）。这里用「[-pi/4, pi/4]
 * 泰勒展开 + 象限归约」实现，角度范围 [-2pi, 2pi] 内误差 < 0.1%，
 * 对功能机游戏绘图足够。fabs/fabsf 一并补齐（libm 符号）。
 */
MRP_WEAK double fabs(double v)
{
    return v < 0.0 ? -v : v;
}

MRP_WEAK float fabsf(float v)
{
    return v < 0.0f ? -v : v;
}

/* x 已归约到 [-pi/4, pi/4] 的 sin 泰勒展开（x^7 阶） */
static double mrp_sinq(double x)
{
    double x2 = x * x;
    return x * (1.0 + x2 * (-1.0 / 6.0 + x2 * (1.0 / 120.0 + x2 * (-1.0 / 5040.0))));
}

/* x 已归约到 [-pi/4, pi/4] 的 cos 泰勒展开（x^6 阶） */
static double mrp_cosq(double x)
{
    double x2 = x * x;
    return 1.0 + x2 * (-0.5 + x2 * (1.0 / 24.0 + x2 * (-1.0 / 720.0)));
}

MRP_WEAK double sin(double x)
{
    const double pi = MRP_PI;
    while (x > pi) x -= 2.0 * pi;
    while (x < -pi) x += 2.0 * pi;
    if (x >= 0.0)
    {
        if (x <= pi / 4.0) return mrp_sinq(x);
        if (x <= 3.0 * pi / 4.0) return mrp_cosq(pi / 2.0 - x);
        return mrp_sinq(pi - x);
    }
    else
    {
        if (x >= -pi / 4.0) return -mrp_sinq(-x);
        if (x >= -3.0 * pi / 4.0) return -mrp_cosq(pi / 2.0 + x);
        return -mrp_sinq(pi + x);
    }
}

MRP_WEAK double cos(double x)
{
    return sin(x + MRP_PI / 2.0);
}

/* =========================================================================
 * 三、mythroad 缺件：主内存总量 / 剩余量
 *
 * mr_table 中 LG_mem_len 是「VM 内存大小」、LG_mem_left 是「VM 剩余内存」，
 * 都是 int32*（指向值的指针），由壳在启动时填好。
 * ========================================================================= */

MRP_WEAK int32 mrc_getSysMem(void)
{
    int32 v;

    if (!mr_table || !mr_table->LG_mem_len)
        return 0;

    v = *mr_table->LG_mem_len;
    return v > 0 ? v : 0;
}

MRP_WEAK MRP_WEAK uint32 mrc_getMemoryRemain(void)
{
    int32 v;

    if (!mr_table || !mr_table->LG_mem_left)
        return 0;

    v = *mr_table->LG_mem_left;
    return v > 0 ? (uint32)v : 0;
}

/* =========================================================================
 * 四、libgcc 异常路径
 *
 * libgcc.a 的 __aeabi_idiv0（除零处理）内部会调用 raise(SIGFPE)。
 * MRP 壳没有信号机制，这里提供空实现：除零不再链接失败（行为与 armcc
 * 时代一致，结果未定义但不崩溃）。
 * ========================================================================= */

MRP_WEAK int raise(int sig)
{
    (void)sig;
    return 0;
}

/* =========================================================================
 * 五、官方模板漏传的 uc3 位图绘制
 *
 * 部分官方模板（如 MrpProjects 的 gcc开发模版）的 uc3_font.c 只有
 * drawBitmapBit 的 extern 声明与调用，漏掉了实现本体（仓库缺文件）。
 * uc3_mfont.c 依赖它绘制点阵字体。这里用弱符号兜底：
 *  - 工程 / 内置运行时已有强定义（内置 uc3_font.c 自带）时以强定义为准；
 *  - 工程缺失时由本实现补齐，保证链接通过。
 * getFontPixel 由各 uc3_font.c 提供（非 static），mrc_drawPointEx 由
 * mrc_base.c 提供，本函数只做“逐点判断 + 打点”，不引入新依赖。
 * ========================================================================= */

extern int getFontPixel(uint8 *buf, int n);

MRP_WEAK void drawBitmapBit(uint8 *font, int x, int y, int w, int h,
                            uint8 r, uint8 g, uint8 b)
{
    int n = 0;
    int iy, bit;

    for (iy = y; iy < y + h; iy++)
    {
        int ix = x;
        for (bit = 0; bit < w; bit++)
        {
            if (getFontPixel(font, n++) >= 1)
                mrc_drawPointEx(ix, iy, r, g, b);
            ix++;
        }
    }
}

/* =========================================================================
 * 六、64 位移位例程（__aeabi_llsl/llsr/lasr）
 *
 * arm-linux-androideabi 的 libgcc.a 里 __aeabi_llsl/llsr/lasr 依赖 bionic
 * （NDK 的 libgcc 不自带），工程代码一旦出现 64 位移位（如时间戳换算、
 * 哈希、随机数），链接就会报 undefined reference。这里按 AAPCS 语义补齐：
 *   __aeabi_llsl : long long <<  (逻辑左移)
 *   __aeabi_llsr : unsigned long long >> (逻辑右移)
 *   __aeabi_lasr : long long >>  (算术右移，符号扩展)
 * 均为弱符号；工程若自带实现（罕见）以工程为准。
 * ========================================================================= */

MRP_WEAK long long __aeabi_llsl(long long value, int shift)
{
    unsigned int lo = (unsigned int)value;
    unsigned int hi = (unsigned int)((unsigned long long)value >> 32);
    unsigned int rlo, rhi;

    shift &= 63;
    if (shift == 0)
    {
        rlo = lo;
        rhi = hi;
    }
    else if (shift < 32)
    {
        rlo = lo << shift;
        rhi = (hi << shift) | (lo >> (32 - shift));
    }
    else
    {
        rlo = 0;
        rhi = lo << (shift - 32);
    }
    return ((unsigned long long)rhi << 32) | rlo;
}

MRP_WEAK unsigned long long __aeabi_llsr(unsigned long long value, int shift)
{
    unsigned int lo = (unsigned int)value;
    unsigned int hi = (unsigned int)(value >> 32);
    unsigned int rlo, rhi;

    shift &= 63;
    if (shift == 0)
    {
        rlo = lo;
        rhi = hi;
    }
    else if (shift < 32)
    {
        rlo = (lo >> shift) | (hi << (32 - shift));
        rhi = hi >> shift;
    }
    else
    {
        rlo = hi >> (shift - 32);
        rhi = 0;
    }
    return ((unsigned long long)rhi << 32) | rlo;
}

MRP_WEAK long long __aeabi_lasr(long long value, int shift)
{
    unsigned int lo = (unsigned int)value;
    unsigned int hi = (unsigned int)((unsigned long long)value >> 32);
    unsigned int rlo, rhi;

    shift &= 63;
    if (shift == 0)
    {
        rlo = lo;
        rhi = hi;
    }
    else if (shift < 32)
    {
        rlo = (lo >> shift) | (hi << (32 - shift));
        rhi = (unsigned int)((int)hi >> shift);
    }
    else
    {
        rlo = (unsigned int)((int)hi >> (shift - 32));
        rhi = (unsigned int)((int)hi >> 31);
    }
    return ((unsigned long long)rhi << 32) | rlo;
}
