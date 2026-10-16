package com.shuxian.videoclient;

import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CapacitorPlugin(name = "VideoProxy")
public class VideoProxyPlugin extends Plugin {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36";
    private static final Pattern NESTED_URI = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern AD_HINT = Pattern.compile("(?:^|[/_?&=-])(ad|ads|advert|advertise|commercial|preroll)(?:[/_?&=.-]|$)", Pattern.CASE_INSENSITIVE);
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final String token = UUID.randomUUID().toString();
    private ServerSocket server;

    @Override
    public void load() {
        try {
            // 随机令牌限制请求；投屏时电视需要通过局域网读取同一代理。
            server = new ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"));
            workers.execute(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        workers.execute(() -> handle(socket));
                    } catch (IOException error) {
                        if (!server.isClosed()) Log.e("VideoProxy", "接受连接失败", error);
                    }
                }
            });
        } catch (IOException error) {
            Log.e("VideoProxy", "启动本地视频代理失败", error);
        }
    }

    @PluginMethod
    public void url(PluginCall call) {
        String target = call.getString("url", "");
        if (server == null || server.isClosed()) {
            call.reject("本地视频代理未启动");
            return;
        }
        try {
            URL parsed = new URL(target);
            if (!parsed.getProtocol().equals("http") && !parsed.getProtocol().equals("https"))
                throw new IOException("地址必须使用 HTTP 或 HTTPS");
            String kind = Boolean.TRUE.equals(call.getBoolean("image")) ? "image" : "stream";
            String encoded = URLEncoder.encode(target, StandardCharsets.UTF_8.name());
            String host = "127.0.0.1";
            String deviceHost = call.getString("deviceHost");
            if (deviceHost != null) {
                // UDP connect 不发包，只选择通往电视的本机网卡地址。
                try (DatagramSocket route = new DatagramSocket()) {
                    route.connect(InetAddress.getByName(deviceHost), 1900);
                    host = route.getLocalAddress().getHostAddress();
                }
                if (host.equals("0.0.0.0")) throw new IOException("无法确定局域网地址");
            }
            String address = "http://" + host + ":" + server.getLocalPort() + "/" + token + "/" + kind
                    + "?url=" + encoded + "&ads=" + (Boolean.TRUE.equals(call.getBoolean("filterAds")) ? "1" : "0");
            JSObject result = new JSObject();
            result.put("url", address);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage());
        }
    }

    private void handle(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String request = reader.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            String range = null;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
            }
            OutputStream output = client.getOutputStream();
            if (parts.length < 2 || (!parts[0].equals("GET") && !parts[0].equals("HEAD"))) {
                writeError(output, 405, "Method Not Allowed");
                return;
            }
            boolean head = parts[0].equals("HEAD");
            URL local = new URL("http://127.0.0.1" + parts[1]);
            String path = local.getPath();
            boolean image = path.equals("/" + token + "/image");
            if (!image && !path.equals("/" + token + "/stream")) {
                writeError(output, 404, "Not Found");
                return;
            }
            Map<String, String> query = new HashMap<>();
            if (local.getQuery() != null) {
                for (String pair : local.getQuery().split("&")) {
                    String[] entry = pair.split("=", 2);
                    if (entry.length == 2)
                        query.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8.name()));
                }
            }
            URL target = new URL(query.getOrDefault("url", ""));
            if (!target.getProtocol().equals("http") && !target.getProtocol().equals("https")) {
                writeError(output, 400, "Bad Request");
                return;
            }
            HttpURLConnection upstream = (HttpURLConnection) target.openConnection();
            upstream.setConnectTimeout(12000);
            upstream.setReadTimeout(60000);
            upstream.setRequestProperty("User-Agent", USER_AGENT);
            upstream.setRequestProperty("Accept-Encoding", "identity");
            upstream.setRequestProperty("Referer", target.getHost().endsWith("doubanio.com")
                    ? "https://movie.douban.com/" : target.getProtocol() + "://" + target.getAuthority() + "/");
            if (range != null) upstream.setRequestProperty("Range", range);
            try {
                int status = upstream.getResponseCode();
                if (status < 200 || status >= 300) {
                    writeError(output, status, "Upstream Error");
                    return;
                }
                String contentType = upstream.getContentType() == null ? "application/octet-stream" : upstream.getContentType();
                boolean manifest = !image && (upstream.getURL().toString().matches("(?i).*\\.m3u8(?:[?#].*)?$")
                        || contentType.toLowerCase().contains("mpegurl"));
                try (InputStream input = upstream.getInputStream()) {
                    if (manifest) {
                        String body = new String(readAll(input), StandardCharsets.UTF_8);
                        byte[] rewritten = rewriteManifest(body, upstream.getURL(), "1".equals(query.get("ads")), client.getLocalAddress().getHostAddress())
                                .getBytes(StandardCharsets.UTF_8);
                        writeHeaders(output, 200, "application/vnd.apple.mpegurl; charset=utf-8", rewritten.length, null, null, false);
                        if (!head) output.write(rewritten);
                    } else {
                        writeHeaders(output, status, contentType, upstream.getContentLengthLong(),
                                upstream.getHeaderField("Content-Range"), upstream.getHeaderField("Accept-Ranges"), image);
                        if (!head) {
                            byte[] buffer = new byte[65536];
                            int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        }
                    }
                    output.flush();
                }
            } finally {
                upstream.disconnect();
            }
        } catch (Exception error) {
            Log.w("VideoProxy", "视频代理请求失败: " + error.getMessage());
        }
    }

    private String rewriteManifest(String body, URL base, boolean filterAds, String host) throws IOException {
        StringBuilder result = new StringBuilder();
        boolean adBreak = false;
        boolean skipSegment = false;
        String previous = "";
        for (String line : body.replace("\r", "").split("\n")) {
            if (filterAds && line.startsWith("#EXT-X-DATERANGE") && line.toLowerCase().contains("class=\"ad")) {
                adBreak = true;
                continue;
            }
            if (line.startsWith("#EXT-X-DISCONTINUITY")) adBreak = false;
            if (line.startsWith("#EXTINF")) skipSegment = filterAds && adBreak;
            if (!line.isEmpty() && !line.startsWith("#")) {
                URL nested = new URL(base, line);
                if (!skipSegment && !(filterAds && AD_HINT.matcher(nested.toString()).find())) {
                    result.append(proxyAddress(nested.toString(), filterAds, host)).append('\n');
                } else if (previous.startsWith("#EXTINF")) {
                    int last = result.lastIndexOf(previous + "\n");
                    if (last >= 0) result.delete(last, result.length());
                }
                skipSegment = false;
                previous = line;
                continue;
            }
            Matcher matcher = NESTED_URI.matcher(line);
            StringBuffer replaced = new StringBuffer();
            while (matcher.find()) {
                String uri = proxyAddress(new URL(base, matcher.group(1)).toString(), filterAds, host);
                matcher.appendReplacement(replaced, Matcher.quoteReplacement("URI=\"" + uri + "\""));
            }
            matcher.appendTail(replaced);
            if (!skipSegment) result.append(replaced).append('\n');
            previous = line;
        }
        return result.toString();
    }

    private String proxyAddress(String target, boolean filterAds, String host) throws IOException {
        return "http://" + host + ":" + server.getLocalPort() + "/" + token + "/stream?url="
                + URLEncoder.encode(target, StandardCharsets.UTF_8.name()) + "&ads=" + (filterAds ? "1" : "0");
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) result.write(buffer, 0, count);
        return result.toByteArray();
    }

    private void writeHeaders(OutputStream output, int status, String type, long length, String contentRange, String acceptRanges, boolean image) throws IOException {
        StringBuilder headers = new StringBuilder("HTTP/1.1 " + status
                + (status == 206 ? " Partial Content\r\n" : " OK\r\n"));
        headers.append("Content-Type: ").append(type).append("\r\n");
        headers.append("Access-Control-Allow-Origin: *\r\nCache-Control: ")
                .append(image ? "private, max-age=86400" : "no-store")
                .append("\r\nConnection: close\r\n");
        if (length >= 0) headers.append("Content-Length: ").append(length).append("\r\n");
        if (contentRange != null) headers.append("Content-Range: ").append(contentRange).append("\r\n");
        if (acceptRanges != null) headers.append("Accept-Ranges: ").append(acceptRanges).append("\r\n");
        headers.append("\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private void writeError(OutputStream output, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        writeHeaders(output, status, "text/plain; charset=utf-8", body.length, null, null, false);
        output.write(body);
    }

    @Override
    protected void handleOnDestroy() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // 退出时代理已经不再接受新连接。
        }
        workers.shutdownNow();
    }
}
