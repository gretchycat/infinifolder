import android.graphics.Bitmap;
import io.github.gretchycat.infiniscribe.android.*;
import io.github.gretchycat.infinistream.*;
import java.io.File;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Real Android PDF backend; no Activity/UI bootstrap or private application data. */
public class PdfRuntimeCheck {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static File valid;
    static void pass(String name) { System.out.println("PASS pdf." + name); }

    public static void main(String[] args) {
        try {
            valid = new File(args[0], "sample-pages.pdf");
            File invalid = new File(args[0], "invalid-document.pdf");
            StreamMetadata input = new StreamMetadata(
                new ContentType(), "test.pdf", 999L, Finiteness.FINITE,
                Collections.emptySet(), Ownership.OWNED, Concurrency.SERIAL, Collections.emptyMap(),
                new Provenance("test://original.pdf", null, null, Collections.emptyList(), Collections.singletonMap("sha256", "source-only")),
                Verification.VERIFIED, Collections.singletonList("retained warning"), 0L);
            try (PdfDocument doc = PdfDocuments.open(valid, input)) {
                check(doc.getPageCount() == 2, "page count");
                check(doc.getMetadata().getContent().getKind() == DataKind.STRUCTURED, "document kind");
                check(doc.getMetadata().getLength() == null, "encoded size is not output length");
                check(doc.getMetadata().getVerification() == Verification.UNKNOWN, "output verification");
                check(doc.getMetadata().getWarnings().equals(input.getWarnings()), "warnings");
                try (PdfPageFrame frame = doc.renderPage(0, 1080)) {
                    StreamMetadata meta = frame.getMetadata();
                    check(meta.getContent().getKind() == DataKind.IMAGE_FRAME, "frame kind");
                    check(meta.getAttributes().get("width").equals("1224"), "actual width");
                    check(meta.getAttributes().get("pageCount").equals("2"), "frame page count");
                    check(meta.getProvenance().getSource().equals("test://original.pdf"), "original identity");
                    check(meta.getProvenance().getTransformations().equals(Arrays.asList("pdf.open", "pdf.render")), "transforms");
                    check(meta.getLength() == null && meta.getVerification() == Verification.UNKNOWN, "frame size/checksum");
                }
            }
            pass("metadata");
            try (PdfDocument doc = PdfDocuments.open(valid)) {
                for (int index : new int[]{0, 1, 0}) {
                    try (PdfPageFrame frame = doc.renderPage(index, 1080)) {
                        Bitmap b = frame.getBitmap();
                        check(b.getWidth() == (index == 0 ? 1224 : 1584), "page width");
                        check(b.getHeight() == (index == 0 ? 1584 : 1224), "page height");
                        check(b.getPixel(120, b.getHeight()-400) == 0xff1a66cc, "blue rectangle");
                        check(b.getPixel(560, b.getHeight()-400) == 0xffcc331a, "red rectangle");
                        check(b.getPixel(4, 4) == 0xffffffff, "white background");
                        int ink = 0;
                        for (int y=55; y<125; y++) for (int x=90; x<1100; x++) if (b.getPixel(x,y) != 0xffffffff) ink++;
                        check(ink > 100, "heading rendered");
                    }
                }
            }
            pass("pages-pixels-navigation");
            try (PdfDocument doc = PdfDocuments.open(valid)) {
                for (int index=0; index<2; index++) try(PdfPageFrame frame=doc.renderPage(index,1080,256)) {
                    Bitmap b=frame.getBitmap();
                    check(b.getWidth()==(index==0?198:256) && b.getHeight()==(index==0?256:198), "bounded proportional raster");
                }
            }
            pass("raster-budget");
            try (PdfDocument doc = PdfDocuments.open(valid)) {
                PdfPageFrame owned=doc.renderPage(0,1080,256);
                Bitmap b=owned.getBitmap(); owned.close(); owned.close();
                check(b.isRecycled(), "owned frame releases bitmap exactly once");
                PdfPageFrame transfer=doc.renderPage(1,1080,256);
                Bitmap moved=transfer.takeBitmap(); transfer.close();
                check(!moved.isRecycled(), "presentation transfer survives frame close");
                moved.recycle();
            }
            pass("frame-lifetime");
            try (PdfDocument doc = PdfDocuments.open(valid)) {
                for (int page : new int[]{-1,2}) {
                    boolean threw=false;
                    try(PdfPageFrame ignored=doc.renderPage(page,1080,256)) {} catch(IllegalArgumentException expected) { threw=true; }
                    check(threw,"invalid page rejected");
                }
                boolean threw=false;
                try(PdfPageFrame ignored=doc.renderPage(0,0,256)) {} catch(IllegalArgumentException expected) { threw=true; }
                check(threw,"invalid viewport rejected");
                try(PdfPageFrame ignored=doc.renderPage(1,1080,256)) {}
            }
            pass("failed-request-recovery");
            PdfDocument closed=PdfDocuments.open(valid);
            Thread.currentThread().interrupt();
            boolean cancelled=false;
            try(PdfPageFrame ignored=closed.renderPage(0,1080,256)) {} catch(InterruptedIOException expected) { cancelled=true; }
            finally { Thread.interrupted(); }
            check(cancelled,"cancelled request rejected");
            try(PdfPageFrame ignored=closed.renderPage(0,1080,256)) {}
            closed.close(); closed.close();
            boolean rejected=false;
            try(PdfPageFrame ignored=closed.renderPage(0,1080,256)) {} catch(IllegalStateException expected) { rejected=true; }
            check(rejected,"closed document rejects render");
            pass("cancellation-close");
            int before=descriptors(invalid);
            for(int i=0;i<30;i++) {
                boolean failed=false;
                try(PdfDocument ignored=PdfDocuments.open(invalid)) {} catch(Exception expected) { failed=true; }
                check(failed,"corrupt PDF must fail explicitly");
            }
            check(descriptors(invalid)==before,"failed construction leaked file descriptor");
            pass("corrupt-input-cleanup");
            PdfDocument concurrent=PdfDocuments.open(valid);
            ExecutorService pool=Executors.newFixedThreadPool(3);
            try {
                CountDownLatch ready=new CountDownLatch(3), start=new CountDownLatch(1);
                List<Future<?>> tasks=new ArrayList<>();
                for(int t=0;t<3;t++) tasks.add(pool.submit(()->{
                    ready.countDown(); start.await();
                    for(int i=0;i<8;i++) try(PdfPageFrame f=concurrent.renderPage(i%2,1080,256)) { check(f.getBitmap().getWidth()>0,"concurrent frame"); }
                    return null;
                }));
                check(ready.await(5,TimeUnit.SECONDS),"workers ready"); start.countDown();
                for(Future<?> task:tasks) task.get(15,TimeUnit.SECONDS);
                Future<?> first=pool.submit(()->concurrent.close());
                Future<?> second=pool.submit(()->concurrent.close());
                first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
            } finally { concurrent.close(); pool.shutdownNow(); }
            check(descriptors(valid)==0,"document descriptor cleanup");
            pass("serialized-render-close");
            System.exit(0);
        } catch(Throwable error) { error.printStackTrace(); System.exit(1); }
    }
    static int descriptors(File target) throws Exception {
        int count=0;
        for(File fd:Objects.requireNonNull(new File("/proc/self/fd").listFiles())) {
            try { if(Files.readSymbolicLink(fd.toPath()).toString().equals(target.getCanonicalPath())) count++; }
            catch(java.io.IOException ignored) { /* transient descriptor from enumeration */ }
        }
        return count;
    }
}
