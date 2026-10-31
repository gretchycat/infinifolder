import android.graphics.*;
import com.infinifolder.viewer.*;

/** Real Android matrices and software pixels; deliberately avoids widgets/font bootstrap. */
public class ImageViewportRuntimeCheck {
    static final ImageViewportGeometry geometry = ImageViewportGeometry.INSTANCE;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void near(float expected, float actual) { check(Math.abs(expected-actual)<.02f, expected+" != "+actual); }
    static ViewportRect rect(RectF r) { return new ViewportRect(r.left,r.top,r.right,r.bottom); }
    static void pass(String name) { System.out.println("PASS image-viewport."+name); }
    static Matrix placement(int w,int h,int vw,int vh,float angle,boolean fit) {
        Matrix m=new Matrix();m.postRotate(angle);
        RectF rotated=new RectF(0,0,w,h);m.mapRect(rotated);
        ImagePlacement p=geometry.placement(rect(rotated),new ViewportRect(0,0,vw,vh),fit);
        check(p!=null,"valid placement");m.postScale(p.getScale(),p.getScale());m.postTranslate(p.getTranslateX(),p.getTranslateY());return m;
    }
    static RectF mapped(Matrix m,int w,int h) { RectF r=new RectF(0,0,w,h);m.mapRect(r);return r; }
    static void oldBug() {
        Matrix before=new Matrix();before.postRotate(90,160,100);
        Matrix rotation=new Matrix();rotation.postRotate(90);RectF r=mapped(rotation,320,200);
        float scale=Math.min(1344/r.width(),2774/r.height());before.postScale(scale,scale);
        before.postTranslate((1344-r.width()*scale)/2-r.left*scale,(2774-r.height()*scale)/2-r.top*scale);
        check(!RectF.intersects(mapped(before,320,200),new RectF(0,0,1344,2774)),"original offscreen bug reproduced");
        pass("original-bug-reproduced");
    }
    static void rotations() {
        for(int[] size:new int[][]{{320,200},{200,320},{1,200},{320,1}})
        for(float angle:new float[]{0,90,180,270,-90})for(boolean fit:new boolean[]{true,false}) {
            RectF r=mapped(placement(size[0],size[1],1344,2774,angle,fit),size[0],size[1]);
            near(672,r.centerX());near(1387,r.centerY());
            check(RectF.intersects(r,new RectF(0,0,1344,2774)),"rotation visible");
            if(fit)check(r.width()<=1344.02f&&r.height()<=2774.02f,"fit contained");
            else check(r.width()>=1343.98f&&r.height()>=2773.98f,"fill covers");
        }
        pass("rotation-fit-fill");
    }
    static void pixels() {
        Bitmap image=Bitmap.createBitmap(320,200,Bitmap.Config.ARGB_8888);image.eraseColor(Color.MAGENTA);
        Bitmap target=Bitmap.createBitmap(134,277,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(target);
        for(float angle:new float[]{0,90,180,270,-90}) {
            target.eraseColor(Color.BLACK);canvas.drawBitmap(image,placement(320,200,134,277,angle,true),null);
            check(target.getPixel(67,138)==Color.MAGENTA,"image immediately drawn at center after reset "+angle);
        }
        image.recycle();target.recycle();pass("software-pixels-visible");
    }
    static void panCrop() {
        for(float angle:new float[]{0,90,180,270,-90}) {
            Matrix m=placement(320,200,1344,2774,angle,true);m.postScale(2.5f,2.5f,672,1387);m.postTranslate(4000,-6000);
            kotlin.Pair<Float,Float> correction=geometry.panCorrection(rect(mapped(m,320,200)),new ViewportRect(0,0,1344,2774));
            m.postTranslate(correction.getFirst(),correction.getSecond());RectF r=mapped(m,320,200);
            check(RectF.intersects(r,new RectF(0,0,1344,2774)),"pan restores real rotated bounds");
            ViewportRect safe=new ViewportRect(0,180,1344,2300);
            ViewportRect crop=geometry.initialCrop(rect(r),safe);check(crop!=null&&crop.getValid(),"visible crop");
            check(crop.getLeft()>=20&&crop.getRight()<=1324&&crop.getTop()>=200&&crop.getBottom()<=2280,"toolbar-safe handles");
            for(int handle=0;handle<=4;handle++) {
                ViewportRect dragged=geometry.dragCrop(crop,handle,-5000,10000,geometry.visibleBounds(rect(r),safe));
                check(dragged.getLeft()>=20&&dragged.getRight()<=1324&&dragged.getTop()>=200&&dragged.getBottom()<=2280,"clamped drag");
            }
        }
        pass("rotated-pan-and-visible-crop");
    }
    static void inverseCrop() {
        for(float angle:new float[]{0,90,180,270,-90}) {
            Matrix m=placement(640,400,1344,2774,angle,true);
            RectF drawableCrop=new RectF(160,100,480,300),display=new RectF();m.mapRect(display,drawableCrop);
            Matrix inverse=new Matrix();check(m.invert(inverse),"invertible");inverse.mapRect(display);
            ViewportRect source=geometry.sourceCrop(rect(display),640,400,320,200);
            near(80,source.getLeft());near(50,source.getTop());near(240,source.getRight());near(150,source.getBottom());
        }
        pass("inverse-crop-density");
    }
    public static void main(String[] args) {
        try { oldBug();rotations();pixels();panCrop();inverseCrop();System.exit(0); }
        catch(Throwable error) { error.printStackTrace();System.exit(1); }
    }
}
