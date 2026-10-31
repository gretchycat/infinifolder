import com.infinifolder.audio.*;
import com.infinifolder.audio.stream.*;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real libVLC PCM callbacks, independent of video and without opening an output device. */
public class LibVlcAudioRuntimeCheck {
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static String hex(byte[] bytes) { StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format("%02x",b&255));return result.toString(); }
    static class Decoded {
        String backend,hash;
        int rate,channels;
        long samples,energy;
    }
    static Decoded decode(AudioInput input) throws Exception {
        try(PcmStream stream=AudioProcessing.INSTANCE.openPcm(input,DecoderPreference.PLATFORM_FIRST)) {
            Decoded result=new Decoded();
            result.backend=stream.getDecoderName();result.rate=stream.getFormat().getSampleRate();result.channels=stream.getFormat().getChannels();
            int[] samples=new int[8192*result.channels];MessageDigest digest=MessageDigest.getInstance("SHA-256");
            while(true) {
                int count=stream.read(samples,0,samples.length);
                if(count<0) break;
                check(count>0&&count%result.channels==0,"invalid PCM read");result.samples+=count;
                for(int i=0;i<count;i++) {
                    short v=(short)(samples[i]>>16);result.energy+=Math.abs((int)v);digest.update((byte)v);digest.update((byte)(v>>8));
                }
            }
            check(result.samples>0&&result.energy>0,"No actual decoded signal");
            result.hash=hex(digest.digest());return result;
        }
    }
    static AudioInput file(File file) { return new FileAudioInput(file,file.getName()); }
    static AudioInput memory(byte[] bytes,String name) { return new MemoryAudioInput(bytes,name,null); }
    static void rejects(AudioInput input) throws Exception {
        try(PcmStream stream=AudioProcessing.INSTANCE.openPcm(input,DecoderPreference.PLATFORM_FIRST)) {
            int[] values=new int[32768];
            if(stream.read(values,0,values.length)>0) throw new AssertionError("Invalid source produced PCM: "+input.getName());
        } catch(IOException expected) { return; }
        throw new AssertionError("Invalid source was accepted");
    }
    static final class FilesContext extends ContextWrapper {
        final File directory;final AssetManager assets;
        FilesContext(File apk,File directory) throws Exception {
            super(null);this.directory=directory;directory.mkdirs();
            assets=AssetManager.class.getDeclaredConstructor().newInstance();
            int cookie=(Integer)AssetManager.class.getDeclaredMethod("addAssetPath",String.class).invoke(assets,apk.getAbsolutePath());
            check(cookie!=0,"APK assets unavailable");
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public File getFilesDir() { return directory; }
        @Override public AssetManager getAssets() { return assets; }
    }
    public static void main(String[] args) {
        try {
            File root=new File(args[0]),work=new File(args[1]),apk=new File(args[2]);work.mkdirs();
            File audio=new File(root,"filesystem-test-images/audio");
            Map<String,Long> expectedSamples=new HashMap<>();
            for(String line:Files.readAllLines(new File(work,"reference-samples.tsv").toPath())) {
                String[] fields=line.split("\t");expectedSamples.put(fields[0],Long.parseLong(fields[1]));
            }
            Map<String,Decoded> controls=new LinkedHashMap<>();
            for(String ext:new String[]{"wma","ra","wv","tta","ac3","eac3","dts","ogg","opus"}) {
                File source=new File(audio,"c_major_piano."+ext);
                if(!source.isFile()) continue;
                Decoded result=decode(file(source));controls.put(ext,result);
                if(ext.equals("wma")||ext.equals("ra")) {
                    check("libVLC.PCM".equals(result.backend),"Extra codec backend was not used");
                    check(result.samples==expectedSamples.get(ext),"libVLC lost or invented decoded samples for "+ext);
                }
                System.out.println("PASS vlc-audio."+ext+" backend="+result.backend+" rate="+result.rate+" channels="+result.channels+" samples="+result.samples+" sha256="+result.hash);
            }
            File pcmFile=new File(work,"prepared-wma.wav");
            try(AudioWaveSource wave=AudioProcessing.INSTANCE.openPlaybackSource(new File(audio,"c_major_piano.wma"),pcmFile,null,()->false)) {
                check(wave.getPreparation().getDecoder().equals("libVLC.PCM"),"WMA output bypassed the codec adapter");
                check(wave.getPreparation().getFormat().getTotalFrames()==controls.get("wma").samples/controls.get("wma").channels,"Prepared WAVE length is not actual PCM length");
                byte[] waveBytes=Files.readAllBytes(pcmFile.toPath());
                check(hex(MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(waveBytes,44,waveBytes.length))).equals(controls.get("wma").hash),"Output WAVE is not the selected decoded audio");
            } finally { pcmFile.delete(); }
            System.out.println("PASS vlc-audio.common-wave-preparation-exact-length");
            byte[] wma=Files.readAllBytes(new File(audio,"c_major_piano.wma").toPath());
            AtomicInteger opens=new AtomicInteger(),closes=new AtomicInteger(),requests=new AtomicInteger(),largest=new AtomicInteger();
            AudioInput shortReads=new AudioInput() {
                public String getName(){ return "common-source.wma"; }
                public AudioBytes open() {
                    opens.incrementAndGet();
                    return new AudioBytes() {
                        public long getSize(){return wma.length;}
                        public int readAt(long position,byte[] out,int offset,int length) {
                            requests.incrementAndGet();largest.accumulateAndGet(length,Math::max);
                            if(length==0)return 0;if(position>=wma.length)return -1;
                            int count=(int)Math.min(Math.min(length,257),wma.length-position);
                            System.arraycopy(wma,(int)position,out,offset,count);return count;
                        }
                        public void close(){closes.incrementAndGet();}
                    };
                }
            };
            Decoded shortResult=decode(shortReads);
            check(shortResult.hash.equals(controls.get("wma").hash),"Short/positional reads changed actual PCM");
            check(opens.get()==closes.get()&&requests.get()>10&&largest.get()<=131072,"Borrowed input lifetime/ranges");
            System.out.println("PASS vlc-audio.common-input-short-reads-and-ownership");
            rejects(memory(new byte[128],"invalid.wma"));rejects(memory(".RMF invalid stream".getBytes(),"invalid.ra"));
            System.out.println("PASS vlc-audio.invalid-input-no-substitute");
            for(int i=0;i<4;i++) {
                PcmStream stream=AudioProcessing.INSTANCE.openPcm(file(new File(audio,"c_major_piano.wma")),DecoderPreference.PLATFORM_FIRST);
                stream.close();stream.close();
            }
            check(decode(file(new File(audio,"c_major_piano.ra"))).hash.equals(controls.get("ra").hash),"Reopen changed PCM");
            System.out.println("PASS vlc-audio.reopen-and-repeated-close");
            PcmStream blocked=AudioProcessing.INSTANCE.openPcm(file(new File(work,"long.wma")),DecoderPreference.PLATFORM_FIRST);
            Thread.sleep(500);
            long closeStarted=System.nanoTime();blocked.close();
            check(System.nanoTime()-closeStarted<5_000_000_000L,"Closing a backpressured decoder hung");
            System.out.println("PASS vlc-audio.backpressure-close");
            Thread.currentThread().interrupt();
            try { AudioProcessing.INSTANCE.openPcm(file(new File(audio,"c_major_piano.wma")),DecoderPreference.PLATFORM_FIRST);throw new AssertionError("Cancellation ignored"); }
            catch(Exception expected) { check(expected instanceof InterruptedIOException,"Wrong cancellation result"); } finally { Thread.interrupted(); }
            System.out.println("PASS vlc-audio.cancellation");
            // Run the actual bundled asset loader: bounded bz2 extraction + checksum + native SF2 presets.
            FilesContext context=new FilesContext(apk,new File(work,"font-state"));
            AudioProcessing.INSTANCE.initialize(context);
            Set<String> hashes=new HashSet<>();
            for(String name:new String[]{"piano.mid","flute.mid","low-piano.mid","drums.mid"}) {
                Decoded result=decode(file(new File(work,name)));
                check(result.backend.equals("libVLC.FluidLite(FluidR3_GM)"),"MIDI did not use the configured SoundFont");
                hashes.add(result.hash);
            }
            check(hashes.size()==4,"MIDI program/percussion selection ignored");
            System.out.println("PASS vlc-audio.fluidr3-programs-and-percussion");
            File font=new File(context.getFilesDir(),"soundfonts/FluidR3_GM.sf2");
            check(font.length()==148398306L,"Incomplete cached SoundFont");
            try(InputStream input=new FileInputStream(font)) {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int count;
                while((count=input.read(b))>=0)digest.update(b,0,count);
                check(hex(digest.digest()).equals("74594e8f4250680adf590507a306655a299935343583256f3b722c48a1bc1cb0"),"SoundFont asset checksum");
            }
            check(decode(file(new File(audio,"c_major_piano.mid"))).samples>0,"Bundled font reuse failed");
            check(Arrays.stream(font.getParentFile().listFiles()).noneMatch(f->f.getName().endsWith(".partial")),"Partial SoundFont left behind");
            System.out.println("PASS vlc-audio.bundled-bzip2-checksum-and-reuse");
            System.out.println("LIBVLC_AUDIO_COMPLETE controls="+controls.size());System.exit(0);
        } catch(Throwable error) { error.printStackTrace();System.exit(1); }
    }
}
