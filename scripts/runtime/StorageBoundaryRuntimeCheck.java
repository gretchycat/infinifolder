import android.os.ParcelFileDescriptor;
import io.github.gretchycat.infiniselect.StorageAccess;
import java.io.*;

/** Tests real Android file/pipe descriptor behavior without installing or changing an app. */
public final class StorageBoundaryRuntimeCheck {
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }
    public static void main(String[] args) throws Exception {
        File file = new File(args[0], "descriptor.bin");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[]{11,22,33,44}); }
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            check(StorageAccess.INSTANCE.seekable(descriptor) == StorageAccess.Support.SUPPORTED, "file.native-seek");
            android.system.Os.lseek(descriptor.getFileDescriptor(), 2, android.system.OsConstants.SEEK_SET);
            byte[] one = new byte[1];
            check(android.system.Os.read(descriptor.getFileDescriptor(), one, 0, 1) == 1 && one[0] == 33, "file.positional-read");
        }
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
        try (ParcelFileDescriptor reader = pipe[0]; ParcelFileDescriptor writer = pipe[1]) {
            check(StorageAccess.INSTANCE.seekable(reader) == StorageAccess.Support.UNSUPPORTED, "pipe.explicit-nonseekable");
            writer.closeWithError("expected source failure");
            boolean failed = false;
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(reader)) { input.read(); }
            catch (IOException expected) { failed = true; }
            check(failed, "pipe.source-error-reaches-consumer");
        }
        pipe = ParcelFileDescriptor.createReliablePipe();
        try (ParcelFileDescriptor reader = pipe[0]; ParcelFileDescriptor writer = pipe[1]) {
            reader.close();
            boolean failed = false;
            try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(writer)) { output.write(1); }
            catch (IOException expected) { failed = true; }
            check(failed, "pipe.consumer-close-reaches-writer");
        }
        int before = new File("/proc/self/fd").list().length;
        for (int i = 0; i < 256; i++) try (ParcelFileDescriptor descriptor =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            if (StorageAccess.INSTANCE.seekable(descriptor) != StorageAccess.Support.SUPPORTED) throw new AssertionError("seek");
        }
        int after = new File("/proc/self/fd").list().length;
        check(after <= before + 2, "file.repeated-open-close-does-not-leak-descriptors");
        if (!file.delete()) throw new IOException("Could not clean runtime fixture");
    }
}
