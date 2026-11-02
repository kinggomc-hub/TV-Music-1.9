package com.lyricplayer.tv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import me.jahnen.libaums.core.UsbMassStorageDevice;
import me.jahnen.libaums.core.fs.UsbFile;

/**
 * USB 저장장치에서 음악(.mp3 등)과 같은 이름의 가사(.lrc/.txt)를 찾는다. 파일 관리자 앱·시스템 파일 선택창을 쓰지 않는다.
 *  1단계 scanMounted : 안드로이드가 이미 마운트한 USB/SD (/storage/XXXX-XXXX 등)를 직접 읽는다. FAT32·exFAT·NTFS 등은 기기 커널이 지원하는 만큼.
 *  2단계 scanRaw     : 마운트가 안 됐거나 접근이 막힌 경우, USB 호스트 API 로 장치를 직접 열어 읽는다 (libaums, FAT32 전용).
 */
final class UsbScanner {
    static final Object USB_LOCK = new Object();
    private static final String ACTION_PERM = "com.lyricplayer.tv.USB_PERMISSION";
    private static final int MAX_FILES = 8000, MAX_DEPTH = 12;

    interface Progress {
        void on(int found);
    }

    /** 사용자에게 보여 줄 검색 결과 설명 */
    volatile String note = "";

    private final Context ctx;
    private final Map<String, UsbMassStorageDevice> opened = new HashMap<>();

    UsbScanner(Context ctx) {
        this.ctx = ctx;
    }

    // ---------------------------------------------------------------- 1단계: 마운트된 볼륨

    List<Entry> scanMounted(Progress p) {
        List<Entry> out = new ArrayList<>();
        List<File> roots = removableRoots();
        int unreadable = 0, readable = 0;
        for (File r : roots) {
            File[] ch = r.listFiles();
            if (ch == null) {
                unreadable++;
                continue;
            }
            readable++;
            walkFile(r, r, "", 0, out, p);
        }
        List<Entry> res = finish(out);
        if (res.isEmpty()) {
            if (readable > 0) note = "USB는 읽었지만 음악 파일(mp3, flac, m4a, aac, wav, ogg, opus)을 찾지 못했습니다.";
            else if (unreadable > 0) note = "USB는 연결되어 있지만 읽기 권한이 없습니다. 저장소 권한을 허용했는지 확인하세요.";
            else note = "마운트된 USB/SD 저장장치를 찾지 못했습니다.";
        }
        return res;
    }

    private List<File> removableRoots() {
        List<File> roots = new ArrayList<>();
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                StorageManager sm = (StorageManager) ctx.getSystemService(Context.STORAGE_SERVICE);
                for (StorageVolume v : sm.getStorageVolumes()) {
                    if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
                    File dir = null;
                    if (Build.VERSION.SDK_INT >= 30) {
                        dir = v.getDirectory();
                    } else {
                        try {
                            Object path = StorageVolume.class.getMethod("getPath").invoke(v);
                            if (path != null) dir = new File((String) path);
                        } catch (Exception ignored) {
                        }
                    }
                    if (dir != null && !roots.contains(dir)) roots.add(dir);
                }
            }
        } catch (Throwable ignored) {
        }
        File[] st = new File("/storage").listFiles();
        if (st != null) {
            for (File f : st) {
                String n = f.getName();
                if (n.equals("emulated") || n.equals("self") || n.startsWith(".")) continue;
                if (f.isDirectory() && !roots.contains(f)) roots.add(f);
            }
        }
        if (roots.isEmpty()) { // 오래된 셋톱박스의 USB 경로
            String[] bases = {"/mnt/usb_storage", "/mnt/usbhost", "/mnt/media_rw", "/mnt/sda"};
            for (String b : bases) {
                File[] ch = new File(b).listFiles();
                if (ch != null) for (File f : ch) if (f.isDirectory()) roots.add(f);
            }
        }
        return roots;
    }

    private void walkFile(File root, File dir, String rel, int depth, List<Entry> out, Progress p) {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String n = f.getName();
            if (skip(n)) continue;
            if (f.isDirectory()) {
                walkFile(root, f, rel.isEmpty() ? n : rel + "/" + n, depth + 1, out, p);
            } else {
                int k = kind(n);
                if (k == 0) continue;
                out.add(new Entry(mkId(root.getAbsolutePath(), rel, n), n, rel, f.length(), k == 1, f, null));
                if (p != null && out.size() % 50 == 0) p.on(out.size());
            }
        }
    }

    // ---------------------------------------------------------------- 2단계: USB 직접 접근

    List<Entry> scanRaw(Progress p) {
        List<Entry> out = new ArrayList<>();
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        UsbMassStorageDevice[] devs;
        try {
            devs = UsbMassStorageDevice.getMassStorageDevices(ctx);
        } catch (Throwable t) {
            note = "USB 장치 조회에 실패했습니다: " + t.getMessage();
            return out;
        }
        if (devs.length == 0) {
            note = "연결된 USB 저장장치를 찾지 못했습니다. 장치를 연결한 뒤 다시 시도하세요.";
            return out;
        }
        // 뽑힌 장치 정리
        Set<String> present = new HashSet<>();
        for (UsbMassStorageDevice d : devs) present.add(d.getUsbDevice().getDeviceName());
        synchronized (opened) {
            for (String k : new ArrayList<>(opened.keySet())) {
                if (!present.contains(k)) {
                    try {
                        opened.remove(k).close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        StringBuilder why = new StringBuilder();
        for (UsbMassStorageDevice d : devs) {
            UsbDevice ud = d.getUsbDevice();
            if (!um.hasPermission(ud) && !askPermission(um, ud)) {
                why.append("USB 사용 권한이 거부되었습니다. ");
                continue;
            }
            try {
                UsbMassStorageDevice dev;
                synchronized (opened) {
                    dev = opened.get(ud.getDeviceName());
                    if (dev == null) {
                        d.init();
                        opened.put(ud.getDeviceName(), d);
                        dev = d;
                    }
                }
                int parts = dev.getPartitions().size();
                if (parts == 0) why.append("인식 가능한 파티션이 없습니다(FAT32만 지원). ");
                for (int i = 0; i < parts; i++) {
                    UsbFile root = dev.getPartitions().get(i).getFileSystem().getRootDirectory();
                    walkUsb(ud.getVendorId() + ":" + ud.getProductId() + ":" + i, root, "", 0, out, p);
                }
            } catch (Throwable t) {
                why.append("읽기에 실패했습니다(").append(t.getClass().getSimpleName())
                        .append("). 직접 접근은 FAT32 형식만 지원합니다. ");
            }
        }
        List<Entry> res = finish(out);
        if (res.isEmpty()) {
            note = why.length() > 0 ? why.toString().trim()
                    : "USB를 읽었지만 음악 파일(mp3, flac, m4a, aac, wav, ogg, opus)을 찾지 못했습니다.";
        }
        return res;
    }

    private void walkUsb(String rootKey, UsbFile dir, String rel, int depth, List<Entry> out, Progress p)
            throws IOException {
        if (depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        UsbFile[] list;
        synchronized (USB_LOCK) {
            list = dir.listFiles();
        }
        for (UsbFile f : list) {
            String n = f.getName();
            if (skip(n)) continue;
            if (f.isDirectory()) {
                walkUsb(rootKey, f, rel.isEmpty() ? n : rel + "/" + n, depth + 1, out, p);
            } else {
                int k = kind(n);
                if (k == 0) continue;
                out.add(new Entry(mkId(rootKey, rel, n), n, rel, f.getLength(), k == 1, null, f));
                if (p != null && out.size() % 50 == 0) p.on(out.size());
            }
        }
    }

    private boolean askPermission(UsbManager um, UsbDevice ud) {
        final CountDownLatch latch = new CountDownLatch(1);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (ACTION_PERM.equals(i.getAction())) latch.countDown();
            }
        };
        ctx.registerReceiver(r, new IntentFilter(ACTION_PERM));
        try {
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ACTION_PERM), 0);
            um.requestPermission(ud, pi);
            latch.await(45, TimeUnit.SECONDS); // 리모컨으로 시스템 허용 창에서 '확인'을 누를 시간
        } catch (InterruptedException ignored) {
        } finally {
            try {
                ctx.unregisterReceiver(r);
            } catch (Exception ignored) {
            }
        }
        return um.hasPermission(ud);
    }

    void closeAll() {
        synchronized (opened) {
            for (UsbMassStorageDevice d : opened.values()) {
                try {
                    d.close();
                } catch (Throwable ignored) {
                }
            }
            opened.clear();
        }
    }

    // ---------------------------------------------------------------- 공통

    private static boolean skip(String n) {
        if (n.startsWith(".") || n.startsWith("$")) return true;
        String l = n.toLowerCase(Locale.ROOT);
        return l.equals("android") || l.equals("lost.dir") || l.equals("system volume information")
                || l.equals("found.000");
    }

    /** 1 = 음악, 2 = 가사, 0 = 무관. (WebView 가 재생하지 못하는 wma 등은 제외) */
    private static int kind(String n) {
        int d = n.lastIndexOf('.');
        if (d < 0) return 0;
        switch (n.substring(d + 1).toLowerCase(Locale.ROOT)) {
            case "mp3": case "flac": case "m4a": case "aac": case "wav": case "ogg": case "opus":
                return 1;
            case "lrc": case "txt":
                return 2;
            default:
                return 0;
        }
    }

    private static String mkId(String rootKey, String rel, String name) {
        return UUID.nameUUIDFromBytes((rootKey + "|" + rel + "|" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String key(Entry e) {
        String base = e.name.replaceFirst("\\.[^.]+$", "");
        return Normalizer.normalize(e.dir + "|" + base, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** 짝이 되는 음악이 있는 가사만 남기고 정렬한다. */
    private static List<Entry> finish(List<Entry> in) {
        Set<String> keys = new HashSet<>();
        for (Entry e : in) if (e.audio) keys.add(key(e));
        List<Entry> out = new ArrayList<>();
        for (Entry e : in) if (e.audio || keys.contains(key(e))) out.add(e);
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return (a.dir + "/" + a.name).compareToIgnoreCase(b.dir + "/" + b.name);
            }
        });
        return out;
    }
}
