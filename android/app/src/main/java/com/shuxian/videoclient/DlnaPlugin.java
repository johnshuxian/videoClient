package com.shuxian.videoclient;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

@CapacitorPlugin(name = "Dlna")
public class DlnaPlugin extends Plugin {
    private static final String SERVICE = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String SERVICE_PREFIX = "urn:schemas-upnp-org:service:AVTransport:";
    private static final String[] SEARCH_TARGETS = {
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            SERVICE,
            "ssdp:all"
    };
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Renderer> renderers = new ConcurrentHashMap<>();
    private volatile Renderer current;
    private volatile String currentUrl;
    private volatile boolean seenPlaying;
    private volatile long castSerial;
    private ScheduledFuture<?> polling;

    private static class Renderer {
        final String id;
        final String name;
        final String host;
        final URL control;
        final Network network;
        final String serviceType;

        Renderer(String id, String name, String host, URL control, Network network, String serviceType) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.control = control;
            this.network = network;
            this.serviceType = serviceType;
        }
    }

    @PluginMethod
    public void devices(PluginCall call) {
        workers.execute(() -> {
            JSArray items = new JSArray();
            Set<String> locations = new LinkedHashSet<>();
            int descriptionErrors = 0;
            WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("videoclient-dlna");
            ConnectivityManager connectivity = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            Network lanNetwork = null;
            if (connectivity != null) {
                for (Network network : connectivity.getAllNetworks()) {
                    NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
                    if (capabilities != null && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        lanNetwork = network;
                        break;
                    }
                }
            }
            try {
                if (lock != null) lock.acquire();
                // 优先使用 Wi-Fi/以太网，避免手机默认走移动数据或 VPN 时把组播发错网络。
                try (DatagramSocket socket = new DatagramSocket()) {
                    if (lanNetwork != null) lanNetwork.bindSocket(socket);
                    InetAddress multicast = InetAddress.getByName("239.255.255.250");
                    long started = SystemClock.elapsedRealtime();
                    long deadline = started + 4000;
                    int bursts = 0;
                    while (SystemClock.elapsedRealtime() < deadline) {
                        long now = SystemClock.elapsedRealtime();
                        if (bursts < 3 && now >= started + bursts * 1000L) {
                            // 电视可能只回应 AVTransport 或 ssdp:all；分三轮发送以容忍丢包。
                            for (String target : SEARCH_TARGETS) {
                                byte[] search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
                                        + "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + target + "\r\n\r\n")
                                        .getBytes(StandardCharsets.US_ASCII);
                                socket.send(new DatagramPacket(search, search.length, multicast, 1900));
                            }
                            bursts++;
                        }
                        socket.setSoTimeout((int) Math.max(1, Math.min(250, deadline - now)));
                        byte[] response = new byte[4096];
                        DatagramPacket reply = new DatagramPacket(response, response.length);
                        try {
                            socket.receive(reply);
                        } catch (java.net.SocketTimeoutException timeout) {
                            continue;
                        }
                        String headers = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.US_ASCII);
                        for (String line : headers.split("\r?\n")) {
                            if (line.regionMatches(true, 0, "location:", 0, 9) && locations.size() < 40)
                                locations.add(line.substring(9).trim());
                        }
                    }
                }
                for (String location : locations) {
                    try {
                        Renderer renderer = describe(location, lanNetwork);
                        if (renderer == null) continue;
                        renderers.put(renderer.id, renderer);
                        JSObject item = new JSObject();
                        item.put("id", renderer.id);
                        item.put("name", renderer.name);
                        item.put("host", renderer.host);
                        item.put("type", "dlna");
                        item.put("paired", true);
                        items.put(item);
                    } catch (Exception error) {
                        descriptionErrors++;
                        Log.w("Dlna", "读取设备描述失败：" + location, error);
                    }
                }
                JSObject result = new JSObject();
                result.put("items", items);
                JSArray warnings = new JSArray();
                if (items.length() == 0) {
                    warnings.put(locations.isEmpty()
                            ? "未收到 SSDP 响应；请确认手机和电视在同一局域网，且路由器未隔离设备或屏蔽组播。"
                            : descriptionErrors > 0
                                    ? "收到设备响应，但读取设备信息失败；请检查局域网设备互访。"
                                    : "收到设备响应，但没有找到支持 DLNA 视频播放的设备。");
                }
                result.put("warnings", warnings);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("搜索电视失败：" + error.getMessage());
            } finally {
                if (lock != null && lock.isHeld()) lock.release();
            }
        });
    }

    private Renderer describe(String location, Network network) throws Exception {
        URL url = new URL(location);
        if (!url.getProtocol().equals("http") && !url.getProtocol().equals("https")) return null;
        HttpURLConnection connection = (HttpURLConnection) (network == null
                ? url.openConnection() : network.openConnection(url));
        connection.setConnectTimeout(1800);
        connection.setReadTimeout(1800);
        try (InputStream input = connection.getInputStream()) {
            Document document = parseXml(input);
            String name = text(document.getDocumentElement(), "friendlyName");
            String base = text(document.getDocumentElement(), "URLBase");
            URL baseUrl = base.isEmpty() ? url : new URL(base);
            NodeList services = document.getElementsByTagName("service");
            for (int index = 0; index < services.getLength(); index++) {
                Element service = (Element) services.item(index);
                String serviceType = text(service, "serviceType");
                if (!serviceType.startsWith(SERVICE_PREFIX)) continue;
                URL control = new URL(baseUrl, text(service, "controlURL"));
                return new Renderer(location, name.isEmpty() ? url.getHost() : name, url.getHost(), control, network, serviceType);
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    @PluginMethod
    public void cast(PluginCall call) {
        workers.execute(() -> {
            Renderer renderer = renderers.get(call.getString("deviceId", ""));
            String stream = call.getString("stream", "");
            String title = call.getString("title", "视频");
            if (renderer == null) {
                call.reject("电视已失效，请重新搜索");
                return;
            }
            try {
                URL streamUrl = new URL(stream);
                if (!streamUrl.getProtocol().equals("http")) throw new IllegalArgumentException("视频地址无效");
                try {
                    stopCurrent();
                } catch (Exception ignored) {
                    // 旧电视已离线时，仍允许投到新电视。
                }
                String mime = Boolean.TRUE.equals(call.getBoolean("isHls"))
                        ? "application/vnd.apple.mpegurl" : "video/mp4";
                String didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" "
                        + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" "
                        + "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                        + "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>"
                        + xml(title) + "</dc:title><upnp:class>object.item.videoItem</upnp:class>"
                        + "<res protocolInfo=\"http-get:*:" + mime + ":*\">" + xml(stream)
                        + "</res></item></DIDL-Lite>";
                soap(renderer, "SetAVTransportURI", "<CurrentURI>" + xml(stream)
                        + "</CurrentURI><CurrentURIMetaData>" + xml(didl) + "</CurrentURIMetaData>");
                soap(renderer, "Play", "<Speed>1</Speed>");
                double startPosition = call.getDouble("startPosition", 0d);
                if (startPosition > 0) {
                    // 部分电视会在 Play 时清除先前的 Seek，开始播放后再设置续播位置。
                    long totalSeconds = (long) startPosition;
                    String target = String.format(java.util.Locale.US, "%02d:%02d:%02d",
                            totalSeconds / 3600, totalSeconds % 3600 / 60, totalSeconds % 60);
                    for (int attempt = 0; attempt < 10; attempt++) {
                        Thread.sleep(500);
                        try {
                            Document state = soap(renderer, "GetTransportInfo", "");
                            if (text(state.getDocumentElement(), "CurrentTransportState").equals("PLAYING")) break;
                        } catch (Exception ignored) {
                            // 不支持状态查询时直接尝试跳转。
                            break;
                        }
                    }
                    // 部分电视已经跳转，却暂时把 GetPositionInfo 报成 0；以 Seek 的执行结果为准。
                    soap(renderer, "Seek", "<Unit>REL_TIME</Unit><Target>" + target + "</Target>");
                }
                current = renderer;
                currentUrl = call.getString("url", "");
                seenPlaying = false;
                long session = ++castSerial;
                polling = monitor.scheduleAtFixedRate(() -> checkEnded(renderer, session), 5, 5, TimeUnit.SECONDS);
                JSObject result = new JSObject();
                result.put("ok", true);
                call.resolve(result);
            } catch (Exception error) {
                call.reject("电视播放失败：" + error.getMessage());
            }
        });
    }

    private void checkEnded(Renderer renderer, long session) {
        if (current != renderer || castSerial != session) return;
        try {
            Document state = soap(renderer, "GetTransportInfo", "");
            String status = text(state.getDocumentElement(), "CurrentTransportState");
            if (status.equals("PLAYING")) seenPlaying = true;
            if (castSerial == session && seenPlaying
                    && (status.equals("STOPPED") || status.equals("NO_MEDIA_PRESENT"))) {
                current = null;
                if (polling != null) polling.cancel(false);
                JSObject event = new JSObject();
                event.put("deviceId", renderer.id);
                event.put("error", "");
                notifyListeners("castEnded", event);
            }
        } catch (Exception ignored) {
            // 有些电视不支持状态查询，不影响已经开始的播放。
        }
    }

    @PluginMethod
    public void progress(PluginCall call) {
        workers.execute(() -> {
            Renderer renderer = current;
            if (renderer == null) {
                call.resolve(new JSObject());
                return;
            }
            try {
                Document state = soap(renderer, "GetPositionInfo", "");
                JSObject result = new JSObject();
                result.put("deviceId", renderer.id);
                result.put("url", currentUrl);
                result.put("position", seconds(text(state.getDocumentElement(), "RelTime")));
                result.put("duration", seconds(text(state.getDocumentElement(), "TrackDuration")));
                call.resolve(result);
            } catch (Exception error) {
                call.resolve(new JSObject());
            }
        });
    }

    @PluginMethod
    public void stop(PluginCall call) {
        workers.execute(() -> {
            try {
                stopCurrent();
                call.resolve();
            } catch (Exception error) {
                call.reject("停止投屏失败：" + error.getMessage());
            }
        });
    }

    private void stopCurrent() throws Exception {
        Renderer previous = current;
        current = null;
        castSerial++;
        if (polling != null) polling.cancel(false);
        if (previous != null) soap(previous, "Stop", "");
    }

    private Document soap(Renderer renderer, String action, String arguments) throws Exception {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                + "<s:Body><u:" + action + " xmlns:u=\"" + renderer.serviceType + "\">"
                + "<InstanceID>0</InstanceID>" + arguments + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection connection = (HttpURLConnection) (renderer.network == null
                ? renderer.control.openConnection() : renderer.network.openConnection(renderer.control));
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8");
        connection.setRequestProperty("SOAPACTION", "\"" + renderer.serviceType + "#" + action + "\"");
        connection.setDoOutput(true);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200)
                throw new IllegalStateException("电视返回 HTTP " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                return parseXml(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    private Document parseXml(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        // Android 的 XML 解析器不支持 disallow-doctype-decl；拦截外部实体避免读取网络或本地文件。
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder.parse(input);
    }

    private String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private int seconds(String value) {
        String[] parts = value.split(":");
        if (parts.length != 3) return 0;
        try {
            return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    @Override
    protected void handleOnDestroy() {
        if (polling != null) polling.cancel(false);
        monitor.shutdownNow();
        workers.shutdownNow();
    }
}
