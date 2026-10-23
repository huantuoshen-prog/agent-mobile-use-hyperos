package com.agent.mobileuse;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;

public final class ReleaseFileSelftest {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("release-file-test-").toFile();
        File target = new File(dir, "verified.zip");
        byte[] data = "candidate release payload".getBytes("UTF-8");
        StringBuilder sha = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sha.append(String.format("%02x", b & 255));
        try {
            ReleaseFile.stage(new ByteArrayInputStream(data), target, sha.toString());
            ReleaseFile.verify(target, sha.toString());
            if (!java.util.Arrays.equals(Files.readAllBytes(target.toPath()), data)) throw new AssertionError("payload changed");
            expectFailure(target, () -> ReleaseFile.stage(new ByteArrayInputStream(data), target, new String(new char[64]).replace('\0','0')));
            expectFailure(target, () -> ReleaseFile.stage(new ByteArrayInputStream(data), target, sha.toString(), 3));
            expectFailure(target, () -> ReleaseFile.stage(new InputStream() { public int read() throws IOException { throw new IOException("interrupted"); } }, target, sha.toString()));
            ReleaseFile.stage(new ByteArrayInputStream(data), target, sha.toString());
            Files.write(target.toPath(), "modified".getBytes("UTF-8"));
            try { ReleaseFile.verify(target, sha.toString()); throw new AssertionError("modified cache accepted"); }
            catch (IOException expected) {}
            System.out.println("ReleaseFileSelftest PASS: verified bytes, bad hash, size limit, interrupted transfer, modified cache");
        } finally { target.delete(); dir.delete(); }
    }
    interface Operation { void run() throws Exception; }
    private static void expectFailure(File target, Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("invalid transfer accepted"); }
        catch (IOException expected) { if (target.exists()) throw new AssertionError("partial file retained"); }
    }
}
