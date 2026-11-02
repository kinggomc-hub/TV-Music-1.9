package com.lyricplayer.tv;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

import me.jahnen.libaums.core.fs.UsbFile;

/** 음악 또는 가사 파일 한 개. 마운트된 경로(File)이거나 USB 직접 접근(UsbFile) 중 하나. */
final class Entry {
    final String id, name, dir;
    final long size;
    final boolean audio;
    final File file;
    final UsbFile usb;

    Entry(String id, String name, String dir, long size, boolean audio, File file, UsbFile usb) {
        this.id = id;
        this.name = name;
        this.dir = dir;
        this.size = size;
        this.audio = audio;
        this.file = file;
        this.usb = usb;
    }

    /** 임의 위치 읽기. */
    interface Src extends Closeable {
        int read(long pos, byte[] b, int off, int len) throws IOException;
    }

    Src open() throws IOException {
        if (file != null) {
            final RandomAccessFile raf = new RandomAccessFile(file, "r");
            return new Src() {
                @Override
                public int read(long pos, byte[] b, int off, int len) throws IOException {
                    raf.seek(pos);
                    return raf.read(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    raf.close();
                }
            };
        }
        final UsbFile uf = usb;
        return new Src() {
            @Override
            public int read(long pos, byte[] b, int off, int len) throws IOException {
                long total = uf.getLength();
                if (pos >= total) return -1;
                int n = (int) Math.min((long) len, total - pos);
                // libaums 는 동시 접근에 안전하지 않으므로 한 번에 하나만 읽는다
                synchronized (UsbScanner.USB_LOCK) {
                    uf.read(pos, ByteBuffer.wrap(b, off, n));
                }
                return n;
            }

            @Override
            public void close() {
            }
        };
    }
}
