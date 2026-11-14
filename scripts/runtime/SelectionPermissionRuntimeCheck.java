import android.content.pm.PathPermission;
import android.content.res.AssetManager;
import android.content.res.XmlResourceParser;
import android.os.PatternMatcher;
import org.xmlpull.v1.XmlPullParser;
import java.util.ArrayList;
import java.util.List;

/** Checks the packaged relay security boundary using Android's actual binary XML and path matcher. */
public final class SelectionPermissionRuntimeCheck {
    private static final String NS = "http://schemas.android.com/apk/res/android";
    private static final String READ = "com.infinifolder.permission.READ_SELECTION";
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        System.out.println("PASS " + message);
    }
    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
    private static void run(String[] args) throws Exception {
        AssetManager assets = AssetManager.class.getDeclaredConstructor().newInstance();
        int cookie = (Integer) assets.getClass().getDeclaredMethod("addAssetPath", String.class)
            .invoke(assets, args[0]);
        check(cookie != 0, "packaged manifest opens on Android");
        boolean relay = false, found = false, exported = false, grantable = false, signature = false;
        String readPermission = null, writePermission = null;
        List<PathPermission> paths = new ArrayList<>();
        try (XmlResourceParser xml = assets.openXmlResourceParser(cookie, "AndroidManifest.xml")) {
            for (int event = xml.getEventType(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                String tag = xml.getName();
                if (event == XmlPullParser.START_TAG && "permission".equals(tag)
                        && READ.equals(xml.getAttributeValue(NS, "name"))) {
                    signature = (xml.getAttributeIntValue(NS, "protectionLevel", 0) & 15) == 2;
                }
                if (event == XmlPullParser.START_TAG && "provider".equals(tag)) {
                    relay = "com.infinifolder.selection".equals(xml.getAttributeValue(NS, "authorities"));
                    if (relay) {
                        found = true;
                        exported = xml.getAttributeBooleanValue(NS, "exported", false);
                        grantable = xml.getAttributeBooleanValue(NS, "grantUriPermissions", false);
                        String common = xml.getAttributeValue(NS, "permission");
                        readPermission = xml.getAttributeValue(NS, "readPermission");
                        writePermission = xml.getAttributeValue(NS, "writePermission");
                        if (readPermission == null) readPermission = common;
                        if (writePermission == null) writePermission = common;
                    }
                } else if (event == XmlPullParser.END_TAG && "provider".equals(tag)) {
                    relay = false;
                } else if (relay && event == XmlPullParser.START_TAG && "path-permission".equals(tag)) {
                    String prefix = xml.getAttributeValue(NS, "pathPrefix");
                    check(prefix != null, "relay uses an explicit protected path prefix");
                    paths.add(new PathPermission(prefix, PatternMatcher.PATTERN_PREFIX,
                        xml.getAttributeValue(NS, "readPermission"), xml.getAttributeValue(NS, "writePermission")));
                }
            }
        } finally {
            assets.close();
        }
        check(found && exported, "resolver endpoint remains exported");
        check(readPermission == null && writePermission == null && paths.stream().noneMatch(p -> p.match("")),
            "authority root has no file permission restriction");
        check(grantable, "Android URI delegation remains enabled");
        // AOSP UriGrantsManagerService treats an exported unprotected path as
        // already accessible and skips ordinary grant bookkeeping. Protected
        // token paths prevent that shortcut; the app's explicit grant check can succeed.
        for (String path : new String[]{"/12345678-1234-1234-1234-123456789abc", "/another-token"}) {
            check(paths.stream().anyMatch(p -> p.match(path) && READ.equals(p.getReadPermission())
                    && READ.equals(p.getWritePermission())),
                "file link matches protected read/write path: " + path);
        }
        check(signature, "path permission cannot be obtained by an unrelated application");
        System.out.println("Packaged relay permission checks passed; this does not execute a cross-UID file read.");
    }
}
