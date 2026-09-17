# MRP Builder（安卓端 mrp 编译打包软件）

在 Android 手机上直接编译并打包 **斯凯（Sky）MRP 程序**（适配 MT6225 / MT6235 / MT6250 / MT6276 等
2005~2012 年左右的 MTK 功能机）的工具。

- **不申请任何存储权限**：源文件、文件夹通过 SAF（存储访问框架）读取，产物通过 SAF 保存，
  模拟器运行 / 分享通过 FileProvider 传递。
- **minSdk 24 / targetSdk 28 / compileSdk 32 / AGP 7.3.1**。
  targetSdk 28 是为了保留从应用私有目录直接执行原生二进制（gcc）的能力
  （Android 10 起对 data 目录施加 W^X 限制，targetSdk > 28 将无法运行解压出来的工具链）。
- 内置完整 `arm-linux-androideabi-gcc` 工具链（约 26MB，随 APK 分发，首次编译时自动解压）。
- 内置 `mrp_demo`（gcc 工程 Demo）与 `start.mr` / `cfunction.ext` 壳文件，开箱即可验证全流程；
  Demo 同时作为其他 gcc 项目的基础库依赖（mythroad 运行时库）。
- **仅支持 gcc 工程**：检测到 ADS1.2/armcc 老 SDK 工程（无 `_start`、自带部分运行时）
  时直接提示「不支持编译」。

---

## 编译 / 打包原理

MRP 程序由“壳 + 代码 + 资源”组成，gcc 路线分两个阶段：

### 一阶段：gcc 编译出 `bin.elf`

对 `.c` / `.h` 源码调用 `arm-linux-androideabi-gcc`（与社区 arm-none-eabi-gcc 相同参数）编译：

```bash
gcc -o bin.elf <入口.c> <src/*.c> -I<头文件目录> \
    -Os -Wall \
    -Wno-implicit-function-declaration -Wno-implicit-int -Wno-builtin-declaration-mismatch \
    -marm -march=armv5te -mfloat-abi=soft \
    -ffixed-r9 -ffixed-r10 \
    -ffunction-sections -fdata-sections -fno-common -fshort-enums \
    -fPIC -fno-tree-loop-distribute-patterns \
    -nostdlib -nostartfiles -pie -Wl,--entry=_start \
    -Wl,--gc-sections -Wl,--strip-all -Wl,--strip-debug \
    <libgcc.a>
```

要点：

- `-march=armv5te -mfloat-abi=soft`：目标机无 FPU，禁止 VFP 指令，浮点走 libgcc 软浮点例程；
- `-pie -fPIC -nostdlib -nostartfiles`：产出**静态 PIE**，MRP 壳的 ELF 加载器只做
  `R_ARM_RELATIVE` 重定位、不加载依赖库；
- `-ffixed-r9 -ffixed-r10`：**真机关键参数**（详见《编译工具链与真机兼容性》），老 MTK
  平台的 VMF 按 APCS 约定 r9=静态基址、r10=栈限制，禁止 gcc 占用可避免中断破坏壳状态；
- `-Os + -ffunction-sections/-fdata-sections + --gc-sections`：死代码消除，减小体积；
- `-Wl,--strip-all/--strip-debug`：去除符号表（.dynsym 保留，动态段仍只有
  R_ARM_RELATIVE 重定位，真机 elfloader 兼容）；
- `-Wl,--entry=_start`：ELF 入口为 mythroad 运行时实现的 `_start(inFuncs_st *in)`，
  壳启动后跳到这里拿到 outFuncs_st 函数表；
- 不链接任何 C 库。编译器为结构体拷贝/清零、`memcpy` 等发出的 ABI 调用，由内置的
  `mrp_compat.c` 补齐（同时提供 `sqrt` / `atan2` / `sin` / `cos` / `fabs` / `fabsf` /
  `mrc_getSysMem` / `mrc_getMemoryRemain` / `raise` —— 后两个 mythroad 缺件与
  libgcc 除零路径 `__aeabi_idiv0` 需要它）；
- **`mrp_compat.c` 始终参与编译**：工程自带运行时（`mrc_base.c`）时同样追加。
  其全部符号为**弱符号**（`__attribute__((weak))`）——工程已有同名强定义时以工程为准
  （如 `mrc_base.c` 自带 `memcpy`），工程缺失时由兼容层兜底，避免
  `undefined reference to sqrt/atan2/mrc_getSysMem/.../raise` 链接失败。

### 工程检测（仅支持 gcc 工程）

按工程自带的 mythroad 运行时情况自动检测，**只允许 gcc 工程编译**：

**老 SDK 工程**是 ADS1.2 + SkySDK 时代（armcc）的项目，显著特征是包含 **SkySDK 工程配置文件
`*.mpr`**（如 `MrpBuilder.mpr`，Skysdk 据此文件编译 mrp）；无 .mpr 但自带部分运行时
（`mrc_graphics.c / uc3_font.c / xl_*` 等）而无 `_start` 的工程同样判定为老 SDK。
导入后日志与界面会明确提示「不支持编译」，不进入编译流程。

**入口文件选择**：入口弹窗与智能判断会跳过运行时实现文件（`mrc_*`、`xl_*`、`uc3_*`、
`mpc.c`、`buffer.c`、`fopen.c` 等内置运行时同名源），这些文件不可能是入口程序；
候选列表只保留业务源文件（如 `main.c`、`2048.c`、`demo.c` 等）。

### 入口文件智能判断与手动选择

入口程序一般是一个 `main.c`（或含 `main` 函数的 .c），智能判断顺序：

1. 工程根目录的 `main.c`；
2. 任意位置的 `main.c`（如 `src/main.c`）；
3. 含 `mrc_init(` 的源文件（mythroad 入口约定，避开工具库里的测试用 `main()`）；
4. 含 `main(` 的源文件；
5. 以上都没有时取第一个 .c（日志会提示未检测到）。

点击「编译」时弹出**入口文件选择菜单**：列出工程全部 .c（相对路径，默认选中智能判断
结果），可手动改选后编译；单文件导入模式入口固定，不弹菜单。

编译完成后对 `bin.elf` 做校验（对应 web 版 `tools/check-elf.py`）：32 位小端 ARM、
`e_type == ET_DYN`、存在 `PT_DYNAMIC`、无 `DT_NEEDED`、重定位仅含
`R_ARM_RELATIVE / R_ARM_NONE`。不通过则拒绝打包并展示失败原因。

### 二阶段：打包成 `.mrp`

把 **`start.mr` + `bin.elf` + 勾选的资源文件 + `cfunction.ext`** 按 MRP 容器格式打包
（复刻社区 mrpbuilder 的 `mrp.go` / web 版 `mrp-pack.js`）：

```
[240B 文件头] [文件列表区] [文件数据区（每个文件单独 gzip）]
```

- 文件头：`MRPG` 魔数、`fileStart = 240 + 列表长度 - 8`、总长度、内部名(12B)、显示名(24B)、
  AppID、版本、flag=7（显示 + CPU3 + 非启动壳）、builderVersion=10002、CRC32、开发者(40B)、
  介绍(64B)、plat=1（MTK/mstar）等，全部小端；
- 名称 / 显示名 / 开发者 / 介绍按 **GBK** 编码、定长截断；
- CRC32 对整文件计算（计算时 CRC 字段置 0）；
- 文件名固定：`start.mr`、`bin.elf`、`cfunction.ext` 三个文件名不可更改
  （壳内加载器按字符串查找）。

打包时引导用户填写：**显示名 / 内部名（.mrp 文件名）/ AppID / 版本 / 开发者 / 介绍**，
并勾选打入包内的文件：

- `start.mr`、`bin.elf`、`cfunction.ext` **必选**（不可取消）；
- 其他资源文件（图片、字体等）**默认勾选**，可取消，取消后不打包。

**字段校验**（点击「打包」时逐项校验，全部通过才关闭弹窗并开始打包）：

| 字段 | 规则 |
|---|---|
| 显示名 | 不允许为空 |
| 内部名 | 必须为英文（字母/数字/`_`/`-`/`.`，无空格无中文），且以 `.mrp` 结尾 |
| AppID | 不允许为空 |
| 版本 | 不允许为空 |
| 开发者 | 不允许为空 |

校验不通过时仅弹 Toast 提示，弹窗保持打开、已填内容不丢失。

### 产物去向

- **在模拟器中运行**：先把 `.mrp` 复制到公共目录（Android 10+ 走 MediaStore
  `Download/MrpBuilder/`，无需存储权限；Android 7-9 走应用专属外部目录），
  再用 **真实文件路径（file://）** 交给 MRP 模拟器（MrpStore / Mrpoid /
  iacMrp / vmrp 等）打开——旧式模拟器只认真实路径，不认 content://；
  复制失败时自动降级为 FileProvider 的 content://（支持 SAF 的新模拟器）；
- **保存到本地**：SAF 创建文档写入；
- **分享**：通过系统分享发送。

---

## 内置 Demo 与导出

- **载入 Demo**：一键把内置 hello-world 工程（helloworld.c + src 运行时 + assets 资源）
  复制到私有目录并识别，可立即体验「编译 → 打包 → 模拟器运行」全流程；
- **导出 Demo**：把内置 demo 工程打包为 zip（内含工程源码、资源与 **功能机开发规范.md**），
  通过 SAF 选择保存位置导出到本地，可作为新工程起点或移植到 PC 工具链。

> 注：APK 内规范文件以 ASCII 名（assets/gui_fan.md）存放，避免旧版 AAPT 对中文资产名的
> 编码问题（GBK 字节导致 AssetManager 按 UTF-8 匹配失败）；导出 zip 时恢复中文名。

## HTTP 编译服务（API / WebUI）

主界面「API 编译服务」卡片点击 **启动编译服务**，App 在 `0.0.0.0:8111` 监听（本地 + 局域网）：
端口被占用时提示「端口 8111 被占用，无法启用」。

- **WebUI**：浏览器访问 `http://<手机IP>:8111/`，可视化提交 zip 工程 + 元信息 + 入口，实时看日志；
- **接口文档**：`http://<手机IP>:8111/doc/`；
- **技能文档**：`http://<手机IP>:8111/SKILL.md`（内含当前局域网 IP；手机走流量时局域网不可达，
  改用 `127.0.0.1` 仅在手机本机访问）；
- **接口**（均为 HTTP JSON）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/build` | multipart 提交 `file`(zip) + `display`/`fileName`/`appid`/`version`/`vendor`/`desc`/`entry` → `build_id` |
| GET | `/api/build/{id}` | 查询状态 / 阶段 / 进度 / 错误 |
| GET | `/api/build/{id}/log?offset=N` | 增量拉取编译日志 |
| GET | `/api/build/{id}/artifact` | 下载打包好的 `.mrp` 产物 |
| GET | `/api/demo` | 下载内置 demo 工程 zip |
| GET | `/api/builds` | 最近 20 条构建 |

```bash
BASE=http://192.168.1.100:8111   # 换成手机局域网 IP
curl -F "file=@demo.zip" -F "display=我的程序" -F "fileName=app.mrp" \
     -F "appid=10001" -F "version=1" -F "vendor=MrpDev" -F "entry=main.c" \
     $BASE/api/build
# → {"build_id":"a1b2c3d4e5f6","status":"queued","urls":{...}}
curl $BASE/api/build/a1b2c3d4e5f6            # 轮询到 status=success
curl -OJ $BASE/api/build/a1b2c3d4e5f6/artifact  # 下载产物
```

- 元信息校验与 App 内打包一致：显示名非空、内部名英文且以 `.mrp` 结尾、AppID/版本/开发者非空；
- **资源打包**：工程内全部资源（含 `assets/` 等**子目录**中的图片/音频等）默认一并打包进 mrp，
  条目名保留相对路径；zip 条目名已做规范化（`\`→`/`、去前导 `./`），兼容 MT管理器等打包的压缩包；
- 同一时刻串行执行 1 个编译任务（其余排队）；构建记录保留 3 天自动清理；
- 无鉴权，仅供个人 / 局域网使用，**不要暴露到公网**；服务随 App 存活，被杀后需重新启动。

## 使用流程

1. 安装 `app-release.apk`（本仓库 `dist/` 或构建产物）；
2. 选择工程来源：
   - **直接打开 zip 文件**：在文件管理器 / 浏览器里选择「用 MRP Builder 打开」一个 `.zip`，
     应用会自动解压到私有目录并准备编译任务（等同于下面「选择 .zip 包」导入，已关联
     `application/zip` / `application/x-zip-compressed` / `*.zip` 路径）；
   - **选择 .c 文件 / .zip 包**：单个源码文件（自动附加内置运行时库）或完整工程 zip
     （含 `settings.gradle` 之外的标准 gcc 工程：`入口.c + src/*.c + 头文件 + 资源`）；
   - **选择文件夹（SAF 授权）**：直接授权读取整个工程目录；
   - **载入内置 Demo**：一键体验；
   > 导入文件夹 / zip 工程时会**先清理私有目录里该工程的上次缓存**（旧源码、旧的
   > `bin.elf`、`.tmp` 中间产物）再复制 / 解压，避免新旧文件混杂导致编译异常；
   >
   > **zip 安全校验**（SAF 导入与 zip 关联打开共用，`Util.unzip`）：
   > - 防路径穿越（zip-slip）：解压路径 canonical 必须位于目标目录内，`../` 等非法路径直接拒绝
   >   （Android 下符号链接条目只会被解压为普通文本文件，不会创建真实链接，无额外风险）；
   > - 防压缩炸弹：条目数上限 10000、单文件上限 128MB、解压后总量上限 512MB，超限中止并报错；
3. 点击 **编译 bin.elf**（首次会自动解压约 26MB gcc 工具链，耗时数十秒到数分钟）；
   编译前会弹出**入口文件选择菜单**（默认选中智能判断的 main.c / 含 main 的 .c，可改选）；
4. 编译通过后点击 **打包 .mrp**，填写元信息、勾选资源文件；
5. 打包成功后选择 **保存 / 模拟器运行 / 分享**。

> 导入 ADS1.2/armcc 老 SDK 工程时，日志与界面会提示「不支持编译」，仅支持 gcc 工程。

### 日志与排错

- 日志区实时显示工具链、编译、打包全过程；编译/打包失败会展示完整失败日志；
- 日志区下方提供 **复制日志**（复制全部日志）与 **清空** 两个按钮，
  一键复制到剪贴板，方便发帖求助或自行分析。

> 工程结构约定（与 `mrp_demo` 一致）：
> `入口.c`（main.c 或含 `main(` / `mrc_init(` 的文件）、`src/*.c *.h`、`assets/` 等资源目录。
> 运行时按「完整运行时 / 无运行时」两态适配；老 SDK 工程（自带部分运行时但无 `_start`）不支持编译。

---

## 项目结构

```
MrpBuilderAndroid/
├── app/
│   ├── build.gradle                 # AGP 7.3.1, compileSdk 32, minSdk 24, targetSdk 28
│   └── src/main/
│       ├── AndroidManifest.xml      # 无任何权限；FileProvider 仅用于产物传递
│       ├── assets/
│       │   ├── toolchain/gcc.zip    # arm-linux-androideabi gcc（随 APK 分发）
│       │   ├── lib/start.mr         # 壳文件（工程无自带时使用）
│       │   ├── lib/cfunction.ext    # 壳文件（同上）
│       │   ├── demo/                # 内置 mrp_demo（helloworld.c + src + assets）
│       │   └── runtime/mrp_compat.c # -nostdlib 下的 libc/libm 兼容实现（弱符号）
│       └── java/net/gddhy/mrpbuilder/
│           ├── MainActivity.java    # UI：选源 / zip 关联打开 / 入口选择 / 编译 / 打包 / 日志复制 / 保存 / 运行 / 分享
│           ├── Project.java         # 工程模型与扫描（gcc/老 SDK 工程检测、入口智能判断）
│           ├── ProjectImporter.java # SAF 导入（文件/zip/目录/内置 Demo，导入前清缓存）
│           ├── ToolchainManager.java# 工具链解压与 chmod
│           ├── MrpCompiler.java     # gcc 进程调用
│           ├── ElfChecker.java      # bin.elf 校验（check-elf.py 的 Java 移植）
│           ├── MrpPacker.java       # MRP 容器打包器
│           ├── BuildEngine.java     # 两阶段编排（gcc 工程编译，老 SDK 拒绝）
│           └── Util.java            # GBK / zip / IO 工具
└── README.md
```

---

## 工具链说明

- 内置 `arm-linux-androideabi-gcc`（GCC 7.2.0，32 位 ARM，静态运行于任意 ARM 设备），
  源自 fengdeyingzi/gccpage 分发版本，与社区手机 CAPP 路线同源；
- 目标始终是 **32 位小端 ARM ELF**（MRP 目标机为 ARMv5TE）；arm64 手机上通过内核
  32 位兼容层执行工具链本身；
- 首次编译自动解压到应用私有目录（`files/gcc`），无需网络、无需 root、无需存储权限；
- 纯 64 位（无 32 位兼容层）的设备无法运行该 32 位工具链，属已知限制。

---

## 参考项目

- [fengdeyingzi/mrpbuilder](https://github.com/fengdeyingzi/mrpbuilder) —— Go 版构建器（MRP 容器格式来源）
- [gddhy/mrpbuilder](https://github.com/gddhy/mrpbuilder) —— web 版构建器（编译参数 / mrp_compat.c / check-elf.py）
- [fengdeyingzi/mrppack](https://github.com/fengdeyingzi/mrppack) —— Java 版 mrp 打包器
- [fengdeyingzi/gccpage](https://github.com/fengdeyingzi/gccpage) —— 安卓 gcc 工具链分发
- mrp_demo / TinalIDE 工程（斯凯 mythroad 运行时库）

## License

本项目基于 **GPL-3.0** 开源（必须项：编译参数、mrp_compat.c、check-elf 校验与 MRP 打包格式
分别派生自同为 GPL-3.0 的 [gddhy/mrpbuilder](https://github.com/gddhy/mrpbuilder) 与
[fengdeyingzi/mrpbuilder](https://github.com/fengdeyingzi/mrpbuilder)）。
完整条款见 [LICENSE](LICENSE)。

### 第三方组件与版权声明

| 组件 | 来源 | 许可/版权 |
|---|---|---|
| 内置 gcc 工具链（arm-linux-androideabi-gcc 7.2.0，约 25MB） | Google NDK / [fengdeyingzi/gccpage](https://github.com/fengdeyingzi/gccpage) 分发 | GCC：GPLv3 + GCC Runtime Library Exception（libgcc 可自由链接） |
| mrp_compat.c / 编译参数 / check-elf 校验 | [gddhy/mrpbuilder](https://github.com/gddhy/mrpbuilder)（web 版） | GPL-3.0 |
| MRP 容器打包格式 / mrp_demo 运行时库 | [fengdeyingzi/mrpbuilder](https://github.com/fengdeyingzi/mrpbuilder) | GPL-3.0 |
| mpc.h 等运行时头文件 | zengming00（2012） | 社区分享，保留原作者版权声明 |
| start.mr / cfunction.ext 壳文件 | fengdeyingzi/mrpbuilder 社区制作 | GPL-3.0 |
| 功能机开发规范.md（gui_fan.md） | [MrpProjects](https://github.com/fengdeyingzi/MrpProjects) 仓库 |   |
| 编译参数中的 r9/r10 保护 | TinalIDE mrp工程 | 社区实践 |

## 致谢

- [fengdeyingzi](https://github.com/fengdeyingzi) —— mrpbuilder 系列（Go/web/Java 版）、gccpage、mrp_demo
- 小蟀 基于 TinalIDE 的mrp项目 —— 手机端编译方案（r9/r10 保护等真机关键参数）
- zengming00 —— mpc 冒泡开发实验系统与运行时代码

## 已知限制

- 仅支持 gcc 工程；ADS1.2/armcc 老 SDK 工程会直接提示「不支持编译」；
- 工具链为 32 位 ARM 二进制，纯 64 位且无 32 位兼容层的设备无法运行；
- targetSdk 28 为有意为之（允许执行私有目录内解压的 gcc）；
- 真机兼容性：产物在模拟器可运行；老功能机需使用与平台匹配的壳，
  若卡启动页请按《编译工具链与真机兼容性》排查；
- 内置 gcc.zip 约 25MB，Git 仓库体积偏大属预期（可后续改用 Git LFS）。

## 编译工具链与真机兼容性

**工具链**：App 内置 arm-linux-androideabi-gcc 7.2.0（约 26MB，首次编译自动解压），编译参数对齐
TinalIDE mrp项目（手机端 arm-none-eabi-gcc 真机验证方案）：-nostdlib -nostartfiles -pie -fPIC
-marm -march=armv5te -mfloat-abi=soft -Os -ffixed-r9 -ffixed-r10 -ffunction-sections
-fdata-sections -fno-common -fshort-enums -Wl,--gc-sections -Wl,--strip-all
+ 显式链接 libgcc.a（对应 mrpbuilder 的 -gcc -lgcc 选项；demo Makefile 不带 -lgcc 是因为
demo 无除法/浮点，含除法工程不带 -lgcc 会报 undefined reference to __aeabi_idiv）。

> **真机卡启动的关键修复（r9/r10 保护）**：老 MTK 功能机（MT6225/6235/6250/6276 等）的 VMF 平台由
> ADS/armcc 按 APCS 编译，约定 r9=静态基址(SB)、r10=栈限制(SL)，平台中断/回调依赖这两个寄存器。
> 编译器默认会把 r9 当普通寄存器使用（实测无 -ffixed 时 r9 被用 109 次），bin.elf 运行中一旦被
> 平台中断打断，壳状态即被破坏 -> 真机卡启动页（模拟器环境不触发中断，表现正常）。
> 加 -ffixed-r9 -ffixed-r10 后产物 r9/r10 使用均为 0，与 APCS 壳安全共存（TinalIDE mrp项目同款参数）。

**两个 gcc 的产物差异**：同一参数下产物结构完全一致（ET_DYN 静态 PIE、双 PT_LOAD、
PT_DYNAMIC、仅 R_ARM_RELATIVE 重定位、EABI5 soft-float），真机壳（elfloader）只认
R_ARM_RELATIVE，两者都满足。差异只在 libgcc 运行时库：
- Android NDK libgcc 的除零例程依赖 raise、64 位移位依赖 bionic —— 已由 mrp_compat.c
  弱符号补齐（raise / __aeabi_llsl / __aeabi_llsr / __aeabi_lasr 等）；
- arm-none-eabi libgcc 纯净无这些依赖。

**真机（2005-2012 功能机）运行提示**：bin.elf 本身在真机壳下是兼容的（模拟器能跑即证明），
真机卡在启动页时优先排查两点：
1. 壳兼容性：App 打包的 start.mr 为社区 2020 版（elfloader v20201111），老平台可能不兼容；
   可用 fengdeyingzi mrpbuilder（web 版/仓库工具）对同一工程打包，装真机对照
   也卡说明是壳/平台问题，与编译工具链无关。
2. 产物自检：编译成功后会打印 bin.elf 校验（架构/类型/动态段/重定位），确认只有
   R_ARM_RELATIVE 重定位、无 DT_NEEDED。
