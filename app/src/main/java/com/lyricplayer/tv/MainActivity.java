package com.lyricplayer.tv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_STORAGE = 7;

    private final Library lib = new Library();
    private WebView web;
    private FrameLayout box;
    private LocalServer server;
    private UsbScanner scanner;
    private volatile boolean pageReady = false;
    private volatile boolean scanning = false;
    private boolean autoScan = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scanner = new UsbScanner(getApplicationContext());
        autoScan = isUsbIntent(getIntent());

        web = new WebView(this);
        // 앱이 백그라운드로 밀리지 않는 TV 재생 중에도 WebView 렌더러를 중요 작업으로 유지한다.
        if (Build.VERSION.SDK_INT >= 26) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }
        web.setBackgroundColor(Color.BLACK);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        box = new FrameLayout(this);
        box.setBackgroundColor(Color.BLACK);
        box.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(box);
        hideSystemUi();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // 자동 재생/오디오 분석이 막히지 않게
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setUseWideViewPort(true);      // 화면 쪽에서 viewport 를 1280 으로 고정해 해상도와 상관없이 같은 배치가 되게 한다
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true); // chrome://inspect 로 원격 디버깅
        }

        try {
            server = new LocalServer(getAssets(), lib);
            server.start();
        } catch (IOException e) {
            Toast.makeText(this, "내부 서버를 시작하지 못했습니다: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return !url.startsWith(server.baseUrl()); // 외부 주소로는 이동하지 않는다
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView v, int code, String desc, String url) {
                if (url != null && url.startsWith(server.baseUrl() + "index.html")) {
                    v.loadData("<body style='background:#000;color:#fff;font-size:28px'>화면을 불러오지 못했습니다: "
                            + desc + "<br>뒤로 버튼으로 종료하세요.</body>", "text/html; charset=utf-8", null);
                }
            }
        });
        web.loadUrl(server.baseUrl() + "index.html");
    }

    // ------------------------------------------------------------ 리모컨
    /** 리모컨 키는 웹뷰의 기본 포커스 이동에 맡기지 않고 전부 화면(JS)의 tvKey()로 넘긴다. */
    private static String mapKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: return "up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "right";
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "ok";
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE: return "back";
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_SETTINGS: return "menu";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK: return "playpause";
            case KeyEvent.KEYCODE_MEDIA_PLAY: return "play";
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return "pause";
            case KeyEvent.KEYCODE_MEDIA_STOP: return "stop";
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP: return "next";
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN: return "prev";
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "ff";
            case KeyEvent.KEYCODE_MEDIA_REWIND: return "rew";
            default: return null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        String cmd = mapKey(e.getKeyCode());
        if (cmd == null) return super.dispatchKeyEvent(e);
        if (!pageReady) { // 화면이 아직 안 떴을 때는 뒤로 키로 종료만 가능
            if (cmd.equals("back") && e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            js("window.tvKey&&tvKey('" + cmd + "'," + e.getRepeatCount() + ")");
        }
        return true;
    }

    // ------------------------------------------------------------ USB 검색
    private void beginScan() {
        if (scanning) return;
        scanning = true;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            return; // 결과는 onRequestPermissionsResult 에서 이어간다
        }
        runScan();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_STORAGE) runScan(); // 거부해도 2단계(USB 직접 접근)로 시도한다
    }

    private void runScan() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Entry> res = new ArrayList<>();
                String via = "";
                scanner.note = "";
                try {
                    UsbScanner.Progress prog = new UsbScanner.Progress() {
                        @Override
                        public void on(int n) {
                            js("window.onUsbProgress&&onUsbProgress(" + n + ")");
                        }
                    };
                    res = scanner.scanMounted(prog);
                    via = "마운트된 USB/SD";
                    if (res.isEmpty()) {
                        String first = scanner.note;
                        List<Entry> raw = scanner.scanRaw(prog);
                        if (!raw.isEmpty()) {
                            res = raw;
                            via = "USB 직접 접근";
                        } else if (scanner.note.isEmpty()) {
                            scanner.note = first;
                        }
                    }
                } catch (Throwable t) {
                    scanner.note = "검색 중 오류가 발생했습니다: " + t;
                }
                lib.replace(res);
                scanning = false;
                js("window.onUsbDone&&onUsbDone(" + res.size() + "," + JSONObject.quote(via) + ","
                        + JSONObject.quote(scanner.note) + ")");
            }
        }, "usb-scan").start();
    }

    // ------------------------------------------------------------ 화면(JS) 연결
    public class Bridge {
        @JavascriptInterface
        public boolean isTv() {
            return true;
        }

        /** 화면이 준비됐음을 알린다. USB 연결로 앱이 켜졌다면 true (자동으로 불러오기). */
        @JavascriptInterface
        public boolean ready() {
            pageReady = true;
            boolean a = autoScan;
            autoScan = false;
            // 앱 아이콘으로 직접 실행해도 USB를 자동 검색한다.
            return true;
        }

        @JavascriptInterface
        public void scanUsb() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    beginScan();
                }
            });
        }

        @JavascriptInterface
        public String getEntries(int from, int n) {
            return lib.json(from, n);
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finishAndRemoveTask();
                }
            });
        }
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    // ------------------------------------------------------------ 수명 주기
    private static boolean isUsbIntent(Intent i) {
        return i != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (isUsbIntent(intent) && pageReady) js("window.onUsbAttached&&onUsbAttached()");
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        scanner.closeAll();
        if (web != null) {
            web.removeJavascriptInterface("Android");
            web.destroy();
        }
        boolean fin = isFinishing();
        super.onDestroy();
        if (fin) Process.killProcess(Process.myPid()); // 종료하면 소리도 확실히 멈추게
    }
}
