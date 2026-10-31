import com.infinifolder.audio.*;
import com.infinifolder.audio.engine.PcmMediaDataSource;
import io.github.gretchycat.infinistream.*;
import android.media.*;
import java.io.*;
import java.nio.*;
import java.util.*;

public class AudioPullRuntimeCheck {
  static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    try { run(args); } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
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
        System.out.println("PASS PCM extractor "+name+" decoder="+source.getPreparation().getDecoder()+" initial="+initial+" total="+pos);
      }
    }
    if (args.length>1 && !args[1].equals("long")) return;
    File cache = new File(out,"pull-long.wav");
    long started=System.nanoTime();
    try (AudioWaveSource source = AudioProcessing.INSTANCE.openWaveSource(new File(out,"audio-pull-long.mp3"),cache,null,()->false)) {
      long openedMs=(System.nanoTime()-started)/1000000;
      long total=source.getMetadata().getLength();
      check(source.getProgressive(),"Long MP3 needs lazy source");
      MediaPlayer player = new MediaPlayer();
      try {
        player.setDataSource(new PcmMediaDataSource(source.borrowedResource().open()));
        player.prepare();
        long preparedMs=(System.nanoTime()-started)/1000000;
        check(Math.abs(player.getDuration()-120000)<30,"exact 120s duration");
        check(cache.length()<total/10,"Prepare must not decode the whole track: "+cache.length()+"/"+total);
        System.out.println("PASS MediaPlayer prepare long MP3 openMs="+openedMs+" preparedMs="+preparedMs+" cached="+cache.length()+" total="+total+" durationMs="+player.getDuration());
      } finally { player.release(); }
    }
  }
}
