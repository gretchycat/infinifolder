import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.FileObserver;
import com.infinifolder.model.*;
import com.infinifolder.storage.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real local provider and inotify events, without initializing the application. */
public class LocalListingRuntimeCheck {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static class FilesContext extends ContextWrapper {
        final File root;
        FilesContext(File root) { super(null); this.root = root; }
        public Context getApplicationContext() { return this; }
        public String getPackageName() { return "com.infinifolder.listingcheck"; }
        public File getFilesDir() { return new File(root, "files"); }
        public File getCacheDir() { return new File(root, "cache"); }
        public File[] getExternalFilesDirs(String type) { return new File[0]; }
        public File[] getExternalCacheDirs() { return new File[0]; }
        public SharedPreferences getSharedPreferences(String name, int mode) {
            throw new AssertionError("Listing initialized unrelated preferences/cache: " + name);
        }
    }
    static final class Reads extends FileObserver {
        final List<String> opened = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch barrier = new CountDownLatch(1);
        Reads(File directory) { super(directory.getAbsolutePath(), OPEN | ACCESS | CLOSE_NOWRITE); }
        public void onEvent(int event, String path) {
            if (".observer-barrier".equals(path)) {
                if ((event & CLOSE_NOWRITE) != 0) barrier.countDown();
            } else if (path != null && (event & (OPEN | ACCESS)) != 0) opened.add(path);
        }
        void flush(File directory) throws Exception {
            barrier = new CountDownLatch(1);
            try (InputStream input = new FileInputStream(new File(directory, ".observer-barrier"))) { input.read(); }
            check(barrier.await(3, TimeUnit.SECONDS), "Filesystem observer must see the real control read");
        }
    }
    static ResourceEntry stat(LocalStorageProvider provider, File file) {
        ProviderResult<ResourceEntry> result = provider.stat(new ResourceRef("local", provider.uriFor(file).toString()));
        check(result instanceof ProviderResult.Success, "Stat failed: " + result);
        return ((ProviderResult.Success<ResourceEntry>) result).getValue();
    }
    static List<ResourceEntry> list(LocalStorageProvider provider, File directory) {
        ProviderResult<List<ResourceEntry>> result = provider.list(new ResourceRef("local", provider.uriFor(directory).toString()));
        check(result instanceof ProviderResult.Success, "Listing failed: " + result);
        return ((ProviderResult.Success<List<ResourceEntry>>) result).getValue();
    }
    static ResourceEntry named(List<ResourceEntry> entries, String name) {
        return entries.stream().filter(e -> e.getMetadata().getName().equals(name)).findFirst().orElseThrow();
    }
    public static void main(String[] args) {
        try {
            File work = new File(args[0]);
            FilesContext context = new FilesContext(work);
            File directory = new File(context.getFilesDir(), "listing");
            check(directory.mkdirs() || directory.isDirectory(), "Fixture directory");
            check(context.getCacheDir().mkdirs() || context.getCacheDir().isDirectory(), "Cache directory");
            byte[] text = "This is plain text under an image extension.\n".repeat(100).getBytes("UTF-8");
            for (int i = 0; i < 256; i++) Files.write(new File(directory, "image-" + i + ".png").toPath(), text);
            File disguised = new File(directory, "image-0.png");
            File extensionless = new File(directory, "extensionless");
            Files.write(extensionless.toPath(), Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS1sAAAAASUVORK5CYII="));
            Files.write(new File(directory, "unknown.png").toPath(), new byte[]{0, -1, 0, -2, 0, -3, 0, -4});
            Files.write(new File(directory, ".observer-barrier").toPath(), new byte[]{1});
            File subdirectory = new File(directory, "folder"); subdirectory.mkdirs();
            Files.createSymbolicLink(new File(directory, "folder-link").toPath(), subdirectory.toPath());
            Files.createSymbolicLink(new File(directory, "broken-link").toPath(), new File(directory, "missing").toPath());

            LocalStorageProvider provider = new LocalStorageProvider(context);
            Reads reads = new Reads(directory); reads.startWatching();
            List<ResourceEntry> entries;
            long elapsed;
            try {
                reads.flush(directory);
                long started = System.nanoTime();
                entries = list(provider, directory);
                elapsed = (System.nanoTime() - started) / 1_000_000;
                reads.flush(directory);
                check(reads.opened.isEmpty(), "Listing opened file contents: " + reads.opened);
                check(entries.size() == 262, "All real filesystem entries returned");
                check(named(entries, disguised.getName()).getMetadata().getMimeType().equals("image/png"), "Listing returns a provisional extension hint");
                check(named(entries, "extensionless").getMetadata().getMimeType().equals("application/octet-stream"), "Extensionless file waits for detection");
                check(named(entries, disguised.getName()).getMetadata().getSizeBytes() == text.length, "Listing includes actual byte length");
                check(named(entries, "folder-link").getMetadata().isSymbolicLink() && named(entries, "folder-link").getMetadata().isDirectory(), "Directory link policy preserved");
                check(named(entries, "broken-link").getMetadata().isBrokenLink() && !named(entries, "broken-link").getMetadata().getCanRead(), "Broken link policy preserved");
                System.out.println("PASS local-listing.default-metadata-first entries=" + entries.size() + " elapsedMs=" + elapsed + " contentOpens=0");

                check(stat(provider, disguised).getMetadata().getMimeType().equals("text/plain"), "Explicit stat validates text behind an incorrect extension");
                reads.flush(directory);
                check(reads.opened.contains(disguised.getName()), "Real filesystem observer must see the deferred content probe");
                check(stat(provider, extensionless).getMetadata().getMimeType().equals("image/png"), "Explicit stat detects extensionless PNG bytes");
                check(stat(provider, new File(directory, "unknown.png")).getMetadata().getMimeType().equals("application/octet-stream"), "Unknown binary bytes remain binary after validation");
                System.out.println("PASS local-listing.deferred-real-content-validation");
            } finally { reads.stopWatching(); }
            File managed = new File(context.getCacheDir(), "managed-cache-v1/slot"); managed.mkdirs();
            Files.write(new File(managed, "snapshot.dat").toPath(), new byte[]{0, 1, 2, 3});
            ResourceMetadata snapshot = named(list(provider, managed), "snapshot.dat").getMetadata();
            check(!snapshot.getCanWrite(), "Managed snapshots stay read-only without scanning cache");
            check(snapshot.getOperationHints().get(ResourceCapability.DELETE) == Support.UNSUPPORTED, "Managed snapshot deletion remains unavailable");
            System.out.println("PASS local-listing.managed-cache-policy-without-cache-initialization");
            System.out.println("LOCAL_LISTING_COMPLETE");
            System.exit(0);
        } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
}
