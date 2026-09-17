package net.gddhy.mrpbuilder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * HTTP 路由：/
 * - GET  /             WebUI（浏览器提交工程编译）
 * - GET  /doc/         接口文档
 * - GET  /SKILL.md     技能文档（含本机局域网 IP）
 * - GET  /api/demo     获取内置 demo 工程 zip
 * - POST /api/build    提交编译（multipart：file + 元信息 + 入口）→ build_id
 * - GET  /api/build/{id}           状态
 * - GET  /api/build/{id}/log?offset= 日志（增量）
 * - GET  /api/build/{id}/artifact  产物 .mrp
 * - GET  /api/builds               最近任务列表
 */
public final class ApiHandler implements MrpHttpServer.Handler {

    private final Context ctx;
    private final ApiCompiler compiler;

    public ApiHandler(Context ctx, ApiCompiler compiler) {
        this.ctx = ctx.getApplicationContext();
        this.compiler = compiler;
    }

    /** 获取服务访问地址：WiFi 下返回局域网 IP，否则 127.0.0.1（手机流量场景） */
    public static String serviceIp(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                WifiManager wm = (WifiManager) ctx.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int ip = wm.getConnectionInfo().getIpAddress();
                    if (ip != 0) {
                        return String.format(Locale.US, "%d.%d.%d.%d",
                                ip & 0xff, ip >> 8 & 0xff, ip >> 16 & 0xff, ip >> 24 & 0xff);
                    }
                }
            }
            // 兜底：遍历网卡找第一个非回环 IPv4
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp()) continue;
                Enumeration<InetAddress> ae = ni.getInetAddresses();
                while (ae.hasMoreElements()) {
                    InetAddress a = ae.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    @Override
    public MrpHttpServer.Resp handle(String method, String path, String query,
                                     Map<String, String> headers, byte[] body) throws Exception {
        // CORS 预检
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return new MrpHttpServer.Resp(200, "text/plain", "");
        }
        if ("/".equals(path)) {
            return new MrpHttpServer.Resp(200, "text/html; charset=utf-8", webui());
        }
        if ("/doc/".equals(path) || "/doc".equals(path)) {
            return new MrpHttpServer.Resp(200, "text/html; charset=utf-8", docPage());
        }
        if ("/SKILL.md".equals(path)) {
            return new MrpHttpServer.Resp(200, "text/markdown; charset=utf-8", skillMd());
        }
        if ("/api/demo".equals(path)) {
            return demoZip();
        }
        if ("/api/build".equals(path)) {
            if ("POST".equalsIgnoreCase(method)) return submitBuild(body, headers);
            return new MrpHttpServer.Resp(405, "application/json", "{\"error\":\"method not allowed\"}");
        }
        if ("/api/builds".equals(path)) {
            return buildsList();
        }
        if (path.startsWith("/api/build/")) {
            String rest = path.substring("/api/build/".length());
            return buildRoute(method, rest, query);
        }
        return null;
    }

    // ---------------------------------------------------------------- 构建路由

    private MrpHttpServer.Resp buildRoute(String method, String rest, String query) throws Exception {
        int slash = rest.indexOf('/');
        String id = slash > 0 ? rest.substring(0, slash) : rest;
        String sub = slash > 0 ? rest.substring(slash + 1) : "";
        ApiCompiler.Task t = compiler.get(id);
        if (t == null) {
            return new MrpHttpServer.Resp(404, "application/json",
                    "{\"error\":\"build not found\",\"build_id\":\"" + MrpHttpServer.jsonEscape(id) + "\"}");
        }
        if ("DELETE".equalsIgnoreCase(method)) {
            return new MrpHttpServer.Resp(200, "application/json",
                    "{\"build_id\":\"" + MrpHttpServer.jsonEscape(id) + "\",\"status\":\"deleted\"}");
        }
        if (sub.isEmpty()) {
            return new MrpHttpServer.Resp(200, "application/json", taskJson(t));
        }
        if ("log".equals(sub)) {
            int offset = 0;
            if (query != null && query.startsWith("offset=")) {
                try { offset = Integer.parseInt(query.substring("offset=".length())); } catch (Exception ignored) {}
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"offset\":").append(t.logLength());
            sb.append(",\"content\":\"").append(MrpHttpServer.jsonEscape(t.logSince(offset))).append("\"");
            sb.append(",\"eof\":").append(t.status.equals("success") || t.status.equals("failed"));
            sb.append("}");
            return new MrpHttpServer.Resp(200, "application/json", sb.toString());
        }
        if ("artifact".equals(sub)) {
            if (!t.status.equals("success")) {
                return new MrpHttpServer.Resp(400, "application/json",
                        "{\"error\":\"build not finished\",\"status\":\"" + t.status + "\"}");
            }
            File out = new File(ctx.getFilesDir(), "api_builds/" + t.id + "/" + t.artifactName);
            if (!out.isFile()) {
                return new MrpHttpServer.Resp(404, "application/json", "{\"error\":\"artifact missing\"}");
            }
            MrpHttpServer.Resp r = new MrpHttpServer.Resp(200,
                    "application/octet-stream", Util.readAll(new java.io.FileInputStream(out)));
            r.fileName = t.artifactName;
            return r;
        }
        return null;
    }

    private String taskJson(ApiCompiler.Task t) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"build_id\":\"").append(MrpHttpServer.jsonEscape(t.id)).append("\"");
        sb.append(",\"status\":\"").append(t.status).append("\"");
        sb.append(",\"phase\":\"").append(t.phase).append("\"");
        sb.append(",\"progress\":").append(t.progress);
        sb.append(",\"current_task\":\"").append(MrpHttpServer.jsonEscape(t.currentTask == null ? "" : t.currentTask)).append("\"");
        sb.append(",\"error\":\"").append(MrpHttpServer.jsonEscape(t.error == null ? "" : t.error)).append("\"");
        sb.append(",\"artifact\":\"").append(MrpHttpServer.jsonEscape(t.artifactName == null ? "" : t.artifactName)).append("\"");
        sb.append(",\"created_at\":").append(t.createdAt);
        sb.append(",\"finished_at\":").append(t.finishedAt == 0 ? "null" : String.valueOf(t.finishedAt));
        sb.append(",\"urls\":{");
        sb.append("\"status\":\"/api/build/").append(t.id).append("\"");
        sb.append(",\"log\":\"/api/build/").append(t.id).append("/log\"");
        sb.append(",\"artifact\":\"/api/build/").append(t.id).append("/artifact\"");
        sb.append("}}");
        return sb.toString();
    }

    private MrpHttpServer.Resp buildsList() {
        List<ApiCompiler.Task> all = new ArrayList<>(compiler.all().values());
        all.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        int n = Math.min(all.size(), 20);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"builds\":[");
        for (int i = 0; i < n; i++) {
            ApiCompiler.Task t = all.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"build_id\":\"").append(t.id).append("\",\"status\":\"").append(t.status)
                    .append("\",\"phase\":\"").append(t.phase)
                    .append("\",\"artifact\":\"").append(MrpHttpServer.jsonEscape(t.artifactName == null ? "" : t.artifactName)).append("\"}");
        }
        sb.append("]}");
        return new MrpHttpServer.Resp(200, "application/json", sb.toString());
    }

    // ---------------------------------------------------------------- 提交构建

    private MrpHttpServer.Resp submitBuild(byte[] body, Map<String, String> headers) throws Exception {
        Map<String, MrpHttpServer.Part> parts =
                MrpHttpServer.parseMultipart(body, headers.get("content-type"));
        MrpHttpServer.Part file = parts.get("file");
        if (file == null || file.data.length == 0) {
            return new MrpHttpServer.Resp(400, "application/json",
                    "{\"error\":\"缺少 file 字段（zip 源码包）\"}");
        }
        P get = (k) -> {
            MrpHttpServer.Part p = parts.get(k);
            return p == null ? "" : new String(p.data, StandardCharsets.UTF_8).trim();
        };
        String display = get.param("display");
        String fileName = get.param("fileName");
        String appId = get.param("appid");
        String ver = get.param("version");
        String vendor = get.param("vendor");
        String desc = get.param("desc");
        String entryName = get.param("entry");
        if (fileName.isEmpty()) fileName = get.param("name");
        if (fileName.isEmpty()) fileName = "app.mrp";

        String err = ApiCompiler.validateMeta(display, fileName, appId, ver, vendor);
        if (err != null) {
            return new MrpHttpServer.Resp(400, "application/json",
                    "{\"error\":\"" + MrpHttpServer.jsonEscape(err) + "\"}");
        }
        try {
            ApiCompiler.Task t = compiler.submit(file.data, entryName,
                    display, fileName, appId, ver, vendor, desc);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"build_id\":\"").append(t.id).append("\"");
            sb.append(",\"status\":\"queued\"");
            sb.append(",\"message\":\"编译任务已入队\"");
            sb.append(",\"urls\":{\"status\":\"/api/build/").append(t.id)
                    .append("\",\"log\":\"/api/build/").append(t.id)
                    .append("/log\",\"artifact\":\"/api/build/").append(t.id).append("/artifact\"}}");
            return new MrpHttpServer.Resp(200, "application/json", sb.toString());
        } catch (IllegalArgumentException e) {
            return new MrpHttpServer.Resp(400, "application/json",
                    "{\"error\":\"" + MrpHttpServer.jsonEscape(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    /** 便捷取参 */
    private interface P {
        String param(String k);
    }

    // ---------------------------------------------------------------- demo zip

    private MrpHttpServer.Resp demoZip() throws Exception {
        File tmp = new File(ctx.getCacheDir(), "api-demo.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
            zos.setLevel(Deflater.DEFAULT_COMPRESSION);
            zipAssetDir(zos, "demo", null);
            zos.putNextEntry(new ZipEntry("功能机开发规范.md"));
            try (InputStream in = ctx.getAssets().open("gui_fan.md")) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) zos.write(b, 0, n);
            }
            zos.closeEntry();
        }
        MrpHttpServer.Resp r = new MrpHttpServer.Resp(200,
                "application/zip", Util.readAll(new java.io.FileInputStream(tmp)));
        r.fileName = "demo.zip";
        return r;
    }

    private void zipAssetDir(ZipOutputStream zos, String assetDir, String prefix) throws Exception {
        String[] kids = ctx.getAssets().list(assetDir);
        if (kids == null) return;
        for (String k : kids) {
            String path = assetDir + "/" + k;
            String entry = (prefix == null ? "" : prefix) + k;
            if (ctx.getAssets().list(path).length > 0) {
                zipAssetDir(zos, path, entry + "/");
            } else {
                zos.putNextEntry(new ZipEntry(entry));
                try (InputStream in = ctx.getAssets().open(path)) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) zos.write(b, 0, n);
                }
                zos.closeEntry();
            }
        }
    }

    // ---------------------------------------------------------------- 页面

    private String webui() {
        String ip = serviceIp(ctx);
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh\"><head><meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
                + "<title>MRP Builder 编译服务</title>\n"
                + "<style>"
                + "body{font-family:system-ui,sans-serif;max-width:760px;margin:0 auto;padding:16px;background:#f6f7f9;color:#222}"
                + "h1{font-size:20px}.card{background:#fff;border-radius:10px;padding:14px;margin:12px 0;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
                + "label{display:block;font-size:13px;color:#555;margin:10px 0 4px}"
                + "input[type=text],input[type=file]{width:100%;box-sizing:border-box;padding:8px;border:1px solid #ccc;border-radius:6px;font-size:14px}"
                + "button{background:#1a73e8;color:#fff;border:0;border-radius:6px;padding:10px 18px;font-size:14px;margin-top:12px}"
                + "button:disabled{background:#aaa}"
                + "#log{background:#0d1117;color:#c9d1d9;font-family:monospace;font-size:12px;white-space:pre-wrap;padding:10px;border-radius:6px;max-height:320px;overflow:auto;display:none}"
                + ".row{display:flex;gap:10px}.row>div{flex:1}"
                + "a{color:#1a73e8}"
                + ".ok{color:#188038}.bad{color:#d93025}"
                + "</style></head><body>\n"
                + "<h1>MRP Builder 编译服务</h1>\n"
                + "<div class=\"card\">本机地址：<b>http://" + ip + ":8111/</b>（同一局域网可访问）<br>"
                + "接口文档：<a href=\"/doc/\">/doc/</a>　技能文档：<a href=\"/SKILL.md\">/SKILL.md</a>　"
                + "<a href=\"/api/demo\">下载内置 Demo（demo.zip）</a></div>\n"
                + "<div class=\"card\"><h2 style=\"font-size:16px;margin:0\">提交编译</h2>\n"
                + "<form id=\"f\">\n"
                + "<label>源码 zip 包（gcc 工程，必选）</label><input type=\"file\" id=\"file\" accept=\".zip,application/zip\">\n"
                + "<div class=\"row\"><div><label>入口文件（可选，默认智能判断）</label>"
                + "<input type=\"text\" id=\"entry\" placeholder=\"main.c 或 src/main.c\"></div>\n"
                + "<div><label>显示名</label><input type=\"text\" id=\"display\" placeholder=\"我的程序\"></div></div>\n"
                + "<div class=\"row\"><div><label>内部名（英文，.mrp 结尾）</label>"
                + "<input type=\"text\" id=\"fileName\" placeholder=\"app.mrp\"></div>\n"
                + "<div><label>AppID</label><input type=\"text\" id=\"appid\" placeholder=\"10001\"></div></div>\n"
                + "<div class=\"row\"><div><label>版本</label><input type=\"text\" id=\"version\" placeholder=\"1\"></div>\n"
                + "<div><label>开发者</label><input type=\"text\" id=\"vendor\" placeholder=\"MrpDev\"></div></div>\n"
                + "<label>介绍（可选）</label><input type=\"text\" id=\"desc\" placeholder=\"一个 mrp 程序\">\n"
                + "<button type=\"button\" id=\"go\">开始编译</button> <span id=\"bid\" style=\"font-size:13px\"></span>\n"
                + "</form></div>\n"
                + "<div class=\"card\" id=\"dl\" style=\"display:none\">"
                + "编译完成，点击下载产物："
                + "<a id=\"dlHref\" href=\"#\" download style=\"display:inline-block;background:#188038;color:#fff;"
                + "padding:8px 16px;border-radius:6px;text-decoration:none;margin-left:8px\">"
                + "下载 <span id=\"dlName\"></span></a></div>\n"
                + "<div class=\"card\"><h2 style=\"font-size:16px;margin:0\">编译日志</h2><pre id=\"log\"></pre></div>\n"
                + "<script>\n"
                + "var t=null;\n"
                + "function el(i){return document.getElementById(i)}\n"
                + "function poll(id){var url='/api/build/'+id;var off=0;var n=0;\n"
                + "fetch(url).then(r=>r.json()).then(j=>{el('bid').textContent='build_id: '+j.build_id+' ['+j.status+']';\n"
                + "if(j.status==='failed'){el('log').style.display='block';el('log').textContent+='\\n[失败] '+j.error;"
                + "el('dl').style.display='none';el('go').disabled=false;return}\n"
                + "if(j.status==='success'){el('log').style.display='block';el('log').textContent+='\\n\\n[完成] 编译成功';"
                + "el('dl').style.display='block';el('dlHref').href='/api/build/'+id+'/artifact';"
                + "el('dlName').textContent=j.artifact||'产物';el('go').disabled=false;return}\n"
                + "fetch(url+'/log?offset='+off).then(r=>r.json()).then(l=>{off=l.offset;"
                + "if(l.content){el('log').style.display='block';el('log').textContent+=l.content;"
                + "el('log').scrollTop=el('log').scrollHeight}\n"
                + "if(n++>600)return;t=setTimeout(()=>poll(id),1500)}).catch(()=>{t=setTimeout(()=>poll(id),2000)})})\n"
                + ".catch(e=>{el('bid').textContent='请求失败: '+e;el('go').disabled=false})}\n"
                + "el('go').onclick=function(){\n"
                + "var f=el('file').files[0];if(!f){alert('请选择 zip 源码包');return}\n"
                + "var fd=new FormData();fd.append('file',f);\n"
                + "['entry','display','fileName','appid','version','vendor','desc'].forEach(k=>fd.append(k,el(k).value));\n"
                + "el('go').disabled=true;el('dl').style.display='none';el('log').style.display='block';el('log').textContent='提交中...';\n"
                + "fetch('/api/build',{method:'POST',body:fd}).then(r=>r.json()).then(j=>{\n"
                + "if(j.error){el('log').textContent='错误: '+j.error;el('go').disabled=false;return}\n"
                + "el('log').textContent='任务已入队: '+j.build_id+'\\n';poll(j.build_id)})\n"
                + ".catch(e=>{el('log').textContent='提交失败: '+e;el('go').disabled=false})}\n"
                + "</script></body></html>";
    }

    private String docPage() {
        String ip = serviceIp(ctx);
        return "<!DOCTYPE html>\n"
                + "<html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<title>API 文档 - MRP Builder</title>"
                + "<style>body{font-family:system-ui,sans-serif;max-width:820px;margin:0 auto;padding:16px;color:#222}"
                + "pre{background:#0d1117;color:#c9d1d9;padding:10px;border-radius:6px;overflow:auto;font-size:12px}"
                + "table{border-collapse:collapse;width:100%;font-size:13px}"
                + "th,td{border:1px solid #ddd;padding:6px 8px;text-align:left}"
                + "th{background:#f0f2f5}h2{font-size:17px;border-bottom:2px solid #eee;padding-bottom:4px}</style></head><body>"
                + "<h1>MRP Builder 编译服务 API</h1>"
                + "<p>服务地址：<b>http://" + ip + ":8111</b>　"
                + "WebUI：<a href=\"/\">/</a>　技能文档：<a href=\"/SKILL.md\">/SKILL.md</a></p>"
                + "<h2>接口一览</h2><table><tr><th>方法</th><th>路径</th><th>说明</th></tr>"
                + "<tr><td>GET</td><td>/</td><td>WebUI 编译页面</td></tr>"
                + "<tr><td>GET</td><td>/doc/</td><td>本接口文档</td></tr>"
                + "<tr><td>GET</td><td>/SKILL.md</td><td>技能文档（含服务 IP，可安装为 Skill）</td></tr>"
                + "<tr><td>GET</td><td>/api/demo</td><td>下载内置 demo 工程 zip</td></tr>"
                + "<tr><td>POST</td><td>/api/build</td><td>提交编译（multipart/form-data）</td></tr>"
                + "<tr><td>GET</td><td>/api/build/{id}</td><td>查询构建状态</td></tr>"
                + "<tr><td>GET</td><td>/api/build/{id}/log?offset=N</td><td>拉取编译日志（增量）</td></tr>"
                + "<tr><td>GET</td><td>/api/build/{id}/artifact</td><td>下载编译产物 .mrp</td></tr>"
                + "<tr><td>GET</td><td>/api/builds</td><td>最近 20 条构建</td></tr></table>"
                + "<h2>POST /api/build</h2>"
                + "<p>multipart/form-data 字段：</p><table><tr><th>字段</th><th>必选</th><th>说明</th></tr>"
                + "<tr><td>file</td><td>是</td><td>gcc 工程源码 zip（.c/.h/资源）</td></tr>"
                + "<tr><td>display</td><td>是</td><td>显示名（非空）</td></tr>"
                + "<tr><td>fileName</td><td>是</td><td>内部名，英文且以 .mrp 结尾，如 app.mrp</td></tr>"
                + "<tr><td>appid</td><td>是</td><td>AppID（非空）</td></tr>"
                + "<tr><td>version</td><td>是</td><td>版本（非空）</td></tr>"
                + "<tr><td>vendor</td><td>是</td><td>开发者（非空）</td></tr>"
                + "<tr><td>desc</td><td>否</td><td>介绍</td></tr>"
                + "<tr><td>entry</td><td>否</td><td>入口文件相对路径（默认智能判断）</td></tr></table>"
                + "<p>返回：<code>{\"build_id\":\"a1b2c3d4e5f6\",\"status\":\"queued\",\"urls\":{...}}</code></p>"
                + "<h2>curl 示例</h2><pre>"
                + "BASE=http://" + ip + ":8111\n"
                + "\n"
                + "# 提交编译（zip + 元信息 + 入口）\n"
                + "curl -F \"file=@demo.zip\" \\\n"
                + "     -F \"display=我的程序\" -F \"fileName=app.mrp\" \\\n"
                + "     -F \"appid=10001\" -F \"version=1\" -F \"vendor=MrpDev\" \\\n"
                + "     -F \"entry=main.c\" $BASE/api/build\n"
                + "\n"
                + "# 查询状态（2 秒轮询到终态）\n"
                + "curl $BASE/api/build/&lt;build_id&gt;\n"
                + "\n"
                + "# 拉日志\n"
                + "curl $BASE/api/build/&lt;build_id&gt;/log?offset=0\n"
                + "\n"
                + "# 下载产物\n"
                + "curl -OJ $BASE/api/build/&lt;build_id&gt;/artifact\n"
                + "</pre></body></html>";
    }

    private String skillMd() {
        String ip = serviceIp(ctx);
        return "# MRP Builder 手机编译服务（安卓端）\n"
                + "\n"
                + "在安卓手机上运行的 MRP 在线编译服务。上传 gcc 工程 zip，在线编译并打包出 .mrp 文件；\n"
                + "支持查询进度、拉取日志、下载产物。由「MRP Builder」App 提供，**手机需保持 App 服务开启**。\n"
                + "\n"
                + "## 服务地址\n"
                + "\n"
                + "- 基础地址：`http://" + ip + ":8111`\n"
                + "- 本文件：`http://" + ip + ":8111/SKILL.md`\n"
                + "- WebUI：`http://" + ip + ":8111/`\n"
                + "- 接口文档：`http://" + ip + ":8111/doc/`\n"
                + "\n"
                + "> 手机连 WiFi 时使用上面的局域网 IP；手机使用流量时局域网 IP 不可达，请在手机上改用\n"
                + "> `http://127.0.0.1:8111`（仅本机可访问）。\n"
                + "\n"
                + "## 能力概述\n"
                + "\n"
                + "| 能力 | 说明 |\n"
                + "|---|---|\n"
                + "| 提交编译 | multipart 上传工程 zip + 显示名/内部名/AppID/版本/开发者/介绍 + 可选入口文件，返回 build_id |\n"
                + "| 状态查询 | 按 build_id 查询状态、阶段、进度、当前任务、错误 |\n"
                + "| 日志拉取 | 按 offset 续读完整编译日志 |\n"
                + "| 产物下载 | 下载打包好的 .mrp 文件 |\n"
                + "| Demo 获取 | 直接下载内置 demo 工程 zip |\n"
                + "\n"
                + "## 工程要求\n"
                + "\n"
                + "- zip 内为 gcc 工程：入口 .c（main.c 或含 main(/mrc_init( 的文件）+ src/*.c *.h + 资源；\n"
                + "- **仅支持 gcc 工程**；ADS1.2/armcc 老 SDK 工程（含 `*.mpr` 工程配置或自带部分运行时但无 `_start`）\n"
                + "  会直接失败并说明不支持；\n"
                + "- 无自带运行时（无 mrc_*/xl_*/uc3_* 等）的工程会自动附加内置 mythroad 运行时库；\n"
                + "- 入口文件可不传，服务端智能判断（main.c → 含 mrc_init( → 含 main(）；\n"
                + "- **资源打包**：工程内全部资源（含 `assets/` 等子目录中的图片/音频等）默认一并打包进 mrp，\n"
                + "  条目名保留相对路径；zip 条目名自动规范化（`\\`→`/`、去前导 `./`），兼容 MT管理器等打包的压缩包；\n"
                + "- 打包元信息校验：显示名非空、内部名英文且 .mrp 结尾、AppID/版本/开发者非空。\n"
                + "\n"
                + "## API 端点\n"
                + "\n"
                + "### 1. 提交编译（上传 zip）\n"
                + "\n"
                + "```\n"
                + "POST /api/build\n"
                + "Content-Type: multipart/form-data\n"
                + "字段:\n"
                + "  file      (必填) 工程源码 .zip\n"
                + "  display   (必填) 显示名\n"
                + "  fileName  (必填) 内部名，英文且以 .mrp 结尾（如 app.mrp）\n"
                + "  appid     (必填) AppID\n"
                + "  version   (必填) 版本\n"
                + "  vendor    (必填) 开发者\n"
                + "  desc      (可选) 介绍\n"
                + "  entry     (可选) 入口文件相对路径（如 main.c / src/main.c），缺省智能判断\n"
                + "```\n"
                + "\n"
                + "```bash\n"
                + "curl -F \"file=@demo.zip\" -F \"display=我的程序\" -F \"fileName=app.mrp\" \\\n"
                + "     -F \"appid=10001\" -F \"version=1\" -F \"vendor=MrpDev\" -F \"entry=main.c\" \\\n"
                + "     http://" + ip + ":8111/api/build\n"
                + "```\n"
                + "\n"
                + "返回：\n"
                + "\n"
                + "```json\n"
                + "{\"build_id\":\"a1b2c3d4e5f6\",\"status\":\"queued\",\"message\":\"编译任务已入队\",\n"
                + " \"urls\":{\"status\":\"/api/build/a1b2c3d4e5f6\",\"log\":\"/api/build/a1b2c3d4e5f6/log\",\n"
                + "          \"artifact\":\"/api/build/a1b2c3d4e5f6/artifact\"}}\n"
                + "```\n"
                + "\n"
                + "### 2. 查询构建状态\n"
                + "\n"
                + "```\n"
                + "GET /api/build/{build_id}\n"
                + "```\n"
                + "\n"
                + "返回字段：`status`（queued/running/success/failed）、`phase`（extracting/building/packaging）、\n"
                + "`progress`、`current_task`、`error`、`artifact`（产物文件名）、`urls`。\n"
                + "\n"
                + "```json\n"
                + "{\"build_id\":\"a1b2c3d4e5f6\",\"status\":\"success\",\"phase\":\"packaging\",\"progress\":100,\n"
                + " \"artifact\":\"app.mrp\",\"urls\":{\"artifact\":\"/api/build/a1b2c3d4e5f6/artifact\"}}\n"
                + "```\n"
                + "\n"
                + "### 3. 拉取构建日志（增量）\n"
                + "\n"
                + "```\n"
                + "GET /api/build/{build_id}/log?offset={已读行数}\n"
                + "```\n"
                + "\n"
                + "返回：`{\"offset\": 新偏移, \"content\": \"本次新增日志\", \"eof\": 是否已结束}`。\n"
                + "轮询时每次把返回的 `offset` 作为下次请求参数。\n"
                + "\n"
                + "### 4. 下载产物\n"
                + "\n"
                + "```\n"
                + "GET /api/build/{build_id}/artifact\n"
                + "```\n"
                + "\n"
                + "- 构建成功（status == \"success\"）后调用；`failed` 时返回 400。\n"
                + "\n"
                + "### 5. 其他\n"
                + "\n"
                + "```\n"
                + "GET  /api/demo          # 下载内置 demo 工程 zip（demo.zip）\n"
                + "GET  /api/builds        # 最近 20 条构建\n"
                + "GET  /doc/              # 接口文档（HTML）\n"
                + "GET  /SKILL.md          # 本技能文档\n"
                + "```\n"
                + "\n"
                + "## 状态机与轮询节奏\n"
                + "\n"
                + "```\n"
                + "queued → running → success | failed\n"
                + "```\n"
                + "\n"
                + "- 提交后立即返回 build_id；建议 1~2 秒轮询 `GET /api/build/{id}`；\n"
                + "- 终态判断：`status` 为 success/failed；下载产物前先确认 `status == \"success\"` 且 artifact 非空。\n"
                + "\n"
                + "## 完整调用流程示例\n"
                + "\n"
                + "```bash\n"
                + "BASE=http://" + ip + ":8111\n"
                + "\n"
                + "# 0. 拿 demo 工程\n"
                + "curl -OJ $BASE/api/demo\n"
                + "\n"
                + "# 1. 提交并取 build_id\n"
                + "BID=$(curl -s -F \"file=@demo.zip\" -F \"display=Hello\" -F \"fileName=hello.mrp\" \\\n"
                + "  -F \"appid=10001\" -F \"version=1\" -F \"vendor=MrpDev\" $BASE/api/build \\\n"
                + "  | python3 -c \"import sys,json;print(json.load(sys.stdin)['build_id'])\")\n"
                + "\n"
                + "# 2. 轮询到终态\n"
                + "while :; do\n"
                + "  S=$(curl -s $BASE/api/build/$BID | python3 -c \"import sys,json;print(json.load(sys.stdin)['status'])\")\n"
                + "  echo \"status=$S\"; [ \"$S\" = success ] || [ \"$S\" = failed ] && break; sleep 2\n"
                + "done\n"
                + "\n"
                + "# 3. 下载产物\n"
                + "curl -OJ $BASE/api/build/$BID/artifact\n"
                + "```\n"
                + "\n"
                + "## 限制与约定\n"
                + "\n"
                + "- 仅支持 gcc 工程；老 SDK（ADS1.2/armcc）工程会被拒绝并说明原因；\n"
                + "- 同时仅执行 1 个编译任务（排队）；构建记录保留 3 天自动清理；\n"
                + "- 编译在手机本地执行，速度取决于手机性能（首次编译需解压约 26MB 工具链）；\n"
                + "- 手机锁屏/App 被杀会中断服务与任务，需保持前台运行；\n"
                + "- 无鉴权，仅供个人/局域网内使用；不要暴露到公网。\n";
    }
}
