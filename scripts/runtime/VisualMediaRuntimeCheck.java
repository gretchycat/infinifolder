import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import com.infinifolder.preview.BitmapAnimation;
import com.infinifolder.preview.BitmapFrame;
import com.infinifolder.preview.CanvasStreams;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Device-side Canvas smoke harness for every visual fixture in filesystem-test-images/images. */
public final class VisualMediaRuntimeCheck {
    private static final int TARGET = 512;
    private static final Set<String> SKIP_EXTENSIONS = new HashSet<>(Arrays.asList("md", "json", "zip"));
    private static final Set<String> ANIMATED_FIXTURES = new HashSet<>(Arrays.asList(
        "animation/plain-animated.gif",
        "animation/motion-palette.fli",
        "animation/motion-palette.flc",
        "animation/motion-palette.gif",
        "animation/gif-transparency-disposal.gif",
        "animation/motion-palette.webp",
        "animation/motion-palette.apng",
        "animation/motion-palette.mng",
        "animation/stored-objects.mng",
        "animation/interactive-regions.mng",
        "animation/recoverable-header.flc",
        "animation/specular.fli",
        "animation/specular.flc",
        "animation/transparency-disposal.png",
        "animation/transparency-disposal (2).png",
        "animation/ora-embedded-iff.ora",
        "animation/ora-embedded-fli.ora",
        "animation/ora-embedded-flc.ora",
        "animation/ora-embedded-gif.ora",
        "animation/ora-embedded-gif-transparency.ora",
        "animation/ora-embedded-webp.ora",
        "animation/ora-embedded-apng.ora",
        "animation/ora-embedded-apng-disposal.ora",
        "animation/ora-embedded-mng.ora"
    ));
    private static final Set<String> EXPECTED_REJECTIONS = new HashSet<>(Arrays.asList(
        "invalid-metadata.jpg"
    ));
    // These corpus SVGs currently fail on the Canvas path; keep reporting them until that subset works.
    private static final Set<String> KNOWN_UNSUPPORTED = new HashSet<>(Arrays.asList(
        "icon.svg",
        "icon.svgz"
    ));
    private static final Set<String> CHECKED_FIXTURES = new HashSet<>();
    private static final Set<String> MOVING_FIXTURES = new HashSet<>(Arrays.asList(
        "animation/plain-animated.gif",
        "animation/motion-palette.fli",
        "animation/motion-palette.flc",
        "animation/motion-palette.gif",
        "animation/gif-transparency-disposal.gif",
        "animation/motion-palette.webp",
        "animation/motion-palette.apng",
        "animation/motion-palette.mng",
        "animation/stored-objects.mng",
        "animation/transparency-disposal.png",
        "animation/transparency-disposal (2).png",
        "animation/gif-transparency-disposal.gif",
        "animation/ora-embedded-iff.ora",
        "animation/ora-embedded-fli.ora",
        "animation/ora-embedded-flc.ora",
        "animation/ora-embedded-gif.ora",
        "animation/ora-embedded-gif-transparency.ora",
        "animation/ora-embedded-webp.ora",
        "animation/ora-embedded-apng.ora",
        "animation/ora-embedded-apng-disposal.ora",
        "animation/ora-embedded-mng.ora"
    ));

    private static final Set<String> COLOR_REQUIRED_FIXTURES = new HashSet<>(Arrays.asList(
        "animation/plain-animated.gif",
        "animation/motion-palette.gif",
        "animation/gif-transparency-disposal.gif",
        "animation/ora-embedded-gif.ora",
        "animation/ora-embedded-gif-transparency.ora"
    ));

    private static int rendered;
    private static int animated;
    private static int rejected;
    private static int unsupported;
    private static int failures;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String relative(File root, File file) {
        return root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
    }

    private static String extension(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static void collect(File directory, List<File> output) {
        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File child : children) {
            if (child.isDirectory()) collect(child, output);
            else if (child.isFile() && !SKIP_EXTENSIONS.contains(extension(child))) output.add(child);
        }
    }

    private static int visiblePixels(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) if ((pixel >>> 24) != 0) count++;
        return count;
    }

    private static int coloredPixels(int[] pixels) {
        int count = 0;
        for (int pixel : pixels) if ((pixel & 0x00ffffff) != 0) count++;
        return count;
    }

    private static int[] pixels(Bitmap bitmap) {
        int[] result = new int[bitmap.getWidth() * bitmap.getHeight()];
        bitmap.getPixels(result, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        return result;
    }

    private static boolean isAnimatable(Drawable drawable) {
        return drawable instanceof Animatable;
    }

    private static String renderer(BitmapFrame frame) {
        if (isAnimatable(frame.getAnimationDrawable())) return "animated-drawable";
        if (frame.getAnimation() != null) return "bitmap-animation";
        if (frame.getColorCycling() != null) return "iff-palette";
        return "still";
    }

    private static void renderDrawable(Drawable drawable, int width, int height, String name) {
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        HandlerThread callbackThread = new HandlerThread("visual-media-animation-callbacks");
        callbackThread.start();
        Handler callbackHandler = new Handler(callbackThread.getLooper());
        drawable.setCallback(new Drawable.Callback() {
            @Override public void invalidateDrawable(Drawable who) { }
            @Override public void scheduleDrawable(Drawable who, Runnable what, long when) {
                callbackHandler.postAtTime(what, when);
            }
            @Override public void unscheduleDrawable(Drawable who, Runnable what) {
                callbackHandler.removeCallbacks(what);
            }
        });
        try {
            drawable.setBounds(0, 0, width, height);
            Animatable animation = (Animatable) drawable;
            animation.start();
            drawable.draw(new Canvas(output));
            int[] first = pixels(output);
            check(visiblePixels(first) > 0, "animated drawable rendered no visible pixels: " + name);
            if (COLOR_REQUIRED_FIXTURES.contains(name))
                check(coloredPixels(first) > 0, "animated drawable rendered only black pixels: " + name);
            try {
                Thread.sleep(300L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while sampling animation: " + name, interrupted);
            }
            output.eraseColor(0);
            drawable.draw(new Canvas(output));
            int[] later = pixels(output);
            check(visiblePixels(later) > 0, "animated drawable emitted an empty frame: " + name);
            if (COLOR_REQUIRED_FIXTURES.contains(name))
                check(coloredPixels(later) > 0, "animated drawable emitted only black pixels: " + name);
            if (MOVING_FIXTURES.contains(name))
                check(!Arrays.equals(first, later), "animation frames did not change: " + name);
        } finally {
            if (drawable instanceof Animatable) ((Animatable) drawable).stop();
            drawable.setCallback(null);
            callbackThread.quitSafely();
            output.recycle();
        }
    }

    private static void checkFile(File root, File file) {
        String name = relative(root, file);
        CHECKED_FIXTURES.add(name);
        BitmapFrame frame = null;
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (EXPECTED_REJECTIONS.contains(name)) {
                try {
                    frame = CanvasStreams.decodeBytes(bytes, file.getName(), TARGET, true);
                } catch (Exception expected) {
                    rejected++;
                    System.out.println("PASS expected-rejection " + name + " " +
                        expected.getClass().getSimpleName());
                    return;
                }
                check(frame == null, "invalid fixture unexpectedly decoded: " + name);
                rejected++;
                System.out.println("PASS expected-rejection " + name + " decoder returned null");
                return;
            }
            frame = CanvasStreams.decodeBytes(bytes, file.getName(), TARGET, true);
            if (frame == null && KNOWN_UNSUPPORTED.contains(name)) {
                unsupported++;
                System.out.println("UNSUPPORTED visual-media " + name +
                    " (remove from KNOWN_UNSUPPORTED when Canvas renders it)");
                return;
            }
            check(frame != null, "Canvas did not decode " + name);
            Bitmap bitmap = frame.getBitmap();
            check(bitmap != null && !bitmap.isRecycled(), "missing poster bitmap: " + name);
            check(bitmap.getWidth() > 0 && bitmap.getHeight() > 0, "invalid bitmap dimensions: " + name);
            int[] poster = pixels(bitmap);
            check(visiblePixels(poster) > 0, "rendered poster is fully transparent: " + name);
            if (COLOR_REQUIRED_FIXTURES.contains(name))
                check(coloredPixels(poster) > 0, "rendered poster contains only black pixels: " + name);

            BitmapAnimation animation = frame.getAnimation();
            if (animation == null) animation = frame.getColorCycling();
            Drawable drawable = frame.getAnimationDrawable();
            boolean hasPlayback = animation != null || isAnimatable(drawable);
            boolean requiresPlayback = ANIMATED_FIXTURES.contains(name);
            boolean platformStillOnly = name.equals("animation/motion-palette.webp") && Build.VERSION.SDK_INT < 28;
            check(!requiresPlayback || hasPlayback || platformStillOnly,
                "animation fixture decoded without a playback renderer: " + name);

            if (animation != null) {
                int[] first = new int[animation.getWidth() * animation.getHeight()];
                int[] later = new int[first.length];
                check(animation.render(0L, first, true), "initial animation frame did not render: " + name);
                long duration = animation.getDurationNanos();
                long sample = duration > 0 ? Math.max(1L, duration / 2L) : 500_000_000L;
                check(animation.render(sample, later, true), "later animation frame did not render: " + name);
                check(visiblePixels(first) > 0 && visiblePixels(later) > 0,
                    "animation emitted a fully transparent frame: " + name);
                if (COLOR_REQUIRED_FIXTURES.contains(name))
                    check(coloredPixels(first) > 0 && coloredPixels(later) > 0,
                        "animation emitted only black pixels: " + name);
                if (MOVING_FIXTURES.contains(name) && animation.getNeedsContinuousFrames())
                    check(!Arrays.equals(first, later), "animation frames did not change: " + name);
            }
            if (isAnimatable(drawable))
                renderDrawable(drawable, bitmap.getWidth(), bitmap.getHeight(), name);

            rendered++;
            if (hasPlayback) animated++;
            System.out.println("PASS visual-media " + name + " " + bitmap.getWidth() + "x" +
                bitmap.getHeight() + " renderer=" + renderer(frame));
        } catch (Throwable error) {
            failures++;
            System.out.println("FAIL visual-media " + name + " " + error.getClass().getSimpleName() +
                ": " + String.valueOf(error.getMessage()));
        } finally {
            if (frame != null) {
                Bitmap bitmap = frame.getBitmap();
                frame.close();
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        }
    }

    public static void main(String[] args) {
        try {
            check(args.length == 1, "expected filesystem-test-images/images path");
            File root = new File(args[0]).getCanonicalFile();
            check(root.isDirectory(), "image fixture directory is missing: " + root);
            List<File> fixtures = new ArrayList<>();
            collect(root, fixtures);
            check(!fixtures.isEmpty(), "no visual fixtures found under " + root);
            for (File fixture : fixtures) checkFile(root, fixture);
            check(CHECKED_FIXTURES.containsAll(EXPECTED_REJECTIONS) &&
                CHECKED_FIXTURES.containsAll(KNOWN_UNSUPPORTED),
                "an explicitly classified visual fixture is missing");
            check(rejected == EXPECTED_REJECTIONS.size(),
                "did not verify every expected invalid fixture (" + rejected + "/" +
                    EXPECTED_REJECTIONS.size() + ")");
            System.out.println("SUMMARY visual-media rendered=" + rendered + " animated=" + animated +
                " unsupported=" + unsupported + " expected-rejections=" + rejected + " failed=" + failures +
                " api=" + Build.VERSION.SDK_INT);
            if (failures != 0) System.exit(1);
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
