package net.gddhy.mrpbuilder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * MRP Builder 主界面
 *
 * 流程：选源（SAF 单文件/zip/目录/内置 Demo）→ 编译 bin.elf（gcc）→ 打包 .mrp
 * 不申请任何存储权限，读写全部走 SAF / FileProvider。
 */
public class MainActivity extends Activity {

    private static final int RC_FILE = 100;
    private static final int RC_FOLDER = 101;
    private static final int RC_SAVE = 102;
    private static final int RC_EXPORT = 103;

    private TextView tvToolchain, tvProject, tvLog;
    private ScrollView logScroll;
    private Button btnCompile, btnPack;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuf = new StringBuilder();

    private Project project;
    private File binElf;
    private boolean busy;

    // API 编译服务（静态持有：Activity 重建（旋转等）后仍能控制）
    private static MrpHttpServer sApiServer;
    private static ApiCompiler sApiCompiler;
    private static final int API_PORT = 8111;
    private TextView tvApiStatus, tvApiUrl;
    private Button btnApiStart, btnApiStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 旧式 MRP 模拟器（MrpStore 等）只认 file:// 真实路径，不认 content://；
        // targetSdk >= 24 默认禁止跨应用暴露 file://（FileUriExposedException），
        // 按 Android 文档方式解除该 VM 限制，以便把产物路径交给这些模拟器打开
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().build());
        setContentView(R.layout.activity_main);

        tvToolchain = findViewById(R.id.tvToolchain);
        tvProject = findViewById(R.id.tvProject);
        tvLog = findViewById(R.id.tvLog);
        logScroll = findViewById(R.id.logScroll);
        btnCompile = findViewById(R.id.btnCompile);
        btnPack = findViewById(R.id.btnPack);
        Button btnFile = findViewById(R.id.btnFile);
        Button btnFolder = findViewById(R.id.btnFolder);
        Button btnDemo = findViewById(R.id.btnDemo);
        Button btnExportDemo = findViewById(R.id.btnExportDemo);
        Button btnCopyLog = findViewById(R.id.btnCopyLog);
        Button btnClearLog = findViewById(R.id.btnClearLog);
        tvApiStatus = findViewById(R.id.tvApiStatus);
        tvApiUrl = findViewById(R.id.tvApiUrl);
        btnApiStart = findViewById(R.id.btnApiStart);
        btnApiStop = findViewById(R.id.btnApiStop);

        btnApiStart.setOnClickListener(v -> startApiServer());
        btnApiStop.setOnClickListener(v -> stopApiServer());
        if (sApiServer != null && sApiServer.isRunning()) {
            btnApiStart.setEnabled(false);
            btnApiStop.setEnabled(true);
            tvApiStatus.setText("服务已启动，监听 " + API_PORT + " 端口");
            tvApiUrl.setText("http://" + ApiHandler.serviceIp(this) + ":" + API_PORT + "/");
        } else {
            btnApiStop.setEnabled(false);
        }

        btnFile.setOnClickListener(v -> pickFile());
        btnFolder.setOnClickListener(v -> pickFolder());
        btnDemo.setOnClickListener(v -> runAsync(() -> {
            try {
                appendLog("载入内置 Demo…");
                project = ProjectImporter.loadDemo(MainActivity.this, MainActivity.this::appendLog);
                onProjectReady();
            } catch (Exception e) {
                appendLog("[错误] Demo 加载失败: " + e.getMessage());
            }
        }));
        btnExportDemo.setOnClickListener(v -> exportDemo());
        btnCompile.setOnClickListener(v -> compile());
        btnPack.setOnClickListener(v -> showPackDialog());
        btnCopyLog.setOnClickListener(v -> copyLog());
        btnClearLog.setOnClickListener(v -> {
            logBuf.setLength(0);
            ui.post(() -> tvLog.setText("[就绪]\n"));
        });

        btnPack.setEnabled(false);
        refreshProjectView();
        updateToolchainStatus();
        appendLog("MRP Builder 就绪。");
        appendLog("设备 ABI: " + ToolchainManager.abi());
        appendLog("提示：点击「载入内置 Demo」可快速体验编译 + 打包全流程。");

        // 外部 zip 关联打开（文件管理器选择「用 MRP Builder 打开」）：冷启动时在这里处理
        handleViewIntent(getIntent());
    }

    /** singleTop 下外部再次用 zip 打开时走这里（Activity 已在前台） */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleViewIntent(intent);
    }

    /**
     * 处理外部 zip 打开：文件管理器 / 浏览器携带 zip（ACTION_VIEW + content/file uri）启动本应用时，
     * 自动解压 zip 到私有目录并准备编译任务，等同于在「选择文件」里选择 .zip 包。
     * 解压与安全校验（路径穿越 / 符号链接 / 压缩炸弹）在 Util.unzip 内完成。
     */
    private void handleViewIntent(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri uri = intent.getData();
        if (uri == null) return;
        String mime = intent.getType();
        String name = ProjectImporter.queryName(this, uri);
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        boolean isZip = lower.endsWith(".zip")
                || (mime != null && mime.toLowerCase(Locale.US).contains("zip"));
        if (!isZip) return; // 非 zip 关联（如单个 .c 文件被转发），保持正常启动
        if (busy) {
            appendLog("[提示] 有任务进行中，请等待完成后再试");
            return;
        }
        appendLog("检测到 zip 文件打开：" + (name != null ? name : uri.toString()));
        final Uri fUri = uri;
        runAsync(() -> {
            try {
                appendLog("解压 zip 到私有目录并识别工程（含路径穿越 / 压缩炸弹安全校验）…");
                project = ProjectImporter.importFile(MainActivity.this, fUri, MainActivity.this::appendLog);
                onProjectReady();
            } catch (Exception e) {
                String scheme = fUri.getScheme();
                appendLog("[错误] 导入失败: " + e.getMessage());
                if ("file".equalsIgnoreCase(scheme)) {
                    appendLog("[提示] 该 zip 以 file:// 路径提供，Android 分区存储禁止无权限直接读取。");
                    appendLog("[提示] 请点击应用内「选择 .c 文件 / .zip 包」，通过系统文件选择器（SAF）导入同一文件。");
                } else if ("content".equalsIgnoreCase(scheme)) {
                    appendLog("[提示] 读取失败可能因文件被移动/删除或授权已失效，请重新选择文件导入。");
                }
            }
        });
    }

    // ---------------------------------------------------------------- API 编译服务

    /** 启动 8111 端口 HTTP 编译服务（本地 + 局域网） */
    private void startApiServer() {
        if (sApiServer != null && sApiServer.isRunning()) {
            toast("编译服务已在运行");
            return;
        }
        runAsync(() -> {
            try {
                if (sApiCompiler == null) {
                    sApiCompiler = new ApiCompiler(this);
                    sApiCompiler.cleanup();
                }
                final MrpHttpServer srv = new MrpHttpServer(API_PORT,
                        new ApiHandler(this, sApiCompiler),
                        msg -> appendLog("[API] " + msg));
                srv.start();
                sApiServer = srv;
                final String ip = ApiHandler.serviceIp(this);
                ui.post(() -> {
                    btnApiStart.setEnabled(false);
                    btnApiStop.setEnabled(true);
                    tvApiStatus.setText("服务已启动，监听 " + API_PORT + " 端口");
                    tvApiUrl.setText("http://" + ip + ":" + API_PORT + "/（WebUI /doc/ /SKILL.md）");
                    toast("编译服务已启动: http://" + ip + ":" + API_PORT + "/");
                    appendLog("[API] 编译服务已启动: http://" + ip + ":" + API_PORT + "/"
                            + "（WiFi 局域网可访问；手机流量下用 127.0.0.1）");
                });
            } catch (java.net.BindException e) {
                ui.post(() -> {
                    tvApiStatus.setText("服务未启动");
                    toast("端口 " + API_PORT + " 被占用，无法启用编译服务");
                    appendLog("[API] 启动失败：端口 " + API_PORT + " 被占用，无法启用");
                });
            } catch (Exception e) {
                ui.post(() -> {
                    tvApiStatus.setText("服务未启动");
                    toast("服务启动失败: " + e.getMessage());
                    appendLog("[API] 启动失败: " + e.getMessage());
                });
            }
        });
    }

    private void stopApiServer() {
        runAsync(() -> {
            if (sApiServer != null) sApiServer.stop();
            sApiServer = null;
            ui.post(() -> {
                btnApiStart.setEnabled(true);
                btnApiStop.setEnabled(false);
                tvApiStatus.setText("服务未启动");
                tvApiUrl.setText("");
                appendLog("[API] 编译服务已停止");
            });
        });
    }

    // ---------------------------------------------------------------- 工具链

    private void updateToolchainStatus() {
        File gcc = new File(getFilesDir(), "gcc/gcc/bin/arm-linux-androideabi-gcc");
        File marker = new File(getFilesDir(), "gcc/.ready_v1");
        if (marker.exists() && gcc.isFile()) {
            tvToolchain.setText(R.string.toolchain_ready);
        } else {
            tvToolchain.setText(R.string.toolchain_missing);
        }
    }

    // ---------------------------------------------------------------- SAF

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/x-c", "text/plain", "application/zip",
                "application/x-zip-compressed", "application/octet-stream", "application/x-c"});
        try {
            startActivityForResult(i, RC_FILE);
        } catch (ActivityNotFoundException e) {
            toast("系统文件选择器不可用");
        }
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, RC_FOLDER);
        } catch (ActivityNotFoundException e) {
            toast("系统文件选择器不可用");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == RC_FILE) {
            final int takeFlags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (Exception ignored) {
            }
            Uri u = uri;
            runAsync(() -> {
                try {
                    appendLog("");
                    project = ProjectImporter.importFile(MainActivity.this, u, MainActivity.this::appendLog);
                    onProjectReady();
                } catch (Exception e) {
                    appendLog("[错误] 导入失败: " + e.getMessage());
                }
            });
        } else if (requestCode == RC_FOLDER) {
            final int takeFlags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(uri, takeFlags);
            } catch (Exception ignored) {
            }
            Uri u = uri;
            runAsync(() -> {
                try {
                    appendLog("");
                    project = ProjectImporter.importTree(MainActivity.this, u, MainActivity.this::appendLog);
                    onProjectReady();
                } catch (Exception e) {
                    appendLog("[错误] 导入失败: " + e.getMessage());
                }
            });
        } else if (requestCode == RC_SAVE) {
            File f = lastMrp;
            if (f != null && f.isFile()) {
                try (java.io.OutputStream os = getContentResolver()
                        .openOutputStream(uri, "w")) {
                    if (os != null) {
                        os.write(Util.readAll(new FileInputStream(f)));
                        os.flush();
                    }
                    toast("已保存: " + ProjectImporter.queryName(this, uri));
                } catch (Exception e) {
                    toast("保存失败: " + e.getMessage());
                }
            }
        } else if (requestCode == RC_EXPORT) {
            File f = new File(getCacheDir(), "demo-export.zip");
            if (f.isFile()) {
                try (java.io.OutputStream os = getContentResolver()
                        .openOutputStream(uri, "w")) {
                    if (os != null) {
                        os.write(Util.readAll(new FileInputStream(f)));
                        os.flush();
                    }
                    toast("已保存: " + ProjectImporter.queryName(this, uri));
                } catch (Exception e) {
                    toast("保存失败: " + e.getMessage());
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    // ---------------------------------------------------------------- 导出内置 Demo

    /** 把内置 demo 工程（含功能机开发规范.md）打包为 zip，通过 SAF 保存到本地 */
    private void exportDemo() {
        File tmp = new File(getCacheDir(), "demo-export.zip");
        runAsync(() -> {
            try {
                appendLog("导出内置 Demo…");
                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
                    zos.setLevel(Deflater.DEFAULT_COMPRESSION);
                    zipAssetDir(zos, "demo", null);
                    ZipEntry e = new ZipEntry("功能机开发规范.md");
                    zos.putNextEntry(e);
                    try (InputStream in = getAssets().open("gui_fan.md")) {
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0) zos.write(b, 0, n);
                    }
                    zos.closeEntry();
                }
                appendLog("已生成 demo.zip（" + Util.humanSize(tmp.length()) + "），请选择保存位置…");
                ui.post(() -> {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/zip");
                    i.putExtra(Intent.EXTRA_TITLE, "demo.zip");
                    try {
                        startActivityForResult(i, RC_EXPORT);
                    } catch (ActivityNotFoundException e) {
                        toast("系统文件保存不可用");
                    }
                });
            } catch (Exception e) {
                appendLog("[错误] 导出失败: " + e.getMessage());
            }
        });
    }

    /** 递归把 assets 目录写入 zip（条目为相对路径） */
    private void zipAssetDir(ZipOutputStream zos, String assetDir, String prefix) throws Exception {
        String[] kids = getAssets().list(assetDir);
        if (kids == null) return;
        for (String k : kids) {
            String path = assetDir + "/" + k;
            String entry = (prefix == null ? k : prefix + "/" + k);
            if (getAssets().list(path).length > 0) {
                zipAssetDir(zos, path, entry);
            } else {
                zos.putNextEntry(new ZipEntry(entry));
                try (InputStream in = getAssets().open(path)) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) zos.write(b, 0, n);
                }
                zos.closeEntry();
            }
        }
    }

    // ---------------------------------------------------------------- 工程视图

    private void onProjectReady() {
        ui.post(() -> {
            binElf = null;
            btnPack.setEnabled(false);
            refreshProjectView();
        });
    }

    private void refreshProjectView() {
        if (project == null) {
            tvProject.setText(R.string.no_project);
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("名称: ").append(project.name).append('\n');
        sb.append("类型: ").append(project.typeDesc()).append('\n');
        sb.append("入口: ").append(project.entry == null ? "（未检测到）" : project.entry.getName()).append('\n');
        sb.append("源码: ").append(project.sources.size()).append(" 个 .c\n");
        sb.append("资源: ").append(project.resources.size()).append(" 个\n");
        if (project.runtimeMode != Project.RT_PARTIAL) {
            sb.append("运行时: ").append(project.hasRuntime ? "工程自带" : "内置库（自动附加）").append('\n');
        }
        sb.append("位置: ").append(project.dir.getAbsolutePath());
        tvProject.setText(sb.toString());
    }

    // ---------------------------------------------------------------- 编译

    private void compile() {
        if (project == null) {
            toast("请先选择工程");
            return;
        }
        if (busy) return;
        if (!project.isGccProject()) {
            appendLog("[不支持编译] 检测到 ADS1.2/armcc 老 SDK 工程"
                    + "（无 mythroad 运行时入口 _start，依赖旧版 SkySDK 头文件与厂商 .lib）。"
                    + "本工具仅支持 gcc 工程。");
            toast("不支持编译：ADS1.2/armcc 老工程，仅支持 gcc 工程");
            return;
        }
        if (project.sources.isEmpty()) {
            toast("工程中没有 .c 源文件");
            return;
        }
        if (project.singleFile) {
            // 单文件模式入口固定，直接编译
            doCompile();
            return;
        }
        showEntryPicker();
    }

    /** 弹出式入口选择：列出入口候选 .c（已排除 mrc_、xl_、uc3_、mpc.c 等运行时实现文件，
     *  这些不可能是入口程序），默认选中智能判断结果 */
    private void showEntryPicker() {
        final Project p = project;
        List<File> cand = new ArrayList<>();
        for (File f : p.sources) {
            if (!Project.isRuntimeSource(f)) cand.add(f);
        }
        if (cand.isEmpty()) {
            appendLog("未找到可作入口的业务源文件（全部 .c 均为运行时实现文件）");
            toast("未找到入口候选（均为运行时文件）");
            return;
        }
        List<String> names = new ArrayList<>();
        for (File f : cand) names.add(relPath(p.dir, f));
        int checked = 0;
        if (p.entry != null) {
            int i = cand.indexOf(p.entry);
            if (i >= 0) checked = i;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("选择入口文件（智能判断: "
                + (p.entry == null ? "未检测到" : p.entry.getName()) + "）");
        final int[] sel = {checked};
        b.setSingleChoiceItems(names.toArray(new String[0]), checked, (d, w) -> sel[0] = w);
        b.setNegativeButton(R.string.cancel, null);
        b.setPositiveButton(R.string.compile, (d, w) -> {
            if (sel[0] < 0 || sel[0] >= cand.size()) return;
            p.entry = cand.get(sel[0]);
            appendLog("已选择入口: " + relPath(p.dir, p.entry));
            refreshProjectView();
            doCompile();
        });
        b.show();
    }

    /** 相对路径（弹窗列表用，保证同名文件可区分） */
    private static String relPath(File base, File f) {
        String b = base.getAbsolutePath();
        String a = f.getAbsolutePath();
        if (a.startsWith(b + File.separator)) return a.substring(b.length() + 1).replace('\\', '/');
        return f.getName();
    }

    private void doCompile() {
        busy = true;
        setBusyUi(true);
        Project p = project;
        runAsync(() -> {
            try {
                appendLog("");
                appendLog("========== 开始编译 ==========");
                appendLog("工程类型: " + p.typeDesc());
                binElf = BuildEngine.compile(MainActivity.this, p, MainActivity.this::appendLog);
                appendLog("========== 编译完成 ==========");
                ui.post(() -> {
                    btnPack.setEnabled(true);
                    toast("编译成功，可打包");
                });
            } catch (Exception e) {
                appendLog("[编译失败] " + e.getMessage());
                toast("编译失败，详见日志");
            } finally {
                busy = false;
                setBusyUi(false);
            }
        });
    }

    // ---------------------------------------------------------------- 打包

    private static final String[] MANDATORY = {"start.mr", "bin.elf", "cfunction.ext"};

    private void showPackDialog() {
        if (project == null) {
            toast("请先选择工程");
            return;
        }
        if (binElf == null || !binElf.isFile()) {
            toast(getString(R.string.must_compile_first));
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.pack_title);
        View root = getLayoutInflater().inflate(R.layout.dialog_pack, null);
        builder.setView(root);

        EditText etDisplay = root.findViewById(R.id.etDisplay);
        EditText etFileName = root.findViewById(R.id.etFileName);
        EditText etAppId = root.findViewById(R.id.etAppId);
        EditText etVersion = root.findViewById(R.id.etVersion);
        EditText etVendor = root.findViewById(R.id.etVendor);
        EditText etDesc = root.findViewById(R.id.etDesc);
        LinearLayout fileList = root.findViewById(R.id.fileList);

        // 默认值
        String base = project.name;
        etDisplay.setText(base);
        etFileName.setText(base + ".mrp");
        etAppId.setText("1");
        etVersion.setText("1");

        // 文件清单：必备三项 + 资源（默认勾选）
        final List<FileChoice> choices = new ArrayList<>();
        choices.add(new FileChoice("start.mr", true, true));
        choices.add(new FileChoice("bin.elf", true, true));
        for (String rel : BuildEngine.resourceRelPaths(project)) {
            choices.add(new FileChoice(rel, true, false));
        }
        choices.add(new FileChoice("cfunction.ext", true, true));

        for (FileChoice c : choices) {
            CheckBox cb = new CheckBox(this);
            cb.setText(c.name);
            cb.setChecked(c.checked);
            cb.setEnabled(!c.mandatory);
            cb.setTextSize(14);
            fileList.addView(cb);
            c.box = cb;
        }

        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.do_pack, null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String display = etDisplay.getText().toString().trim();
                    String fn = etFileName.getText().toString().trim();
                    String appId = etAppId.getText().toString().trim();
                    String ver = etVersion.getText().toString().trim();
                    String vendor = etVendor.getText().toString().trim();

                    // 校验：全部通过才关闭弹窗进入打包；不通过吐司提示并保留弹窗内容
                    if (display.isEmpty()) {
                        toast("显示名不能为空");
                        return;
                    }
                    if (!fn.matches("(?i)^[a-z0-9_.-]+\\.mrp$")) {
                        toast("内部名必须为英文且以 .mrp 结尾，如 xxx.mrp");
                        return;
                    }
                    if (appId.isEmpty()) {
                        toast("AppID 不能为空");
                        return;
                    }
                    if (ver.isEmpty()) {
                        toast("版本不能为空");
                        return;
                    }
                    if (vendor.isEmpty()) {
                        toast("开发者不能为空");
                        return;
                    }

                    dialog.dismiss();
                    MrpPacker.Meta meta = new MrpPacker.Meta();
                    meta.display = display;
                    meta.fileName = fn;
                    meta.appid = parseInt(appId, 1);
                    meta.version = parseInt(ver, 1);
                    meta.vendor = vendor;
                    meta.desc = etDesc.getText().toString().trim();

                    List<String> checked = new ArrayList<>();
                    for (FileChoice c : choices) {
                        if (c.box != null && c.box.isChecked() && !c.mandatory) checked.add(c.name);
                    }
                    doPack(meta, checked);
                }));
        dialog.show();
    }

    private static final class FileChoice {
        final String name;
        final boolean mandatory;
        boolean checked;
        CheckBox box;
        FileChoice(String name, boolean checked, boolean mandatory) {
            this.name = name;
            this.checked = checked;
            this.mandatory = mandatory;
        }
    }

    private File lastMrp;

    private void doPack(MrpPacker.Meta meta, List<String> checkedResources) {
        if (busy) return;
        busy = true;
        setBusyUi(true);
        Project p = project;
        File elf = binElf;
        runAsync(() -> {
            try {
                appendLog("");
                appendLog("========== 打包 ==========");
                byte[] mrp = BuildEngine.pack(MainActivity.this, p, elf, meta, checkedResources);
                File out = new File(getCacheDir(), meta.fileName);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(mrp);
                    fos.flush();
                }
                lastMrp = out;
                appendLog("打包成功: " + out.getName() + " (" + Util.humanSize(out.length()) + ")");
                appendLog("文件数: " + (2 + checkedResources.size() + 1)
                        + "（start.mr + bin.elf + 资源" + checkedResources.size() + " + cfunction.ext）");
                ui.post(() -> showResultDialog(out));
            } catch (Exception e) {
                appendLog("[打包失败] " + e.getMessage());
                toast("打包失败，详见日志");
            } finally {
                busy = false;
                setBusyUi(false);
            }
        });
    }

    private void showResultDialog(File mrp) {
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(R.string.packed);
        b.setMessage(mrp.getName() + " (" + Util.humanSize(mrp.length()) + ")\n已生成在应用缓存目录。");
        b.setPositiveButton(R.string.run_emulator, (d, w) -> runInEmulator(mrp));
        b.setNeutralButton(R.string.save, (d, w) -> saveViaSaf(mrp));
        b.setNegativeButton(R.string.share, (d, w) -> share(mrp));
        b.show();
    }

    private void runInEmulator(File mrp) {
        // 优先：复制到公共目录，用真实路径启动（MrpStore 等旧模拟器只认 file://）
        File pub = copyToPublic(mrp);
        if (pub != null) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(pub), "application/octet-stream");
            try {
                startActivity(Intent.createChooser(i, getString(R.string.run_emulator)));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        // 兜底：content://（支持 SAF 的新模拟器）
        Uri uri = MrpFileProvider.uriFor(mrp);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/octet-stream");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, getString(R.string.run_emulator)));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.no_emulator));
        }
    }

    /** 把产物复制到模拟器能读到的公共位置，返回真实文件路径（无存储权限方案） */
    private File copyToPublic(File src) {
        try {
            byte[] bytes = Util.readAll(new FileInputStream(src));
            if (Build.VERSION.SDK_INT >= 29) {
                // Android 10+：写入公共 Download（MediaStore），无需任何存储权限
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, src.getName());
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MrpBuilder");
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(bytes);
                            os.flush();
                        }
                    }
                    cv.clear();
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(uri, cv, null, null);
                    File dir = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), "MrpBuilder");
                    File f = new File(dir, src.getName());
                    appendLog("[提示] 已复制到公共目录供模拟器读取: " + f.getAbsolutePath());
                    return f;
                }
            } else {
                // Android 7-9：应用专属外部目录（无需权限；旧模拟器持有
                // READ_EXTERNAL_STORAGE 时可读 Android/data 下的路径）
                File dir = new File(getExternalFilesDir(null), "share");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File f = new File(dir, src.getName());
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    fos.write(bytes);
                    fos.flush();
                }
                appendLog("[提示] 已复制到共享目录供模拟器读取: " + f.getAbsolutePath());
                return f;
            }
        } catch (Exception e) {
            appendLog("[提示] 复制到公共目录失败，改用内容 URI: " + e.getMessage());
        }
        return null;
    }

    private void saveViaSaf(File mrp) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_TITLE, mrp.getName());
        try {
            startActivityForResult(i, RC_SAVE);
        } catch (ActivityNotFoundException e) {
            toast("系统文件保存不可用");
        }
    }

    private void share(File mrp) {
        Uri uri = MrpFileProvider.uriFor(mrp);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/octet-stream");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.setClipData(ClipData.newRawUri("mrp", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    // ---------------------------------------------------------------- 日志复制

    private void copyLog() {
        String text = logBuf.toString().trim();
        if (text.isEmpty()) {
            toast(getString(R.string.no_error_log));
            return;
        }
        copyText(text);
        toast(getString(R.string.log_copied, text.length()));
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("mrp-log", text));
    }

    // ---------------------------------------------------------------- 工具

    private static long parseInt(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private void setBusyUi(boolean b) {
        ui.post(() -> {
            btnCompile.setEnabled(!b);
            btnPack.setEnabled(!b && binElf != null && binElf.isFile());
        });
    }

    private void appendLog(String line) {
        ui.post(() -> {
            tvLog.append(line + "\n");
            logBuf.append(line).append('\n');
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String msg) {
        ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private void runAsync(Runnable r) {
        new Thread(r, "mrpbuilder-worker").start();
    }
}
