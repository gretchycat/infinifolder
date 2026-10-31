import com.infinifolder.audio.*;
import com.infinifolder.audio.engine.PcmMediaDataSource;
import io.github.gretchycat.infinistream.*;
import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import android.os.*;

public class AudioPullRuntimeCheck {
  static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  static <T> T on(Handler handler, Callable<T> action) throws Exception {
    FutureTask<T> task=new FutureTask<>(action);handler.post(task);return task.get(10,TimeUnit.SECONDS);
  }
  public static void main(String[] args) throws Exception {
    try { run(args); System.exit(0); } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
  }
  public static void run(String[] args) throws Exception {
    File root = new File(args[0]), fixtures = new File(root,"libraries/libinfinisound/audio-core/src/test/resources/audio");
    File out = new File(root,"build/infinistream-migration");
    for (String name : new String[]{"chord.mp3","chord.aac","chord.m4a","chord.wav","chord.flac","high-resolution.flac","full-resolution.flac"}) {
      if (args.length>1 && !args[1].equals(name)) continue;
      File cache = new File(out,"pull-"+name+".wav");
      try (AudioWaveSource source = AudioProcessing.INSTANCE.openWaveSource(new File(fixtures,name),cache,null,()->false)) {
        PcmFormat format = source.getPreparation().getFormat();
        check(source.getMetadata().getContent().getMimeType().equals("audio/wav"), "PCM MIME");
        check(source.getProgressive() != name.equals("chord.aac"), "Unexpected fallback for "+name);
        long initial = cache.length();
        MediaExtractor extractor = new MediaExtractor();
        try {
          extractor.setDataSource(new PcmMediaDataSource(source.borrowedResource().open()));
          check(extractor.getTrackCount()==1,"one PCM track");
          MediaFormat mf = extractor.getTrackFormat(0);
          check("audio/raw".equals(mf.getString(MediaFormat.KEY_MIME)), "PCM-only output");
          check(format.getSampleRate()==mf.getInteger(MediaFormat.KEY_SAMPLE_RATE), "rate");
          check(format.getChannels()==mf.getInteger(MediaFormat.KEY_CHANNEL_COUNT), "channels");
          extractor.selectTrack(0);
          check(extractor.readSampleData(ByteBuffer.allocateDirect(65536),0)>0,"first PCM sample");
          extractor.seekTo(Math.min(700000L,format.getDurationMs()*500L),MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
          check(extractor.readSampleData(ByteBuffer.allocateDirect(65536),0)>0,"middle PCM sample");
          extractor.seekTo(0,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
          check(extractor.readSampleData(ByteBuffer.allocateDirect(65536),0)>0,"rewound PCM sample");
        } finally { extractor.release(); }
        // Read the entire source and ensure the lazy file has the declared exact output size.
        byte[] bytes = new byte[65536]; long pos=0; int count;
        while ((count=source.readAt(pos,bytes,0,bytes.length))>0) pos+=count;
        check(pos==source.getMetadata().getLength(),"exact PCM byte length");
        check(source.getProgressive() != name.equals("chord.aac"),"Seeking silently fell back for "+name);
        System.out.println("PASS PCM extractor "+name+" decoder="+source.getPreparation().getDecoder()+" initial="+initial+" total="+pos);
      }
    }
    if (args.length>1 && !args[1].equals("long")) return;
    File cache = new File(out,"pull-long.wav");
    long started=System.nanoTime();
    try (AudioWaveSource source = AudioProcessing.INSTANCE.openPlaybackSource(new File(out,"audio-pull-long.mp3"),cache,null,()->false)) {
      long openedMs=(System.nanoTime()-started)/1000000;
      long total=source.getMetadata().getLength();
      check(source.getProgressive(),"Long MP3 needs lazy source");
      HandlerThread events = new HandlerThread("AudioSeekRuntimeEvents"); events.start();
      Handler handler = new Handler(events.getLooper());
      MediaPlayer player = on(handler,MediaPlayer::new);
      try {
        on(handler,()->{player.setDataSource(new PcmMediaDataSource(source.borrowedResource().open()));player.prepare();return null;});
        long preparedMs=(System.nanoTime()-started)/1000000;
        int duration=on(handler,player::getDuration);
        check(Math.abs(duration-120000)<30,"exact 120s duration");
        check(cache.length()<total/10,"Prepare must not decode the whole track: "+cache.length()+"/"+total);
        System.out.println("PASS MediaPlayer prepare long MP3 openMs="+openedMs+" preparedMs="+preparedMs+" cached="+cache.length()+" total="+total+" durationMs="+duration);
        int frameBytes=source.getPreparation().getFormat().getChannels()*source.getPreparation().getFormat().getBitsPerSample()/8;
        check("decoded-block-disk-cache".equals(source.getMetadata().getAttributes().get("access")),"MP3 playback needs indexed PCM blocks");
        byte[] window=new byte[16384];
        for (int seconds : new int[]{90,5,110,20,90}) {
          long t=System.nanoTime();
          int n=source.readAt(44L+seconds*44100L*frameBytes,window,0,window.length);
          check(n==window.length,"seek window length");
          check(Long.parseLong(source.getMetadata().getAttributes().get("cachedPcmBytes"))<1000000,"Seek decoded an entire prefix");
          System.out.println("SEEK "+seconds+"s latencyMs="+(System.nanoTime()-t)/1000000+" access="+source.getMetadata().getAttributes().get("access"));
        }
        for (int milliseconds : new int[]{80000,10000,100000,30000}) {
          CountDownLatch sought=new CountDownLatch(1);
          java.util.concurrent.atomic.AtomicReference<String> failed=new java.util.concurrent.atomic.AtomicReference<>();
          long t=System.nanoTime();
          on(handler,()->{
            player.setOnSeekCompleteListener(p->{System.out.println("SEEK callback position="+p.getCurrentPosition());sought.countDown();});
            player.setOnErrorListener((p,w,e)->{failed.set("SEEK player error "+w+" "+e);sought.countDown();return true;});
            player.seekTo(milliseconds,MediaPlayer.SEEK_PREVIOUS_SYNC);
            return null;
          });
          check(sought.await(8,TimeUnit.SECONDS),"MediaPlayer seek completion; position="+on(handler,player::getCurrentPosition)+" eventThreadAlive="+events.isAlive());
          long elapsed=(System.nanoTime()-t)/1000000;
          check(failed.get()==null,"MediaPlayer seek failed: "+failed.get());
          check(Math.abs(on(handler,player::getCurrentPosition)-milliseconds)<100,"MediaPlayer seek position");
          System.out.println("PASS MediaPlayer SEEK "+milliseconds+"ms completedMs="+elapsed+" cachedPcmBytes="+source.getMetadata().getAttributes().get("cachedPcmBytes")+" frameSeeks="+source.getMetadata().getAttributes().get("decoderFrameSeeks"));
          check(elapsed<1500,"PCM seek exceeded the local fixture budget");
        }
        File reference=new File(out,"seek-reference.wav");
        new AudioFileProcessor(Collections.emptyList()).prepare(new com.infinifolder.audio.stream.FileAudioInput(new File(out,"audio-pull-long.mp3"),"source.mp3"),reference,DecoderPreference.PORTABLE_ONLY,null,()->false);
        try (RandomAccessFile expected=new RandomAccessFile(reference,"r")) {
          byte[] actual=new byte[16384], wanted=new byte[16384];
          for (int seconds : new int[]{0,90,5,110,20}) {
            long pos=44L+seconds*44100L*frameBytes;
            source.readAt(pos,actual,0,actual.length);expected.seek(pos);expected.readFully(wanted);
            int differences=0,max=0;
            for(int i=0;i<actual.length;i+=2) {
              int a=(short)((actual[i]&255)|(actual[i+1]<<8)),b=(short)((wanted[i]&255)|(wanted[i+1]<<8));
              if(a!=b)differences++;max=Math.max(max,Math.abs(a-b));
            }
            System.out.println("SEEK PCM "+seconds+"s differingSamples="+differences+" maxError="+max);
            check(Arrays.equals(actual,wanted),"Seeked MP3 PCM differs from linear decoding");
          }
        }
      } finally { on(handler,()->{player.release();return null;}); events.quitSafely(); }
    }
  }
}
