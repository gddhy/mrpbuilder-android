package net.gddhy.mrpbuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量 HTTP 服务器（ServerSocket 手写，不依赖任何第三方库）。
 *
 * 用于在手机本地 + 局域网 8111 端口提供 MRP 编译 API 服务。
 * 仅实现本服务需要的 HTTP/1.1 子集：请求行/头解析、Content-Length 定长 body、
 * multipart/form-data 解析、固定响应。
 */
public final class MrpHttpServer {

    public interface Log { void log(String s); }

    /** HTTP 响应 */
    public static final class Resp {
        public int status;
        public String contentType;
        public byte[] body;
        public String fileName; // 附件下载名（可选）
        public Resp(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
        public Resp(int status, String contentType, String text) {
            this(status, contentType, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 请求处理回调：返回 null 表示 404 */
    public interface Handler {
        Resp handle(String method, String path, String query,
                    Map<String, String> headers, byte[] body) throws Exception;
    }

    private final int port;
    private final Handler handler;
    private final Log log;
    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;

    public MrpHttpServer(int port, Handler handler, Log log) {
        this.port = port;
        this.handler = handler;
        this.log = log;
    }

    public int port() { return port; }

    public boolean isRunning() {
        return running && server != null && !server.isClosed();
    }

    /** 启动监听（0.0.0.0，局域网可访问）。端口被占用时抛 IOException（BindException） */
    public void start() throws IOException {
        server = new ServerSocket(port);
        running = true;
        pool = Executors.newCachedThreadPool();
        Thread t = new Thread(this::acceptLoop, "mrp-httpd");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                pool.execute(() -> handleConn(s));
            } catch (IOException e) {
                if (running) log.log("HTTP 服务 accept 失败: " + e.getMessage());
            }
        }
    }

    private void handleConn(Socket s) {
        try (Socket socket = s;
             InputStream in = s.getInputStream();
             OutputStream out = s.getOutputStream()) {

            // 请求行 + 头（读到 \r\n\r\n）
            byte[] head = readUntil(in, new byte[]{'\r', '\n', '\r', '\n'}, 64 * 1024);
            String headStr = new String(head, StandardCharsets.UTF_8);
            String[] lines = headStr.split("\r\n");
            if (lines.length < 1) return;
            String[] reqLine = lines[0].split(" ");
            if (reqLine.length < 2) return;
            String method = reqLine[0];
            String target = reqLine[1];

            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int ci = lines[i].indexOf(':');
                if (ci > 0) {
                    headers.put(lines[i].substring(0, ci).trim().toLowerCase(),
                            lines[i].substring(ci + 1).trim());
                }
            }

            // path + query
            String path = target;
            String query = null;
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                query = target.substring(qi + 1);
            }
            if (!path.startsWith("/")) path = "/" + path;

            // body
            byte[] body = new byte[0];
            String cl = headers.get("content-length");
            if (cl != null) {
                int len = Integer.parseInt(cl.trim());
                if (len > 0) {
                    if (len > 512 * 1024 * 1024) throw new IOException("请求体过大");
                    body = readN(in, len);
                }
            }

            Resp resp;
            try {
                resp = handler.handle(method, path, query, headers, body);
            } catch (Exception e) {
                String msg = "{\"error\":\"" + jsonEscape(String.valueOf(e.getMessage())) + "\"}";
                resp = new Resp(500, "application/json", msg.getBytes(StandardCharsets.UTF_8));
            }
            if (resp == null) {
                resp = new Resp(404, "text/plain; charset=utf-8",
                        "404 Not Found".getBytes(StandardCharsets.UTF_8));
            }
            writeResponse(out, resp);
        } catch (Exception ignored) {
        }
    }

    private void writeResponse(OutputStream out, Resp resp) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(resp.status);
        if (resp.status == 200) sb.append(" OK");
        else if (resp.status == 400) sb.append(" Bad Request");
        else if (resp.status == 404) sb.append(" Not Found");
        else if (resp.status == 500) sb.append(" Internal Server Error");
        else if (resp.status == 503) sb.append(" Service Unavailable");
        sb.append("\r\n");
        sb.append("Content-Type: ").append(resp.contentType == null ? "text/plain" : resp.contentType).append("\r\n");
        sb.append("Content-Length: ").append(resp.body.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n");
        sb.append("Access-Control-Allow-Headers: Content-Type\r\n");
        if (resp.fileName != null) {
            sb.append("Content-Disposition: attachment; filename=\"").append(resp.fileName).append("\"\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(resp.body);
        out.flush();
    }

    // ---------------------------------------------------------------- 解析工具

    /** 读流直到遇到 delim（含），返回含分隔符的字节；上限 maxBytes，超限抛异常 */
    private static byte[] readUntil(InputStream in, byte[] delim, int maxBytes) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int d = 0;
        while (true) {
            int c = in.read();
            if (c == -1) break;
            buf.write(c);
            if (c == delim[d]) {
                d++;
                if (d == delim.length) break;
            } else {
                d = 0;
                if (c == delim[0]) d = 1;
            }
            if (buf.size() > maxBytes) throw new IOException("HTTP 头过大");
        }
        return buf.toByteArray();
    }

    /** 精确读 n 字节 */
    public static byte[] readN(InputStream in, int n) throws IOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(out, off, n - off);
            if (r == -1) break;
            off += r;
        }
        if (off != n) {
            byte[] t = new byte[off];
            System.arraycopy(out, 0, t, 0, off);
            return t;
        }
        return out;
    }

    /** 解析 multipart/form-data：返回 name → (filename 可能为 null) → 字节 */
    public static Map<String, Part> parseMultipart(byte[] body, String contentType) throws IOException {
        Map<String, Part> out = new HashMap<>();
        if (contentType == null || !contentType.contains("boundary=")) return out;
        String boundary = "boundary=";
        int bi = contentType.indexOf(boundary);
        if (bi < 0) return out;
        String b = contentType.substring(bi + boundary.length()).trim();
        if (b.startsWith("\"")) b = b.substring(1, b.lastIndexOf('"'));
        byte[] bd = ("--" + b).getBytes(StandardCharsets.US_ASCII);

        List<byte[]> parts = splitBy(body, bd);
        for (byte[] part : parts) {
            // 找头/数据分界 \r\n\r\n
            int sep = indexOf(part, new byte[]{'\r', '\n', '\r', '\n'});
            if (sep < 0) continue;
            String head = new String(part, 0, sep, StandardCharsets.UTF_8);
            byte[] data = new byte[part.length - sep - 4];
            System.arraycopy(part, sep + 4, data, 0, data.length);
            // 尾部 \r\n（boundary 前）
            if (data.length >= 2 && data[data.length - 2] == '\r' && data[data.length - 1] == '\n') {
                byte[] t = new byte[data.length - 2];
                System.arraycopy(data, 0, t, 0, t.length);
                data = t;
            }
            String name = null, filename = null;
            int cd = head.toLowerCase().indexOf("content-disposition:");
            if (cd >= 0) {
                String cds = head.substring(cd + 20);
                name = extractAttr(cds, "name");
                filename = extractAttr(cds, "filename");
            }
            if (name != null) out.put(name, new Part(name, filename, data));
        }
        return out;
    }

    public static final class Part {
        public final String name;
        public final String fileName; // 文件 part 的原始文件名（可为 null）
        public final byte[] data;
        public Part(String name, String fileName, byte[] data) {
            this.name = name;
            this.fileName = fileName;
            this.data = data;
        }
    }

    private static String extractAttr(String cd, String key) {
        int i = cd.indexOf(key + "=");
        if (i < 0) return null;
        String v = cd.substring(i + key.length() + 1).trim();
        if (v.startsWith("\"")) {
            int e = v.indexOf('"', 1);
            return e > 0 ? v.substring(1, e) : v.substring(1);
        }
        int e = v.indexOf(';');
        return e >= 0 ? v.substring(0, e).trim() : v.trim();
    }

    /** 按分隔符切分（去掉边界条目本身），兼容结尾 --boundary-- */
    private static List<byte[]> splitBy(byte[] data, byte[] delim) {
        List<byte[]> out = new ArrayList<>();
        int start = 0;
        while (true) {
            int i = indexOf(data, delim, start);
            if (i < 0) break;
            int partStart = i + delim.length;
            // 跳过分隔符后的 \r\n
            if (partStart < data.length && data[partStart] == '\r') partStart++;
            if (partStart < data.length && data[partStart] == '\n') partStart++;
            // 下一个分隔符
            int j = indexOf(data, delim, partStart);
            if (j < 0) break;
            // 跳过结尾 \r\n
            int end = j;
            if (end > partStart && data[end - 1] == '\n') end--;
            if (end > partStart && data[end - 1] == '\r') end--;
            if (end > partStart) {
                byte[] part = new byte[end - partStart];
                System.arraycopy(data, partStart, part, 0, part.length);
                out.add(part);
            }
            start = j;
        }
        return out;
    }

    private static int indexOf(byte[] data, byte[] key) {
        return indexOf(data, key, 0);
    }

    private static int indexOf(byte[] data, byte[] key, int from) {
        outer:
        for (int i = from; i <= data.length - key.length; i++) {
            for (int k = 0; k < key.length; k++) {
                if (data[i + k] != key[k]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** JSON 字符串转义（简单实现） */
    public static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
