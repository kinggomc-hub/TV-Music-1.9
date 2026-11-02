package com.lyricplayer.tv;

import android.content.res.AssetManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 127.0.0.1 전용 아주 작은 HTTP 서버.
 *  - /<토큰>/index.html : 앱 화면(assets)
 *  - /<토큰>/f/<id>     : USB 의 음악·가사 파일 (Range 지원: 탐색·시크바용)
 * 화면과 음악을 같은 주소(origin)에서 내보내야 오디오 분석(웹오디오)이 막히지 않는다.
 * 다른 앱이 함부로 접속하지 못하도록 실행마다 바뀌는 토큰을 주소에 넣는다.
 */
final class LocalServer {
    private final AssetManager assets;
    private final Library lib;
    private final String token = new BigInteger(60, new SecureRandom()).toString(36);
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket ss;
    private volatile boolean run;
    private int port;

    LocalServer(AssetManager assets, Library lib) {
        this.assets = assets;
        this.lib = lib;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port + "/" + token + "/";
    }

    void start() throws IOException {
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        try {
            s.bind(new InetSocketAddress(lo, 38425), 32); // 고정 포트: 저장소(localStorage) 주소를 유지하려고
        } catch (IOException e) {
            s.close();
            s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(lo, 0), 32);
        }
        ss = s;
        port = s.getLocalPort();
        run = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (run) {
                    try {
                        final Socket c = ss.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(c);
                            }
                        });
                    } catch (IOException e) {
                        if (!run) break;
                    }
                }
            }
        }, "http-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        run = false;
        try {
            if (ss != null) ss.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            s.setSendBufferSize(512 * 1024);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 262144);
            String line = readLine(in);
            if (line == null) return;
            String[] rq = line.split(" ");
            if (rq.length < 2) return;
            String method = rq[0], target = rq[1], range = null, h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                if (h.regionMatches(true, 0, "range:", 0, 6)) range = h.substring(6).trim();
            }
            boolean head = method.equals("HEAD");
            if (!head && !method.equals("GET")) {
                simple(out, 405, "Method Not Allowed");
                return;
            }
            int q = target.indexOf('?');
            if (q >= 0) target = target.substring(0, q);
            String pre = "/" + token + "/";
            if (!target.startsWith(pre)) {
                simple(out, 404, "Not Found");
                return;
            }
            String rest = target.substring(pre.length());
            if (rest.isEmpty() || rest.equals("index.html")) serveAsset(out, head);
            else if (rest.startsWith("f/")) serveFile(out, lib.get(rest.substring(2)), range, head);
            else simple(out, 404, "Not Found");
            out.flush();
        } catch (IOException ignored) {
            // 재생 중 탐색 등으로 연결이 끊기는 것은 정상
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void serveAsset(OutputStream out, boolean head) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream a = assets.open("index.html");
        try {
            byte[] b = new byte[16384];
            int n;
            while ((n = a.read(b)) > 0) bo.write(b, 0, n);
        } finally {
            a.close();
        }
        // TV 에서는 화면 폭을 1280 으로 고정한다 (웹뷰 해상도를 낮춰도 글자·버튼 크기가 그대로 유지)
        String html = new String(bo.toByteArray(), StandardCharsets.UTF_8)
                .replace("width=device-width, initial-scale=1", "width=1280");
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        header(out, 200, "OK", "text/html; charset=utf-8", body.length, null);
        if (!head) out.write(body);
    }

    private void serveFile(OutputStream out, Entry e, String range, boolean head) throws IOException {
        if (e == null) {
            simple(out, 404, "Not Found");
            return;
        }
        long size = e.size, start = 0, end = size - 1;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).split(",")[0].trim();
            int dash = spec.indexOf('-');
            try {
                long st = 0, en = size - 1;
                if (dash == 0) {
                    st = Math.max(0, size - Long.parseLong(spec.substring(1)));
                } else if (dash > 0) {
                    st = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) en = Math.min(size - 1, Long.parseLong(spec.substring(dash + 1)));
                } else {
                    throw new NumberFormatException();
                }
                if (st >= size || st > en) {
                    header(out, 416, "Range Not Satisfiable", "text/plain", 0, "bytes */" + size);
                    return;
                }
                start = st;
                end = en;
                partial = true;
            } catch (NumberFormatException ignored) {
                // 잘못된 Range 는 무시하고 전체를 보낸다
            }
        }
        long len = size == 0 ? 0 : end - start + 1;
        String cr = partial ? "bytes " + start + "-" + end + "/" + size : null;
        header(out, partial ? 206 : 200, partial ? "Partial Content" : "OK", mime(e.name), len, cr);
        if (head || len == 0) return;
        Entry.Src src = e.open();
        try {
            byte[] buf = new byte[256 * 1024];
            long pos = start;
            while (pos <= end) {
                int n = src.read(pos, buf, 0, (int) Math.min((long) buf.length, end - pos + 1));
                if (n <= 0) break;
                out.write(buf, 0, n);
                pos += n;
            }
        } finally {
            src.close();
        }
    }

    private static void header(OutputStream out, int code, String msg, String type, long len, String contentRange)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(len).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        if (contentRange != null) sb.append("Content-Range: ").append(contentRange).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void simple(OutputStream out, int code, String msg) throws IOException {
        header(out, code, msg, "text/plain", 0, null);
        out.flush();
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".aac")) return "audio/aac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg") || n.endsWith(".opus")) return "audio/ogg";
        return "application/octet-stream";
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) return null;
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
