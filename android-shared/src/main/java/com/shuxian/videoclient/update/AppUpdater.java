package com.shuxian.videoclient.update;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** 手机与电视共用更新下载、APK 校验和安装授权流程，UI 各自处理。 */
public final class AppUpdater {
    private static final String RELEASE_API = "https://api.github.com/repos/johnshuxian/videoClient/releases/latest";
    private static final String RELEASE_BASE = "https://github.com/johnshuxian/videoClient/releases/download/";
    private final Context context;
    private final boolean television;
    private UpdateInfo pending;
    private File installer;
    private boolean awaitingPermission;

    public interface ProgressListener { void onProgress(int percent); }

    public static final class UpdateInfo {
        public final String version, notes, name, url, sha256, route;
        public final long size;

        private UpdateInfo(String version, String notes, String name, String url, String sha256, long size, String route) {
            this.version = version;
            this.notes = notes;
            this.name = name;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
            this.route = route;
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject().put("version", version).put("notes", notes).put("size", size);
        }
    }

    public AppUpdater(Context context, boolean television) {
        this.context = context.getApplicationContext();
        this.television = television;
    }

    public static String updateAddress(String url, String route) throws IOException {
        if (!"gh-proxy".equals(route) && !"direct".equals(route)) throw new IOException("无效的更新线路");
        return "gh-proxy".equals(route) ? "https://gh-proxy.org/" + url : url;
    }

    public static boolean isNewerVersion(String candidate, String current) throws IOException {
        if (!candidate.matches("v?\\d+\\.\\d+\\.\\d+") || !current.matches("v?\\d+\\.\\d+\\.\\d+"))
            throw new IOException("更新版本号格式无效");
        String[] next = candidate.replaceFirst("^v", "").split("\\.");
        String[] previous = current.replaceFirst("^v", "").split("\\.");
        for (int index = 0; index < 3; index++) {
            int comparison = Long.compare(Long.parseLong(next[index]), Long.parseLong(previous[index]));
            if (comparison != 0) return comparison > 0;
        }
        return false;
    }

    public String currentVersion() throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
    }

    private HttpURLConnection connect(String url, String route) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(updateAddress(url, route)).openConnection();
        connection.setConnectTimeout(25000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "videoClient");
        connection.setUseCaches(false);
        int status = connection.getResponseCode();
        if (status != 200) {
            connection.disconnect();
            throw new IOException("更新请求失败（HTTP " + status + "）");
        }
        return connection;
    }

    // 网络和文件操作由调用方放到工作线程，不阻塞遥控器或 WebView。
    public UpdateInfo check(String route) throws Exception {
        pending = null;
        HttpURLConnection connection = connect(RELEASE_API, route);
        JSONObject release;
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 2 * 1024 * 1024) throw new IOException("版本信息大小异常");
                output.write(buffer, 0, count);
            }
            release = new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        } finally { connection.disconnect(); }
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
        String tag = release.getString("tag_name");
        if (!isNewerVersion(tag, currentVersion())) return null;
        String version = tag.replaceFirst("^v", "");
        String name = "videoClient-v" + version + (television ? "-android-tv.apk" : "-android.apk");
        JSONArray assets = release.getJSONArray("assets");
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.getJSONObject(index);
            if (!name.equals(asset.getString("name"))) continue;
            String url = asset.getString("browser_download_url");
            String digest = asset.optString("digest");
            long size = asset.optLong("size");
            if (!url.equals(RELEASE_BASE + tag + "/" + name) || !digest.matches("sha256:[a-fA-F0-9]{64}") || size <= 0)
                throw new IOException("更新安装包信息不完整，暂时无法下载");
            pending = new UpdateInfo(version, release.optString("body"), name, url, digest.substring(7), size, route);
            return pending;
        }
        throw new IOException("新版本暂未提供当前设备的安装包");
    }

    public void download(ProgressListener progress) throws Exception {
        UpdateInfo update = pending;
        if (update == null) throw new IOException("请先检查更新");
        installer = null;
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新目录");
        File target = new File(directory, update.name);
        File partial = new File(directory, update.name + ".part");
        HttpURLConnection connection = connect(update.url, update.route);
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            long received = 0;
            int reported = -1;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
                    received += count;
                    if (received > update.size) throw new IOException("更新文件大小不符");
                    hash.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    int percent = (int) (received * 100 / update.size);
                    if (percent != reported) { reported = percent; progress.onProgress(percent); }
                }
            }
            StringBuilder digest = new StringBuilder();
            for (byte value : hash.digest()) digest.append(String.format("%02x", value & 0xff));
            if (received != update.size || !digest.toString().equalsIgnoreCase(update.sha256))
                throw new IOException("更新文件校验失败，请重新下载");
            verifyApk(partial, update.version);
            if (target.exists() && !target.delete()) throw new IOException("无法替换旧更新包");
            if (!partial.renameTo(target)) throw new IOException("无法保存更新包");
            installer = target;
        } finally {
            connection.disconnect();
            if (partial.exists()) partial.delete();
        }
    }

    private void verifyApk(File file, String version) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo current = manager.getPackageInfo(context.getPackageName(), flags);
        PackageInfo downloaded = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (downloaded == null || !context.getPackageName().equals(downloaded.packageName) ||
                !version.equals(downloaded.versionName) ||
                (Build.VERSION.SDK_INT >= 28 ? downloaded.getLongVersionCode() <= current.getLongVersionCode() : downloaded.versionCode <= current.versionCode))
            throw new IOException("更新包与当前应用不匹配");
        Signature[] currentSigners = Build.VERSION.SDK_INT >= 28 ? current.signingInfo.getApkContentsSigners() : current.signatures;
        Signature[] downloadedSigners = Build.VERSION.SDK_INT >= 28 ? downloaded.signingInfo.getApkContentsSigners() : downloaded.signatures;
        // APK 必须沿用当前安装版的签名，避免下载代理替换为其它应用。
        if (currentSigners == null || currentSigners.length == 0 || !Arrays.equals(currentSigners, downloadedSigners))
            throw new IOException("更新包签名与当前应用不一致，无法覆盖安装");
    }

    public boolean install(Activity activity) throws IOException {
        if (installer == null || !installer.isFile()) throw new IOException("请先下载更新包");
        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
            awaitingPermission = true;
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + context.getPackageName())));
            return false;
        }
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", installer);
        activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        awaitingPermission = false;
        return true;
    }

    /** 用户从“允许安装未知应用”返回后，继续已同意的安装，不再重新下载。 */
    public void resumeInstallation(Activity activity) throws IOException {
        if (awaitingPermission && (Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls()))
            install(activity);
    }
}
