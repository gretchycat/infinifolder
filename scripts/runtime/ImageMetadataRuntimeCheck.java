import android.media.ExifInterface;
import com.infinifolder.preview.*;
import io.github.gretchycat.infinistream.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Real packaged Canvas and Android EXIF. No Activity, fonts, widgets or WebView bootstrap. */
public class ImageMetadataRuntimeCheck {
    static File fixtures;
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static void pass(String name) { System.out.println("PASS image-metadata."+name); }
    static ImageExifFields legacy(File file) throws Exception {
        try(InputStream stream=new FileInputStream(file)) {
            ExifInterface exif=new ExifInterface(stream);float[] coordinates=new float[2];
            return new ImageExifFields(exif.getAttribute(ExifInterface.TAG_MAKE),exif.getAttribute(ExifInterface.TAG_MODEL),
                exif.getAttribute(ExifInterface.TAG_DATETIME),exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                exif.getAttribute(ExifInterface.TAG_F_NUMBER),exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS),
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),exif.getLatLong(coordinates)?new ImageLocation(coordinates[0],coordinates[1]):null);
        }
    }
    static class TrackingSource implements ByteSource {
        ByteSource source;int reads=0;boolean closed=false,cancelled=false,shortReads=false,fail=false;
        TrackingSource(File file) { source=new FileSourceResource(file,new ContentType()).open(); }
        public StreamMetadata getMetadata() { return source.getMetadata(); }
        public int read(byte[] b,int offset,int length) {
            reads++;
            if(fail) { try { throw new IOException("provider failed"); } catch(IOException error) { return ImageMetadataRuntimeCheck.<RuntimeException,Integer>raise(error); } }
            return source.read(b,offset,shortReads?Math.min(length,1):length);
        }
        public void close() { closed=true;try { source.close(); } catch(IOException error) { throw new UncheckedIOException(error); } }
    }
    @SuppressWarnings("unchecked") static <E extends Throwable,T> T raise(Throwable error) throws E { throw (E)error; }
    static SourceResource resource(TrackingSource source) { return new SourceResource() {
        public StreamMetadata getMetadata() { return source.getMetadata(); }
        public ByteSource open() { return source; }
    }; }
    static void same(File file) throws Exception {
        ImageExifFields expected=legacy(file);
        try(ImageExifMetadata result=ImageMetadata.read(new FileSourceResource(file,new ContentType()))) {
            check(result.getFields().equals(expected),"legacy field parity: "+file.getName()+" expected="+expected+" actual="+result.getFields());
        }
    }
    static int fds() { String[] entries=new File("/proc/self/fd").list();check(entries!=null,"descriptor inventory");return entries.length; }
    public static void main(String[] args) {
        try {
            fixtures=new File(args[0]);File little=new File(fixtures,"sample-exif-le.jpg"),big=new File(fixtures,"sample-exif-be.jpg");
            same(little);
            try(ImageExifMetadata value=ImageMetadata.read(new FileSourceResource(little,new ContentType()))) {
                ImageExifFields f=value.getFields();
                check("InfiniStream Synthetic".equals(f.getMake()) && "Metadata Fixture".equals(f.getModel()),"synthetic camera");
                check("2026:10:31 12:34:56".equals(f.getDateTime()) && "200".equals(f.getIso()),"date/ISO");
                check(f.getLocation()!=null && f.getLocation().getLatitude()==41.5f && f.getLocation().getLongitude()==-87.25f,"GPS");
                check(f.getExposureTime()!=null && f.getFNumber()!=null && f.getFocalLength()!=null,"all camera fields");
                StreamMetadata m=value.getMetadata();
                check(m.getContent().getKind()==DataKind.STRUCTURED && "image-exif".equals(m.getContent().getFormat()),"structured output");
                check(m.getLength()==null && m.getCapabilities().isEmpty() && m.getVerification()==Verification.UNKNOWN,"no inherited encoded capabilities/verification");
                check(m.getProvenance().getSource().equals(little.toURI().toString()),"original provenance");
                check(m.getAttributes().get("latitude").equals("41.5"),"actual field attributes");
                System.out.println("FIELDS "+f);
            }
            pass("little-endian-legacy-parity-metadata");
            same(big);pass("big-endian-legacy-parity");
            same(new File(fixtures,"icon.jpg"));same(new File(fixtures,"icon.png"));pass("without-exif-legacy-parity");
            TrackingSource borrowed=new TrackingSource(little);
            ImageExifMetadata result=ImageMetadata.read((ByteSource)borrowed);
            result.close();check(!borrowed.closed,"borrowed source remains open");
            byte[] a=new byte[16],b=new byte[16];
            check(((PositionalReadable)borrowed.source).readAt(0,a,0,a.length)==a.length,"position independent read");
            try(ByteSource other=new FileSourceResource(little,new ContentType()).open()) { other.read(b,0,b.length); }
            check(Arrays.equals(a,b),"separate cursor bytes");borrowed.close();
            check(result.getFields().getMake().equals("InfiniStream Synthetic"),"detached description");pass("borrowed-lifetime-positional-cursors");
            TrackingSource shortSource=new TrackingSource(little);shortSource.shortReads=true;
            try(ImageExifMetadata value=ImageMetadata.read(resource(shortSource))) { check(value.getFields().equals(legacy(little)),"one-byte short reads"); }
            check(shortSource.closed,"resource-owned close on success");pass("short-reads-owned-close");
            byte[] encoded=Files.readAllBytes(little.toPath());
            InputStreamSource unknown=new InputStreamSource(new ByteArrayInputStream(encoded),new StreamMetadata(),true,()->false);
            try(ImageExifMetadata value=ImageMetadata.read((ByteSource)unknown)) { check(value.getFields().equals(legacy(little)),"unknown-length sequential source"); }
            unknown.close();pass("unknown-length-sequential-source");
            boolean cancelled=false;final int[] opens={0};
            SourceResource never=new SourceResource() {
                public StreamMetadata getMetadata() { return new StreamMetadata(); }
                public ByteSource open() { opens[0]++;throw new AssertionError("cancelled resource opened"); }
            };
            try(ImageExifMetadata ignored=ImageMetadata.read(never,()->true)) {} catch(InterruptedIOException expected) { cancelled=true; }
            check(cancelled && opens[0]==0,"cancel before source open");
            TrackingSource during=new TrackingSource(little);cancelled=false;
            try(ImageExifMetadata ignored=ImageMetadata.read(resource(during),()->during.reads>0)) {} catch(InterruptedIOException expected) { cancelled=true; }
            check(cancelled && during.closed && during.reads==1,"cancel during platform read and close");pass("cancellation-before-and-during-read");
            TrackingSource failing=new TrackingSource(little);failing.fail=true;boolean failed=false;
            try(ImageExifMetadata ignored=ImageMetadata.read(resource(failing))) {} catch(IOException expected) { failed=true;check(expected.getMessage().equals("provider failed"),"original provider error"); }
            check(failed && failing.closed,"source error/owned close");pass("provider-error-propagation");
            File invalid=new File(fixtures,"invalid-metadata.jpg");boolean oldFailed=false,newFailed=false;
            ImageExifFields old=null,now=null;
            try { old=legacy(invalid); } catch(IOException expected) { oldFailed=true; }
            TrackingSource bad=new TrackingSource(invalid);
            try(ImageExifMetadata value=ImageMetadata.read(resource(bad))) { now=value.getFields(); } catch(IOException expected) { newFailed=true; }
            check(oldFailed==newFailed && Objects.equals(old,now) && bad.closed,"malformed input legacy parity/cleanup");
            int before=fds();
            for(int i=0;i<100;i++)try(ImageExifMetadata ignored=ImageMetadata.read(new FileSourceResource(little,new ContentType()))) {}
            check(fds()<=before,"no descriptor leak across 100 source opens");pass("invalid-input-and-repeated-cleanup");
            try(ZipFile zip=new ZipFile(new File(fixtures,"image-metadata-checks.zip"));InputStream entry=zip.getInputStream(zip.getEntry("images/sample-exif-be.jpg"))) {
                InputStreamSource source=new InputStreamSource(entry,new StreamMetadata(),false,()->false);
                try(ImageExifMetadata value=ImageMetadata.read((ByteSource)source)) { check(value.getFields().equals(legacy(big)),"archive sequential input"); }
                source.close();
            }
            pass("zip-entry-common-source");
            System.exit(0);
        } catch(Throwable error) { error.printStackTrace();System.exit(1); }
    }
}
