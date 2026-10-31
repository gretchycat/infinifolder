import android.content.*;
import android.os.Looper;
import io.github.gretchycat.infinivideo.LibVlcMedia;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.IMedia;
import java.io.*;

/** Headless native demux/decode checks. No Activity, widget or Android font bootstrap. */
public class LibVlcRuntimeCheck {
    static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
    static void pass(String name) { System.out.println("PASS libvlc."+name); }
    static class TestContext extends ContextWrapper {
        final File root;
        TestContext(File root) { super(null);this.root=root; }
        public Context getApplicationContext() { return this; }
        public File getDir(String name,int mode) { File dir=new File(root,name);dir.mkdirs();return dir; }
        public String getPackageName() { return "com.infinifolder.runtime"; }
    }
    static void parsed(LibVLC vlc,File file,long expectedDuration,boolean subtitles) {
        Media media=LibVlcMedia.INSTANCE.open(vlc,file);
        try {
            check(media.parse(0),"parse "+file);
            check(Math.abs(media.getDuration()-expectedDuration)<200,"duration "+media.getDuration());
            int video=0,audio=0,text=0;
            for(int i=0;i<media.getTrackCount();i++) {
                IMedia.Track track=media.getTrack(i);
                if(track instanceof IMedia.VideoTrack) {
                    IMedia.VideoTrack v=(IMedia.VideoTrack)track;
                    check(v.width==320&&v.height==180,"actual video dimensions");video++;
                }
                if(track.type==IMedia.Track.Type.Audio)audio++;
                if(track.type==IMedia.Track.Type.Text)text++;
            }
            check(video==1,"one video track");
            if(expectedDuration==12000)check(audio==1,"one audio track");
            if(subtitles)check(text==1,"embedded subtitle track");
        } finally { media.release(); }
    }
    static void decodeSeek(LibVLC vlc,File file) throws Exception {
        Media media=LibVlcMedia.INSTANCE.open(vlc,file);
        MediaPlayer player=new MediaPlayer(vlc);
        try {
            media.setHWDecoderEnabled(false,false);
            media.addOption(":vout=dummy");media.addOption(":aout=dummy");
            check(player.setAudioOutput("dummy"),"dummy audio output");
            player.setMedia(media);player.play();
            long deadline=System.currentTimeMillis()+8000;
            while((!player.isPlaying()||!player.isSeekable()||player.getTime()<100)&&System.currentTimeMillis()<deadline)Thread.sleep(20);
            check(player.isPlaying()&&player.isSeekable(),"playing seekable native video");
            for(long position:new long[]{300000,10000,350000}) {
                int before=media.getStats()==null?0:media.getStats().decodedVideo;
                long started=System.nanoTime();player.setTime(position,false);
                deadline=System.currentTimeMillis()+4000;
                // setTime can update the getter optimistically. Also require decoder progress
                // and let the position settle before accepting the seek.
                boolean advanced=false;
                while(System.currentTimeMillis()<deadline) {
                    Thread.sleep(40);
                    advanced=media.getStats()!=null&&media.getStats().decodedVideo>before;
                    if(advanced&&Math.abs(player.getTime()-position)<=1500&&(System.nanoTime()-started)>150000000)break;
                }
                check(advanced&&Math.abs(player.getTime()-position)<=1500,"bounded forward/back seek with decoder progress "+position+" actual="+player.getTime());
                System.out.println("SEEK targetMs="+position+" elapsedMs="+(System.nanoTime()-started)/1000000);
            }
            deadline=System.currentTimeMillis()+3000;
            while((media.getStats()==null||media.getStats().decodedVideo==0)&&System.currentTimeMillis()<deadline)Thread.sleep(20);
            check(media.getStats()!=null&&media.getStats().decodedVideo>0,"actual native video frames decoded");
            player.pause();deadline=System.currentTimeMillis()+2000;
            while(player.isPlaying()&&System.currentTimeMillis()<deadline)Thread.sleep(20);
            check(!player.isPlaying(),"pause completes");
            player.setRate(.5f);check(Math.abs(player.getRate()-.5f)<.01,"half speed");
            player.setRate(2f);check(Math.abs(player.getRate()-2f)<.01,"double speed");
            player.play();deadline=System.currentTimeMillis()+2000;
            while(!player.isPlaying()&&System.currentTimeMillis()<deadline)Thread.sleep(20);
            check(player.isPlaying(),"resume completes");
        } finally { player.stop();player.release();media.release(); }
        pass("native-decode-seek-pause-speed");
    }
    public static void main(String[] args) {
        try {
            PrintStream evidence = new PrintStream(new FileOutputStream(args[3]), true, "UTF-8");
            System.setOut(evidence);System.setErr(evidence);
            Looper.prepareMainLooper();
            System.out.println("Native path="+System.getProperty("java.library.path"));
            boolean sound=new File(args[2],"libinfiniopenmpt.so").isFile();
            for(String name:new String[]{"libc++_shared.so","libinfiniopenmpt.so","libvlc.so","libvlcjni.so"}) {
                if(name.equals("libinfiniopenmpt.so")&&!sound)continue;
                System.load(new File(args[2],name).getAbsolutePath());
                System.out.println("Loaded "+name);
            }
            LibVLC vlc=new LibVLC(new TestContext(new File(args[1])), new java.util.ArrayList<>(java.util.List.of(
                "--no-video-title-show", "--vout=dummy", "--aout=dummy", "--no-spu", "--text-renderer=dummy")));
            try {
                System.out.println("libVLC version="+LibVLC.version()+" changeset="+LibVLC.changeset());
                pass(sound?"packaged-vlc-and-sound-native-linkage":"packaged-vlc-native-linkage");
                File root=new File(args[0]);
                parsed(vlc,new File(root,"01-avc-aac.mp4"),12000,false);pass("mp4-h264-aac");
                parsed(vlc,new File(root,"02-vp8-vorbis.webm"),12000,false);pass("webm-vp8-vorbis");
                parsed(vlc,new File(root,"03-avc-aac-subtitles.mkv"),12000,true);pass("mkv-embedded-subtitles");
                parsed(vlc,new File(root,"90-six-minute-seek.mp4"),360000,false);pass("long-indexed-mp4");
                decodeSeek(vlc,new File(root,"90-six-minute-seek.mp4"));
                Media bad=LibVlcMedia.INSTANCE.open(vlc,new File(root,"invalid-video.mp4"));
                try { bad.parse(0);check(bad.getTrackCount()==0,"invalid input no tracks"); } finally { bad.release(); }
                parsed(vlc,new File(root,"01-avc-aac.mp4"),12000,false);pass("invalid-input-and-reopen-cleanup");
            } finally { vlc.release(); }
            System.exit(0);
        } catch(Throwable error) { error.printStackTrace();System.exit(1); }
    }
}
