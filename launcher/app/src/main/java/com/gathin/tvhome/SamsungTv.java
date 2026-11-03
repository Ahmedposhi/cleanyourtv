package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Drives a Samsung Tizen TV over the LAN remote WebSocket. Newer Crystal UHD sets (e.g. UA75U8000)
 * only accept control on <b>WSS port 8002</b>; plain WS 8001 returns unauthorized with no popup.
 * Used so Inputs on a streaming box can switch the Samsung to another HDMI (satellite on HDMI 1).
 * The auth token is kept in prefs after the first successful connect.
 */
final class SamsungTv {
    private static final String TAG = "SamsungTv";
    private static final String PREFS = "samsung_tv";
    private static final String KEY_TOKEN = "token";
    private static final String APP_NAME = "CleanTV Inputs";
    private static final int PORT = 8002;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private SamsungTv() {
    }

    /** True when a theme/config set a Samsung host to control. */
    static boolean configured(String host) {
        return host != null && !host.trim().isEmpty();
    }

    /**
     * Sends a remote key (e.g. KEY_HDMI1). Calls {@code done} on the worker thread with null on
     * success, or a short error message (including when the TV asks to Allow the app).
     */
    static void sendKey(Context ctx, String host, String key, Consumer<String> done) {
        Context app = ctx.getApplicationContext();
        String h = host == null ? "" : host.trim();
        String k = (key == null || key.trim().isEmpty()) ? "KEY_HDMI1" : key.trim();
        if (h.isEmpty()) {
            done.accept("No Samsung TV configured");
            return;
        }
        IO.execute(() -> {
            try {
                done.accept(sendOnce(app, h, k));
            } catch (Exception e) {
                Log.w(TAG, "sendKey failed", e);
                done.accept(e.getMessage() != null ? e.getMessage() : "Samsung switch failed");
            }
        });
    }

    private static String sendOnce(Context ctx, String host, String key) throws Exception {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_TOKEN, "");
        String name = Base64.encodeToString(APP_NAME.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String path = "/api/v2/channels/samsung.remote.control?name=" + name;
        if (!token.isEmpty()) path += "&token=" + token;

        SSLSocketFactory factory = trustAllFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new InetSocketAddress(host, PORT), 6000);
            socket.setSoTimeout(30000);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            byte[] nonce = new byte[16];
            new Random().nextBytes(nonce);
            String wsKey = Base64.encodeToString(nonce, Base64.NO_WRAP);
            String handshake = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + PORT + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            out.write(handshake.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String status = readHttpHeaders(in);
            if (!status.contains("101")) {
                return "Samsung refused connection";
            }

            for (int i = 0; i < 8; i++) {
                String msg = readTextFrame(in);
                if (msg == null || msg.isEmpty()) continue;
                JSONObject json = new JSONObject(msg);
                String event = json.optString("event", "");
                if ("ms.channel.unauthorized".equals(event)) {
                    return "Allow CleanTV on the Samsung TV, then try again";
                }
                if ("ms.channel.connect".equals(event)) {
                    JSONObject data = json.optJSONObject("data");
                    if (data != null) {
                        String t = data.optString("token", "");
                        if (!t.isEmpty()) prefs.edit().putString(KEY_TOKEN, t).apply();
                    }
                    // Modern Samsung ignores KEY_HDMI1; drive the Source bar to HDMI 1 (Nilesat/Astra).
                    for (String step : keysFor(key)) {
                        writeTextFrame(out, remoteClick(step));
                        Thread.sleep(pauseFor(step));
                    }
                    return null;
                }
            }
            return "No reply from Samsung";
        }
    }

    /**
     * KEY_HDMI1 / hdmi1 → Source-bar path to the first HDMI (satellite). Other values are sent as one key,
     * or as a comma-separated list (e.g. KEY_HOME,KEY_HDMI).
     */
    static String[] keysFor(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty() || k.equalsIgnoreCase("KEY_HDMI1") || k.equalsIgnoreCase("hdmi1")
                || k.equalsIgnoreCase("nilesat") || k.equalsIgnoreCase("astra")
                || k.equalsIgnoreCase("cable")) {
            // Modern sets ignore KEY_HDMI1. UA75U8000 Source is a 2-column grid (page 1):
            //   TV (Samsung TV Plus) | PlayStation
            //   Astra (cable HDMI1)  | SmartThings
            //   Xiaomi              | Help
            // Opens with TV focused → one DOWN = Astra → ENTER. Verified live with user.
            return new String[]{
                    "KEY_SOURCE",
                    "KEY_DOWN",
                    "KEY_ENTER"
            };
        }
        if (k.contains(",")) {
            String[] parts = k.split(",");
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
            return parts;
        }
        return new String[]{k};
    }

    private static long pauseFor(String key) {
        // Source grid needs a beat to appear before DOWN; ENTER needs time to switch HDMI.
        if ("KEY_SOURCE".equals(key) || "KEY_CONTENTS".equals(key)) return 3500;
        if ("KEY_ENTER".equals(key) || "KEY_HOME".equals(key)) return 2500;
        if ("KEY_DOWN".equals(key) || "KEY_UP".equals(key)) return 1000;
        if ("KEY_LEFT".equals(key) || "KEY_RIGHT".equals(key)) return 350;
        return 400;
    }

    private static String remoteClick(String key) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("method", "ms.remote.control");
        JSONObject params = new JSONObject();
        params.put("Cmd", "Click");
        params.put("DataOfCmd", key);
        params.put("Option", "false");
        params.put("TypeOfRemote", "SendRemoteKey");
        payload.put("params", params);
        return payload.toString();
    }

    /** Local TV certs are self-signed; accept them for LAN control only. */
    private static SSLSocketFactory trustAllFactory() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                    public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        return ctx.getSocketFactory();
    }

    private static String readHttpHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) break;
            buf.write(b);
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
        }
        return buf.toString(StandardCharsets.US_ASCII.name());
    }

    private static String readTextFrame(InputStream in) throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        int opcode = b0 & 0x0f;
        long len = b1 & 0x7f;
        if (len == 126) {
            len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff);
        }
        boolean masked = (b1 & 0x80) != 0;
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        if (len > 1_000_000) throw new IllegalStateException("frame too large");
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
        }
        if (opcode == 0x8) return null;
        if (opcode == 0x9 || opcode != 0x1) return "";
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void writeTextFrame(OutputStream out, String text) throws Exception {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);
        byte[] mask = new byte[4];
        new Random().nextBytes(mask);
        if (data.length < 126) {
            frame.write(0x80 | data.length);
        } else {
            frame.write(0x80 | 126);
            frame.write((data.length >> 8) & 0xff);
            frame.write(data.length & 0xff);
        }
        frame.write(mask);
        for (int i = 0; i < data.length; i++) frame.write(data[i] ^ mask[i % 4]);
        out.write(frame.toByteArray());
        out.flush();
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IllegalStateException("short read");
            off += n;
        }
    }
}
