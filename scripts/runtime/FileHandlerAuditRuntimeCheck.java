import android.graphics.Bitmap;
import com.infinifolder.preview.*;
import com.infinifolder.audio.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;

/** Observes current behavior, including defects. This is an audit, not a passing regression gate. */
public class FileHandlerAuditRuntimeCheck {
    static Bitmap image(String ext, byte[] bytes) {
        switch (ext) {
            case "xcf": case "psd": return LayeredImageDecoder.INSTANCE.decode(bytes, 0, ext);
            case "exr": case "hdr": case "fits": case "dds": return HdrAndScientificImageDecoder.INSTANCE.decode(bytes, 0, ext);
            case "ans": case "eps": case "wmf": return MetafileAndVectorImageDecoder.INSTANCE.decode(bytes, 0, ext);
            case "qoi": return SimpleLosslessImageDecoder.INSTANCE.decode(bytes, 0);
            case "ppm": return NetpbmDecoder.INSTANCE.decode(bytes, 0);
            case "pcx": return PcxDecoder.INSTANCE.decode(bytes, 0);
            case "tiff": return TiffAndRawImageDecoder.INSTANCE.decode(bytes, 0);
            case "tga": return TgaDecoder.INSTANCE.decode(new ByteArrayInputStream(bytes), 0);
            default: throw new IllegalArgumentException(ext);
        }
    }
    public static void main(String[] args) {
        try {
            JSONArray results = new JSONArray();
            for (String line : Files.readAllLines(Path.of(args[0]))) {
                String[] fields = line.split("\t");
                String id = fields[0], family = fields[1], ext = fields[2];
                File file = new File(fields[3]);
                JSONObject row = new JSONObject().put("id", id).put("family", family).put("extension", ext)
                    .put("probe", fields[4]);
                byte[] bytes = Files.readAllBytes(file.toPath());
                try {
                    if (family.equals("audio")) {
                        AudioDecodeResult decoded = AudioProcessing.INSTANCE.decode(new AudioDecodeRequest(bytes, ext, file), null);
                        row.put("accepted", decoded != null);
                        if (decoded != null) {
                            short[] samples = decoded.getPcm().getSamples();
                            row.put("decoder", decoded.getDecoder()).put("samples", samples.length);
                            MessageDigest digest = MessageDigest.getInstance("SHA-256");
                            for (short sample : samples) { digest.update((byte)sample);digest.update((byte)(sample >> 8)); }
                            StringBuilder hex = new StringBuilder();for (byte value : digest.digest()) hex.append(String.format("%02x",value & 255));
                            row.put("pcmSha256", hex.toString());
                        }
                    } else {
                        Bitmap bitmap = image(ext, bytes);
                        row.put("accepted", bitmap != null);
                        if (bitmap != null) try {
                            int width = bitmap.getWidth(), height = bitmap.getHeight(), first = bitmap.getPixel(0,0);
                            int[] pixels = new int[width * height];bitmap.getPixels(pixels,0,width,0,0,width,height);
                            int different = 0;for (int pixel : pixels) if (pixel != first) different++;
                            row.put("width", width).put("height", height).put("differentPixels", different)
                                .put("firstPixel", String.format("%08x",first));
                            if (id.equals("ansi-stipple")) {
                                boolean matches = true;
                                for (int y=0;y<16;y++) for (int x=0;x<8;x++) {
                                    boolean foreground=x>=1&&x<=6&&y>=2&&y<=13&&(('A' >> ((x+y)&7))&1)!=0;
                                    if (bitmap.getPixel(x,y)!=(foreground?0xffaaaaaa:0xff000000)) matches=false;
                                }
                                row.put("matchesCodeBitStipple", matches);
                                int[] referenceA = {0x0c,0x1e,0x33,0x33,0x3f,0x33,0x33,0};
                                boolean reference = true;
                                for (int y=0;y<16;y++) for (int x=0;x<8;x++) {
                                    boolean fg = ((referenceA[y/2] >> x) & 1) != 0;
                                    if (bitmap.getPixel(x,y)!=(fg?0xffaaaaaa:0xff000000)) reference=false;
                                }
                                row.put("matchesReferenceGlyph", reference);
                            }
                        } finally { bitmap.recycle(); }
                    }
                } catch (Exception error) { row.put("accepted",false).put("error",error.toString()); }
                results.put(row);
                System.out.println("OBSERVED " + row);
            }
            Files.writeString(Path.of(args[1]), results.toString(2));
            System.out.println("AUDIT_COMPLETE cases=" + results.length());
            System.exit(0);
        } catch (Throwable error) { error.printStackTrace();System.exit(1); }
    }
}
