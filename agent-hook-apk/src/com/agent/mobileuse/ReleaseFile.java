package com.agent.mobileuse;

import java.io.*;
import java.security.MessageDigest;

/** Stage in private cache. A failed or partial transfer must not become a ZIP. */
final class ReleaseFile {
    static final long MAX_BYTES = 64L * 1024 * 1024;

    static void stage(InputStream source, File target, String expected) throws Exception {
        stage(source, target, expected, MAX_BYTES);
    }

    static void stage(InputStream source, File target, String expected, long limit) throws Exception {
        boolean complete = false;
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = source.read(buffer)) != -1) {
                    total += n;
                    if (total > limit) throw new IOException("文件超出发布包大小限制");
                    hash.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            match(hash.digest(), expected);
            complete = true;
        } finally {
            if (!complete) target.delete();
        }
    }

    static void verify(File file, String expected) throws Exception {
        if (!file.isFile() || file.length() > MAX_BYTES) throw new IOException("缓存文件不可用，请重新下载");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n);
        }
        match(hash.digest(), expected);
    }

    private static void match(byte[] value, String expected) throws IOException {
        StringBuilder digest = new StringBuilder();
        for (byte b : value) digest.append(String.format("%02x", b & 255));
        if (expected == null || !expected.matches("[a-fA-F0-9]{64}") || !digest.toString().equalsIgnoreCase(expected))
            throw new IOException("SHA-256 不匹配，文件未保存");
    }
}
