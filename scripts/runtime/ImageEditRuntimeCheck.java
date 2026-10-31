import android.graphics.*;
import com.infinifolder.preview.*;
import io.github.gretchycat.infinistream.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Packaged Canvas versus the previous Android operations. No Activity/widget/font bootstrap. */
public class ImageEditRuntimeCheck {
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static void pass(String name) { System.out.println("PASS image-edit."+name); }
    static Bitmap pixels(int width,int height) {
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)bitmap.setPixel(x,y,0xff000000|((x*43+y*17)&255)<<16|((x*13+y*61)&255)<<8|((x*73+y*7)&255));
        return bitmap;
    }
    static BitmapFrame frame(Bitmap bitmap) {
        return new BitmapFrame(bitmap,false,new Provenance("synthetic://original",null,null,List.of("image.decode"),Map.of("sha256","original")),List.of("retained warning"));
    }
    static void equal(Bitmap a,Bitmap b,String message) {
        check(a.getWidth()==b.getWidth() && a.getHeight()==b.getHeight(),message+" dimensions");
        for(int y=0;y<a.getHeight();y++)for(int x=0;x<a.getWidth();x++)check(a.getPixel(x,y)==b.getPixel(x,y),message+" pixels at "+x+","+y);
    }
    static Bitmap legacy(Bitmap source,ImageOrientationChange change) {
        Matrix matrix=new Matrix();
        switch(change) {
            case ROTATE_CLOCKWISE:matrix.postRotate(90);break;
            case ROTATE_COUNTERCLOCKWISE:matrix.postRotate(-90);break;
            case FLIP_HORIZONTAL:matrix.postScale(-1,1);break;
            case FLIP_VERTICAL:matrix.postScale(1,-1);break;
        }
        return Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),matrix,true);
    }
    static void geometry(BitmapFrame frame,int width,int height) {
        StreamMetadata m=frame.getMetadata();
        check(m.getContent().getKind()==DataKind.IMAGE_FRAME,"frame output kind");
        check(m.getAttributes().get("width").equals(""+width) && m.getAttributes().get("height").equals(""+height),"actual output geometry");
        check(m.getAttributes().get("pixelFormat").equals(frame.getBitmap().getConfig().name()),"actual config");
        check(m.getAttributes().get("colorSpace").equals(frame.getBitmap().getColorSpace().getName()),"actual color space");
        check(m.getLength()==null && m.getCapabilities().isEmpty() && m.getVerification()==Verification.UNKNOWN,"no encoded claims on pixels");
        check(m.getProvenance().getSource().equals("synthetic://original") && m.getWarnings().equals(List.of("retained warning")),"source provenance/warnings");
    }
    static byte[] legacyPng(Bitmap bitmap) {
        ByteArrayOutputStream output=new ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"legacy PNG");return output.toByteArray();
    }
    static void dispose(Bitmap result,Bitmap input) { if(result!=input)result.recycle(); }
    public static void main(String[] args) {
        try {
            Bitmap source=pixels(4,3);BitmapFrame input=frame(source);
            for(ImageOrientationChange change:ImageOrientationChange.values())try(BitmapFrame actual=ImageTransforms.apply(input,new ImageTransform.Orient(change))) {
                Bitmap old=legacy(source,change);equal(actual.getBitmap(),old,change.name());
                boolean turn=change==ImageOrientationChange.ROTATE_CLOCKWISE||change==ImageOrientationChange.ROTATE_COUNTERCLOCKWISE;
                geometry(actual,turn?3:4,turn?4:3);
                for(int y=0;y<actual.getBitmap().getHeight();y++)for(int x=0;x<actual.getBitmap().getWidth();x++) {
                    int sx=change==ImageOrientationChange.ROTATE_CLOCKWISE?y:change==ImageOrientationChange.ROTATE_COUNTERCLOCKWISE?3-y:change==ImageOrientationChange.FLIP_HORIZONTAL?3-x:x;
                    int sy=change==ImageOrientationChange.ROTATE_CLOCKWISE?2-x:change==ImageOrientationChange.ROTATE_COUNTERCLOCKWISE?x:change==ImageOrientationChange.FLIP_VERTICAL?2-y:y;
                    check(actual.getBitmap().getPixel(x,y)==source.getPixel(sx,sy),"independent orientation oracle");
                }
                dispose(old,source);
            }
            check(!source.isRecycled(),"orientation keeps input");pass("four-orientations-pixels-and-metadata");
            for(float[] bounds:new float[][]{{1.9f,0.8f,3.9f,2.7f},{-50,-40,60,70},{20,20,30,30},{3,2,1,0}}) {
                int left=Math.max(0,Math.min((int)bounds[0],3)),top=Math.max(0,Math.min((int)bounds[1],2));
                int right=Math.max(left+1,Math.min((int)bounds[2],4)),bottom=Math.max(top+1,Math.min((int)bounds[3],3));
                Bitmap expected=Bitmap.createBitmap(source,left,top,right-left,bottom-top);
                try(BitmapFrame actual=ImageTransforms.apply(input,new ImageTransform.Crop(new ImageCropBounds(bounds[0],bounds[1],bounds[2],bounds[3])))) {
                    equal(actual.getBitmap(),expected,"legacy crop");geometry(actual,right-left,bottom-top);
                }
                dispose(expected,source);
            }
            pass("fractional-clamped-collapsed-crops");
            Matrix display=new Matrix();display.postRotate(90);display.postScale(3.25f,3.25f);display.postTranslate(100,80);
            Matrix inverse=new Matrix();check(display.invert(inverse),"invert display");RectF mapped=new RectF();inverse.mapRect(mapped,new RectF(92,83,98,89));
            int left=Math.max(0,Math.min((int)mapped.left,3)),top=Math.max(0,Math.min((int)mapped.top,2));
            int right=Math.max(left+1,Math.min((int)mapped.right,4)),bottom=Math.max(top+1,Math.min((int)mapped.bottom,3));
            Bitmap expected=Bitmap.createBitmap(source,left,top,right-left,bottom-top);
            try(BitmapFrame actual=ImageTransforms.apply(input,new ImageTransform.Crop(new ImageCropBounds(mapped.left,mapped.top,mapped.right,mapped.bottom)))) { equal(actual.getBitmap(),expected,"mapped crop"); }
            dispose(expected,source);pass("inverse-display-crop-coordinates");
            for(int[] size:new int[][]{{9,7},{2,1},{4,3}}) {
                Bitmap old=Bitmap.createScaledBitmap(source,size[0],size[1],true);
                try(BitmapFrame actual=ImageTransforms.apply(input,new ImageTransform.Resize(size[0],size[1]))) {
                    equal(actual.getBitmap(),old,"filtered resize");geometry(actual,size[0],size[1]);
                }
                dispose(old,source);
            }
            pass("filtered-resize-and-no-op");
            BitmapFrame owned=ImageTransforms.apply(input,new ImageTransform.Resize(8,6));Bitmap fresh=owned.getBitmap();
            check(fresh!=source && owned.getMetadata().getOwnership()==Ownership.OWNED,"new pixels are owned");owned.close();owned.close();
            check(fresh.isRecycled() && !source.isRecycled(),"close recycles only result");pass("owned-result-close");
            Bitmap oldNoop=Bitmap.createScaledBitmap(source,4,3,true);
            try(BitmapFrame noOp=ImageTransforms.apply(input,new ImageTransform.Resize(4,3))) {
                check((noOp.getBitmap()==source)==(oldNoop==source),"mutable no-op identity matches old backend");
                check(noOp.getMetadata().getOwnership()==(noOp.getBitmap()==source?Ownership.BORROWED:Ownership.OWNED),"no-op ownership follows actual identity");
            }
            dispose(oldNoop,source);
            Bitmap immutable=source.copy(Bitmap.Config.ARGB_8888,false);
            BitmapFrame immutableInput=frame(immutable);
            try(BitmapFrame alias=ImageTransforms.apply(immutableInput,new ImageTransform.Resize(4,3))) { check(alias.getBitmap()==immutable && alias.getMetadata().getOwnership()==Ownership.BORROWED,"immutable resize alias is borrowed"); }
            try(BitmapFrame crop=ImageTransforms.apply(immutableInput,new ImageTransform.Crop(new ImageCropBounds(0,0,4,3)))) {
                check(crop.getBitmap()==immutable && crop.getMetadata().getOwnership()==Ownership.BORROWED,"immutable full-crop alias");
            }
            check(!source.isRecycled() && !immutable.isRecycled(),"aliases remain intact");immutableInput.close();immutable.recycle();pass("alias-close-preserves-borrowed-input");
            BitmapFrame transfer=ImageTransforms.apply(input,new ImageTransform.Resize(7,5));Bitmap moved=transfer.takeBitmap();transfer.close();
            check(!moved.isRecycled(),"host takes pixels before session close");boolean closed=false;
            try { transfer.takeBitmap(); } catch(IOException error) { closed=true; }check(closed,"double transfer rejected");moved.recycle();
            ProcessorOptions flip=new ProcessorOptions(Map.of("operation","FLIP_HORIZONTAL"),FlowMode.PULL,()->false);
            ProcessorSession session=new ImageTransformProcessor().open(Map.of("image",List.of(input)),flip);
            Bitmap sessionBitmap=((BitmapFrame)session.getOutputs().get("image").get(0)).getBitmap();session.close();
            check(sessionBitmap.isRecycled() && !source.isRecycled(),"session owns transformed result only");
            Bitmap ownedInputBitmap=pixels(2,3);BitmapFrame ownedInput=new BitmapFrame(ownedInputBitmap,true,new Provenance(),List.of());
            try(ProcessorSession borrowedSession=new ImageTransformProcessor().open(Map.of("image",List.of(ownedInput)),flip)) {}
            try(EncodedImage ignored=ImageEncoding.png(ownedInput)) {}
            check(!ownedInputBitmap.isRecycled(),"sessions borrow even an owned input frame");ownedInput.close();check(ownedInputBitmap.isRecycled(),"original owner still closes its pixels");
            pass("transfer-and-processor-session-ownership");
            try(BitmapFrame resized=ImageTransforms.apply(input,new ImageTransform.Resize(9,7));EncodedImage encoded=ImageEncoding.png(resized)) {
                StreamMetadata m=encoded.getMetadata();
                check(m.getContent().getMimeType().equals("image/png") && m.getContent().getFormat().equals("png"),"PNG output type");
                check(m.getAttributes().equals(Map.of("width","9","height","7")),"encoded geometry without raw pixel claims");
                check(m.getCapabilities().equals(Set.of(Capability.READ,Capability.POSITIONAL_READ,Capability.KNOWN_LENGTH)),"encoded actual capabilities");
                check(m.getProvenance().getTransformations().equals(List.of("image.decode","canvas.android.transform:RESIZE","canvas.android.encode.png")),"derived transform trace");
                byte[] bytes=encoded.takeBytes();check(bytes.length==m.getLength() && Arrays.equals(bytes,legacyPng(resized.getBitmap())),"byte-identical PNG encoding");
                Bitmap decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.length);equal(decoded,resized.getBitmap(),"PNG roundtrip pixels");decoded.recycle();
            }
            Bitmap alpha=pixels(3,2);alpha.setPixel(1,1,0x80604020);
            try(EncodedImage encoded=ImageEncoding.png(frame(alpha))) { check(Arrays.equals(encoded.takeBytes(),legacyPng(alpha)),"alpha PNG parity"); }
            alpha.recycle();pass("png-pixels-bytes-and-output-metadata");
            StreamMetadata encodedMetadata;
            try(EncodedImage encoded=ImageEncoding.png(input)) {
                byte[] sequential=new byte[8],positional=new byte[8];encoded.readAt(0,positional,0,8);encoded.read(sequential,0,8);check(Arrays.equals(sequential,positional),"encoded independent cursor");
                encodedMetadata=encoded.getMetadata();
            }
            ProcessorRegistry registry=new ProcessorRegistry(List.of(new ProcessorRegistration(new PngImageProcessor(),(values,options)->Map.of("encoded",List.of(encodedMetadata)))));
            PortDescriptor target=new PortDescriptor("result",Set.of(DataKind.BYTES),Set.of("png"),Set.of(Capability.READ),false,Map.of());
            ByteSource pipelineOutput;
            try(ProcessorSession pipeline=registry.openPipeline(input,target,new ProcessorOptions(),null,8,128)) {
                pipelineOutput=(ByteSource)pipeline.getOutputs().get("result").get(0);byte[] signature=new byte[8];check(pipelineOutput.read(signature,0,8)==8 && signature[0]==(byte)137,"PNG pull pipeline");
            }
            closed=false;try { pipelineOutput.read(new byte[1],0,1); } catch(Exception error) { check(error instanceof IOException,"closed output error type");closed=true; }check(closed,"pipeline closes encoded output");
            try(BitmapFrame again=ImageTransforms.apply(input,new ImageTransform.Resize(5,4))) { check(!source.isRecycled(),"pipeline borrows input frame"); }
            pass("png-positional-source-and-registry-pipeline");
            for(int threshold:new int[]{1,3}) {
                AtomicInteger checks=new AtomicInteger();boolean cancelled=false;
                try(BitmapFrame ignored=ImageTransforms.apply(input,new ImageTransform.Resize(8,6),()->checks.incrementAndGet()>=threshold)) {} catch(InterruptedIOException error) { cancelled=true; }
                check(cancelled && !source.isRecycled(),"transform cancellation before/after native work");
            }
            Bitmap cancelledAlias=source.copy(Bitmap.Config.ARGB_8888,false);AtomicInteger aliasChecks=new AtomicInteger();boolean aliasCancelled=false;
            try(BitmapFrame ignored=ImageTransforms.apply(frame(cancelledAlias),new ImageTransform.Resize(4,3),()->aliasChecks.incrementAndGet()>=3)) {} catch(InterruptedIOException error) { aliasCancelled=true; }
            check(aliasCancelled && !cancelledAlias.isRecycled(),"cancelled alias keeps input pixels");cancelledAlias.recycle();
            AtomicInteger checks=new AtomicInteger();boolean cancelled=false;
            try(EncodedImage ignored=ImageEncoding.png(input,()->checks.incrementAndGet()>=3)) {} catch(InterruptedIOException error) { cancelled=true; }
            check(cancelled && !source.isRecycled(),"PNG write cancellation");
            AtomicInteger pulseChecks=new AtomicInteger();cancelled=false;
            try(EncodedImage ignored=ImageEncoding.png(input,()->pulseChecks.incrementAndGet()==3)) {} catch(InterruptedIOException error) { cancelled=true; }
            check(cancelled && !source.isRecycled(),"native writer preserves the original cancellation after its flag clears");
            pass("transform-and-png-cancellation");
            boolean invalid=false;
            try(ProcessorSession ignored=new ImageTransformProcessor().open(Map.of("image",List.of(input)),new ProcessorOptions(Map.of("operation","RESIZE","width","0","height","1"),FlowMode.PULL,()->false))) {} catch(IOException error) { invalid=true; }
            check(invalid,"invalid options");Bitmap closedBitmap=pixels(2,2);BitmapFrame closedInput=new BitmapFrame(closedBitmap,true,new Provenance(),List.of());closedInput.close();invalid=false;
            try(BitmapFrame ignored=ImageTransforms.apply(closedInput,new ImageTransform.Resize(3,3))) {} catch(IOException error) { invalid=true; }
            check(invalid && closedBitmap.isRecycled() && !source.isRecycled(),"closed input rejection");pass("invalid-options-and-closed-input");
            File fixtures=new File(args[0]);Bitmap picture=BitmapFactory.decodeFile(new File(fixtures,"sample-image-edits.png").toString());
            String[] files={"expected-image-cw.png","expected-image-ccw.png","expected-image-flip-horizontal.png","expected-image-flip-vertical.png"};
            for(int index=0;index<files.length;index++) {
                Bitmap reference=BitmapFactory.decodeFile(new File(fixtures,files[index]).toString());
                try(BitmapFrame actual=ImageTransforms.apply(frame(picture),new ImageTransform.Orient(ImageOrientationChange.values()[index]))) { equal(actual.getBitmap(),reference,"independent fixture "+files[index]); }
                reference.recycle();
            }
            Bitmap cropReference=BitmapFactory.decodeFile(new File(fixtures,"expected-image-crop.png").toString());
            try(BitmapFrame actual=ImageTransforms.apply(frame(picture),new ImageTransform.Crop(new ImageCropBounds(80,50,240,150)))) { equal(actual.getBitmap(),cropReference,"independent crop fixture"); }
            try(ZipFile zip=new ZipFile(new File(fixtures,"image-edit-checks.zip"));InputStream stream=zip.getInputStream(zip.getEntry("images/sample-image-edits.png"))) {
                Bitmap archived=BitmapFactory.decodeStream(stream);equal(archived,picture,"ZIP fixture");archived.recycle();
            }
            cropReference.recycle();picture.recycle();input.close();source.recycle();pass("independent-reference-pngs-and-zip");
            System.exit(0);
        } catch(Throwable error) { error.printStackTrace();System.exit(1); }
    }
}
