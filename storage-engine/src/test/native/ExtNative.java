package com.infinifolder.archive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Runs the shipped JNI bridge with the same block-device method contract. */
public class ExtNative {
    static { System.loadLibrary("infiniext"); }
    native long[] execute(Object device, long size, int action, byte[] source, byte[] target, byte[] content) throws IOException;
    static byte[] utf8(String s) { return s == null ? null : s.getBytes(StandardCharsets.UTF_8); }
    public static class Device implements AutoCloseable {
        final RandomAccessFile file;
        int writes = 0, failAfter = -1, crashAfter = -1;
        boolean interrupt = false;
        Device(String path) throws IOException { file = new RandomAccessFile(path, "rw"); }
        public int readAt(long p, byte[] b, int offset, int length) throws IOException { file.seek(p); return file.read(b, offset, length); }
        public void writeAt(long p, byte[] b, int offset, int length) throws IOException {
            if (crashAfter >= 0 && writes >= crashAfter) Runtime.getRuntime().halt(77);
            if (failAfter >= 0 && writes >= failAfter) throw new IOException("injected write failure");
            if (interrupt) throw new java.io.InterruptedIOException("injected cancellation");
            writes++; file.seek(p); file.write(b, offset, length);
        }
        public void flush() throws IOException { file.getFD().sync(); }
        public void close() throws IOException { file.close(); }
    }
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static long[] run(Device d, int action, String source, String target, String content) throws IOException {
        System.out.println("Operation " + action + " " + source);
        return new ExtNative().execute(d, d.file.length(), action, utf8(source), utf8(target), utf8(content));
    }
    static void mustFail(Device d, int action, String source, String target, String content) throws IOException {
        try { run(d, action, source, target, content); throw new AssertionError("Unexpected success: " + source); }
        catch (IOException expected) { System.out.println("Rejected: " + expected.getMessage()); }
    }
    public static void main(String[] args) throws Exception {
        try (Device d = new Device(args[1])) {
            if (args[0].equals("reject")) { mustFail(d, 0, "", null, null); require(d.writes == 0, "Rejected probe wrote"); return; }
            if (args[0].equals("fault")) {
                d.failAfter = 4; mustFail(d, 2, "failure-test", null, null);
                // Failed snapshot is discarded by owner; ensure native registry is
                // reusable by probing an independent, clean original immediately.
                try (Device fresh = new Device(args[2])) { run(fresh, 0, "", null, null); }
                return;
            }
            if (args[0].equals("recover")) {
                long[] result = run(d, 2, "after-recovery", null, null);
                require(result[4] == 1, "Journal replay not reported");
                return;
            }
            if (args[0].equals("crash")) {
                d.crashAfter = 20;
                run(d, 3, "destination/data-🌍.bin", null, args[2]);
                throw new AssertionError("Crash point not reached");
            }
            if (args[0].equals("metadata")) {
                run(d, 5, "destination/data-🌍.bin", "destination/metadata-moved.bin", null);
                mustFail(d, 3, "pointer/metadata-moved.bin", null, args[2]);
                return;
            }
            if (args[0].equals("unlink-shared")) {
                run(d, 4, "destination/subtree", null, null);
                run(d, 4, "pointer", null, null);
                return;
            }
            if (args[0].equals("corrupt")) {
                mustFail(d, 2, "should-not-exist", null, null);
                try (Device fresh = new Device(args[2])) { run(fresh, 0, "", null, null); }
                return;
            }
            long[] probe = run(d, 0, "", null, null);
            require(d.writes == 0, "Read-only probe wrote to image");
            System.out.println("Probe: block=" + probe[0] + " journal=" + probe[3]);
            run(d, 2, "source", null, null); run(d, 2, "destination", null, null);
            run(d, 2, "source/subtree", null, null);
            run(d, 1, "source/subtree/data-🌍.bin", null, null);
            run(d, 3, "source/subtree/data-🌍.bin", null, args[2]);
            run(d, 1, "source/empty", null, null);
            long inode = run(d, 5, "source/subtree/data-🌍.bin", "source/data-🌍.bin", null)[5];
            require(inode > 0, "Move lost inode");
            long moved = run(d, 5, "source/data-🌍.bin", "destination/data-🌍.bin", null)[5];
            require(inode == moved, "Second move changed inode");
            run(d, 1, "source/subtree/child", null, null);
            run(d, 3, "source/subtree/child", null, args[2]);
            run(d, 5, "source/subtree", "destination/subtree", null);
            run(d, 2, "source/keep", null, null);
            run(d, 2, "destination/keep", null, null);
            mustFail(d, 5, "source/keep", "destination/keep", null);
            mustFail(d, 5, "destination/subtree", "destination/subtree/child-of-self", null);
            mustFail(d, 1, "../escape", null, null);
            mustFail(d, 1, "source/empty", null, null);
            run(d, 4, "source/empty", null, null);
            run(d, 4, "source", null, null);
            long[] finalProbe = run(d, 0, "", null, null);
            require(probe[3] == finalProbe[3], "Journal feature removed");
            System.out.println("File + nonempty directory move, write, recursive delete, collision and path checks passed");
        }
    }
}
