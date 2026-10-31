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
    if (args.length>1 && (args[1].equals("gapless") || args[1].equals("source-gapless"))) { gapless(fixtures,out,args[1].equals("source-gapless"));return; }
    if (args.length>1 && args[1].startsWith("source-")) { sourceCheck(fixtures,out,args[1]);return; }
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
    if (args.length>1 && !args[1].startsWith("long") && !args[1].equals("provided")) return;
    boolean cbr = args.length>1 && args[1].equals("long-cbr");
    boolean flac = args.length>1 && args[1].equals("long-flac24");
    File input = args.length>2 ? new File(args[2]) : new File(out,flac ? "audio-pull-long-flac24.flac" : cbr ? "audio-pull-long-cbr.mp3" : "audio-pull-long.mp3");
    File cache = new File(out,cbr ? "pull-long-cbr.wav" : "pull-long.wav");
    long started=System.nanoTime();
    try (AudioWaveSource source = AudioProcessing.INSTANCE.openPlaybackSource(input,cache,new AudioPreparationObserver() {
      public void warning(String decoder, Throwable error) { System.out.println("WARNING "+decoder+" "+error); error.printStackTrace(); }
      public void prepared(AudioPreparation preparation) { System.out.println("PREPARED "+preparation); }
    },()->false)) {
      long openedMs=(System.nanoTime()-started)/1000000;
      long total=source.getMetadata().getLength();
      check(source.getProgressive(),"Long track needs lazy source");
      if(flac) check(source.getPreparation().getFormat().getBitsPerSample()==24,"FLAC precision must remain 24-bit");
      HandlerThread events = new HandlerThread("AudioSeekRuntimeEvents"); events.start();
      Handler handler = new Handler(events.getLooper());
      MediaPlayer player = on(handler,MediaPlayer::new);
      java.util.concurrent.atomic.AtomicReference<Throwable> analysisError=new java.util.concurrent.atomic.AtomicReference<>();
      CountDownLatch analyzed=new CountDownLatch(1);
      AudioAnalysisSession analyzer=AudioProcessing.INSTANCE.createAnalyzer(source.borrowedResource(),()->{
        try { return on(handler,()->new kotlin.Pair<Boolean,Long>(player.isPlaying(),(long)player.getCurrentPosition())); }
        catch(Exception error) { throw new RuntimeException(error); }
      },(message,error)->{analysisError.set(error);System.out.println("ANALYZER ERROR "+message+" "+error);return kotlin.Unit.INSTANCE;},
      (samples,channels,rate)->{analyzed.countDown();return kotlin.Unit.INSTANCE;});
      try {
        on(handler,()->{player.setDataSource(new PcmMediaDataSource(source.borrowedResource().open()));player.prepare();return null;});
        long preparedMs=(System.nanoTime()-started)/1000000;
        int duration=on(handler,player::getDuration);
        check(Math.abs(duration-source.getPreparation().getFormat().getDurationMs())<30,"exact duration");
        check(duration>=32000,"MP3 seek probe requires at least 32 seconds");
        int lastSecond=duration/1000-2;
        int[] secondsToRead=args.length>2 ? new int[]{Math.min(270,lastSecond),Math.min(330,lastSecond),Math.min(420,lastSecond),20,lastSecond} : new int[]{270,330,810,20,890,330};
        int[] seekTargets=args.length>2 ? new int[]{Math.min(260000,lastSecond*1000),Math.min(310000,lastSecond*1000),Math.min(420000,lastSecond*1000),30000,lastSecond*1000} : new int[]{260000,310000,800000,30000,898000};
        check(cache.length()<total/10,"Prepare must not decode the whole track: "+cache.length()+"/"+total);
        System.out.println("PASS MediaPlayer prepare "+input.getName()+" decoder="+source.getPreparation().getDecoder()+" access="+source.getMetadata().getAttributes().get("access")+" openMs="+openedMs+" preparedMs="+preparedMs+" cached="+cache.length()+" total="+total+" durationMs="+duration);
        int frameBytes=source.getPreparation().getFormat().getChannels()*source.getPreparation().getFormat().getBitsPerSample()/8;
        check("decoded-block-disk-cache".equals(source.getMetadata().getAttributes().get("access")),"Playback needs indexed PCM blocks");
        byte[] window=new byte[16384];
        for (int seconds : secondsToRead) {
          long t=System.nanoTime();
          int n=source.readAt(44L+seconds*(long)source.getPreparation().getFormat().getSampleRate()*frameBytes,window,0,window.length);
          check(n==window.length,"seek window length");
          check(Long.parseLong(source.getMetadata().getAttributes().get("cachedPcmBytes"))<1000000,"Seek decoded an entire prefix");
          System.out.println("SEEK "+seconds+"s latencyMs="+(System.nanoTime()-t)/1000000+" access="+source.getMetadata().getAttributes().get("access"));
        }
        on(handler,()->{player.setVolume(0,0);player.start();return null;});
        analyzer.start();
        check(analyzed.await(3,TimeUnit.SECONDS),"Analyzer produced no PCM");
        for (int milliseconds : seekTargets) {
          CountDownLatch sought=new CountDownLatch(1);
          java.util.concurrent.atomic.AtomicInteger landed=new java.util.concurrent.atomic.AtomicInteger(-1);
          java.util.concurrent.atomic.AtomicReference<String> failed=new java.util.concurrent.atomic.AtomicReference<>();
          long t=System.nanoTime();
          on(handler,()->{
            player.setOnSeekCompleteListener(p->{
              int position=p.getCurrentPosition();
              // Android may deliver an earlier request's callback after the listener changes.
              if(Math.abs((long)position-milliseconds)>=100){System.out.println("SEEK stale callback position="+position+" pending="+milliseconds);return;}
              landed.set(position);System.out.println("SEEK callback position="+position);sought.countDown();
            });
            player.setOnErrorListener((p,w,e)->{failed.set("SEEK player error "+w+" "+e);sought.countDown();return true;});
            player.seekTo(milliseconds,MediaPlayer.SEEK_PREVIOUS_SYNC);
            return null;
          });
          check(sought.await(8,TimeUnit.SECONDS),"MediaPlayer seek completion; position="+on(handler,player::getCurrentPosition)+" eventThreadAlive="+events.isAlive());
          long elapsed=(System.nanoTime()-t)/1000000;
          check(failed.get()==null,"MediaPlayer seek failed: "+failed.get());
          check(Math.abs((long)landed.get()-milliseconds)<100,"MediaPlayer seek position at matching callback");
          System.out.println("PASS MediaPlayer SEEK "+milliseconds+"ms completedMs="+elapsed+" cachedPcmBytes="+source.getMetadata().getAttributes().get("cachedPcmBytes")+" frameSeeks="+source.getMetadata().getAttributes().get("decoderFrameSeeks"));
          check(elapsed<1500,"PCM seek exceeded the local fixture budget");
          check(source.getProgressive(),"Seek triggered full-file fallback");
        }
        on(handler,()->{player.pause();return null;});
        analyzer.stop();
        check(analysisError.get()==null,"Analyzer failed: "+analysisError.get());
        File reference=new File(out,"seek-reference.wav");
        new AudioFileProcessor(Collections.emptyList()).prepare(new com.infinifolder.audio.stream.FileAudioInput(input,input.getName()),reference,DecoderPreference.PORTABLE_ONLY,null,()->false);
        try (RandomAccessFile expected=new RandomAccessFile(reference,"r")) {
          byte[] actual=new byte[16384], wanted=new byte[16384];
          for (int seconds : secondsToRead) {
            long pos=44L+seconds*(long)source.getPreparation().getFormat().getSampleRate()*frameBytes;
            source.readAt(pos,actual,0,actual.length);expected.seek(pos);expected.readFully(wanted);
            int differences=0;long max=0;
            int width=source.getPreparation().getFormat().getBitsPerSample()/8;
            for(int i=0;i<=actual.length-width;i+=width) {
              int a=0,b=0;
              for(int j=0;j<width;j++){a|=(actual[i+j]&255)<<(j*8);b|=(wanted[i+j]&255)<<(j*8);}
              a<<=32-width*8;b<<=32-width*8;
              if(a!=b)differences++;max=Math.max(max,Math.abs((long)a-b));
            }
            System.out.println("SEEK PCM "+seconds+"s differingSamples="+differences+" maxError="+max);
            check(Arrays.equals(actual,wanted),"Seeked PCM differs from linear decoding");
          }
        }
      } finally { analyzer.stop();on(handler,()->{player.release();return null;}); events.quitSafely(); }
    }
  }
  static void gapless(File fixtures, File out, boolean shared) throws Exception {
    File tagged=new File(out,"gapless-tagged.mp3");
    try (FileOutputStream file=new FileOutputStream(tagged)) {
      file.write(java.nio.file.Files.readAllBytes(new File(fixtures,"chord.mp3").toPath()));
      byte[] tag=new byte[128];tag[0]='T';tag[1]='A';tag[2]='G';file.write(tag);
    }
    try (SharedPositionalResource firstInput=shared ? new SharedPositionalResource(new FileSourceResource(tagged,new ContentType()).open(),null) : null;
         SharedPositionalResource secondInput=shared ? new SharedPositionalResource(new FileSourceResource(tagged,new ContentType()).open(),null) : null;
         AudioWaveSource first=shared ? AudioProcessing.INSTANCE.openPlaybackSource(firstInput,new File(out,"gapless-first.wav"),null,()->false) : AudioProcessing.INSTANCE.openPlaybackSource(tagged,new File(out,"gapless-first.wav"),null,()->false);
         AudioWaveSource second=shared ? AudioProcessing.INSTANCE.openPlaybackSource(secondInput,new File(out,"gapless-second.wav"),null,()->false) : AudioProcessing.INSTANCE.openPlaybackSource(tagged,new File(out,"gapless-second.wav"),null,()->false)) {
      HandlerThread events=new HandlerThread("GaplessRuntimeEvents");events.start();Handler handler=new Handler(events.getLooper());
      MediaPlayer current=on(handler,MediaPlayer::new), next=on(handler,MediaPlayer::new);
      CountDownLatch firstDone=new CountDownLatch(1),secondDone=new CountDownLatch(1);
      java.util.concurrent.atomic.AtomicReference<String> failed=new java.util.concurrent.atomic.AtomicReference<>();
      try {
        on(handler,()->{
          current.setVolume(0,0);next.setVolume(0,0);
          current.setDataSource(new PcmMediaDataSource(first.borrowedResource().open()));current.prepare();
          next.setDataSource(new PcmMediaDataSource(second.borrowedResource().open()));next.prepare();
          current.setOnCompletionListener(p->firstDone.countDown());next.setOnCompletionListener(p->secondDone.countDown());
          MediaPlayer.OnErrorListener error=(p,w,e)->{failed.set(w+"/"+e);firstDone.countDown();secondDone.countDown();return true;};
          current.setOnErrorListener(error);next.setOnErrorListener(error);
          current.setNextMediaPlayer(next);current.start();return null;
        });
        check(firstDone.await(5,TimeUnit.SECONDS),"First linked player never completed");
        check(secondDone.await(5,TimeUnit.SECONDS),"Next linked player did not start/complete automatically");
        check(failed.get()==null,"Linked player failed: "+failed.get());
        check(first.getProgressive() && second.getProgressive(),"Tagged gapless playback triggered fallback");
        check(first.getPreparation().getFormat().getTotalFrames()==66150L && second.getPreparation().getFormat().getTotalFrames()==66150L,"Gapless trim changed");
        System.out.println("PASS linked gapless handoff shared="+shared+" with tagged MP3 sources; exact 66150-frame timelines, playback muted");
      } finally { on(handler,()->{current.release();next.release();return null;});events.quitSafely(); }
    }
  }
  static class TrackedSource implements ByteSource, PositionalReadable {
    final ByteSource source; final StreamMetadata metadata; long bytes; int closes;
    TrackedSource(File file) throws Exception {
      source=new FileSourceResource(file,new ContentType()).open(); StreamMetadata m=source.getMetadata();
      metadata=new StreamMetadata(m.getContent(),m.getName(),m.getLength(),m.getFiniteness(),m.getCapabilities(),m.getOwnership(),m.getConcurrency(),m.getAttributes(),new Provenance("test:positional/"+file.getName(),null,m.getLength(),Collections.emptyList(),Collections.emptyMap()),m.getVerification(),m.getWarnings(),m.getRevision());
    }
    public StreamMetadata getMetadata(){return metadata;}
    public int read(byte[] buffer,int offset,int length){throw new AssertionError("Encoded cursor must remain independent");}
    public int readAt(long position,byte[] buffer,int offset,int length) {
      int n=((PositionalReadable)source).readAt(position,buffer,offset,length);if(n>0)bytes+=n;return n;
    }
    public void close() throws IOException{closes++;source.close();}
  }
  static void sourceCheck(File fixtures,File out,String mode) throws Exception {
    boolean fallback=mode.equals("source-fallback");
    File file=mode.equals("source-flac24") ? new File(out,"audio-pull-long-flac24.flac") : mode.equals("source-mp3") ? new File(out,"audio-pull-long-cbr.mp3") : new File(fixtures,"chord.m4a");
    if(fallback){file=new File(out,"source-invalid.bin");try(FileOutputStream output=new FileOutputStream(file)){output.write(new byte[128]);}}
    TrackedSource raw=new TrackedSource(file);
    java.util.concurrent.atomic.AtomicInteger stages=new java.util.concurrent.atomic.AtomicInteger(),leases=new java.util.concurrent.atomic.AtomicInteger();
    try(com.infinifolder.viewer.AudioPlaybackInput input=com.infinifolder.viewer.AudioPlaybackInput.Companion.select(raw,()->{
      if(!fallback)throw new AssertionError("Positional source unexpectedly staged");stages.incrementAndGet();
      return new com.infinifolder.viewer.MediaSourceAdapter.StagedSource(new File(fixtures,"chord.mp3"),()->leases.incrementAndGet());
    },AudioProcessing.INSTANCE::requiresLocalFileForPlayback,file.getName())) {
      if(!fallback) {
        AudioFormatInfo format=AudioProcessing.INSTANCE.probeFormat(input.getResource(),file.getName(),0L);
        check(format.getSampleRate()==44100 && format.getChannels()==2,"Positional metadata probe");
        AudioTags tags=AudioProcessing.INSTANCE.readTags(input.getResource(),false);
        long expectedDuration=mode.equals("source-m4a") ? 1500L : 900000L;
        check(tags.getDuration()!=null && Math.abs(Long.parseLong(tags.getDuration())-expectedDuration)<60,"Positional tag duration");
      }
      try(AudioWaveSource source=input.openWaveSource(new File(out,"source-pcm.wav"),null,false,()->false)) {
        check(source.getProgressive(),"Positional PCM must remain lazy");
        if(fallback){check(stages.get()==1 && raw.closes==1 && leases.get()==0,"Explicit fallback ownership");}
        else {
          check(input.getFile()==null && stages.get()==0,"No encoded staging expected");
          check(source.getMetadata().getProvenance().getSource().equals(raw.metadata.getProvenance().getSource()),"Input provenance lost");
          if(file.length()>1000000)check(raw.bytes<file.length()/2,"Opening eagerly read the encoded source");
          int width=source.getPreparation().getFormat().getChannels()*source.getPreparation().getFormat().getBitsPerSample()/8;
          if(mode.equals("source-flac24"))check(source.getPreparation().getFormat().getBitsPerSample()==24,"FLAC precision changed");
          File reference=new File(out,"source-reference.wav");
          new AudioFileProcessor(Collections.emptyList()).prepare(new com.infinifolder.audio.stream.FileAudioInput(file,file.getName()),reference,DecoderPreference.PORTABLE_ONLY,null,()->false);
          check(reference.length()==source.getMetadata().getLength(),"Source PCM frame count differs from file");
          if(mode.equals("source-m4a")){byte[] pcm=new byte[8192];check(source.readAt(44,pcm,0,pcm.length)==pcm.length,"AAC source PCM read");}
          if(!mode.equals("source-m4a"))try(RandomAccessFile expected=new RandomAccessFile(reference,"r")){
            for(int seconds:new int[]{810,5,890,20}){
              long at=44L+seconds*44100L*width;byte[] actual=new byte[16384],wanted=new byte[16384];long t=System.nanoTime();
              check(source.readAt(at,actual,0,actual.length)==actual.length,"Source seek length");expected.seek(at);expected.readFully(wanted);
              check(Arrays.equals(actual,wanted),"Source seek PCM differs from linear decoding");
              check((System.nanoTime()-t)/1000000<1500,"Source seek latency exceeded budget");
            }
          }
          check(raw.closes==0,"Decoder closed the host-owned source");
          check(source.getProgressive(),"Positional seek triggered full preparation");
          System.out.println("PASS "+mode+" decoder="+source.getPreparation().getDecoder()+" bits="+source.getPreparation().getFormat().getBitsPerSample()+" encodedBytesRead="+raw.bytes+" encodedStaging=none; metadata, frame count and codec-specific PCM checks verified");
        }
      }
      if(!fallback)check(raw.closes==0,"Closing PCM must retain the encoded lease until host close");
    }
    check(raw.closes==1,"Encoded source must close once");
    if(fallback){check(leases.get()==1,"Staged lease must close once");System.out.println("PASS explicit staged fallback and ownership");}
  }

}
