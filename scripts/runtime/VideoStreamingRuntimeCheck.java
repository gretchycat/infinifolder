import android.net.Uri;
import io.github.gretchycat.infinistream.*;
import io.github.gretchycat.infinivideo.*;
import com.infinifolder.archive.*;
import com.infinifolder.model.*;
import org.videolan.libvlc.*;
import org.videolan.libvlc.interfaces.IMedia;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.*;

/** Real libVLC HTTP input, bounded virtual sources and non-extracting container views. */
public class VideoStreamingRuntimeCheck {
    static void check(boolean value,String message) { LibVlcRuntimeCheck.check(value,message); }
    static void pass(String name) { System.out.println("PASS video-stream."+name); }
    static StreamMetadata metadata(File file,Long length,boolean positional) {
        StreamMetadata m=new FileSourceResource(file,new ContentType()).getMetadata();
        Set<Capability> caps=new HashSet<>(Set.of(Capability.READ,Capability.REOPEN));
        if(positional)caps.add(Capability.POSITIONAL_READ);
        return new StreamMetadata(m.getContent(),m.getName(),length,m.getFiniteness(),caps,m.getOwnership(),m.getConcurrency(),
            m.getAttributes(),m.getProvenance(),m.getVerification(),m.getWarnings(),m.getRevision());
    }
    static class VirtualMovie implements ByteSource,PositionalReadable {
        final RandomAccessFile file;
        final long original,size;
        final StreamMetadata metadata;
        final byte[] paddingHeader;
        final AtomicLong reads=new AtomicLong();
        int largest;
        final ArrayDeque<Long> positions=new ArrayDeque<>();
        long cursor;
        boolean closed;
        VirtualMovie(File path) throws Exception {
            file=new RandomAccessFile(path,"r");original=file.length();size=512L*1024*1024;
            metadata=metadata(path,size,true);
            paddingHeader=ByteBuffer.allocate(8).putInt((int)(size-original)).put(new byte[]{'f','r','e','e'}).array();
        }
        public StreamMetadata getMetadata() { return metadata; }
        public synchronized int readAt(long pos,byte[] b,int off,int length) {
            try {
            if(closed)throw new IOException("closed");
            if(pos>=size)return -1;
            int count=(int)Math.min(length,size-pos);largest=Math.max(largest,count);
            positions.add(pos);if(positions.size()>24)positions.remove();
            int fromFile=(int)Math.min(count,Math.max(0,original-pos));
            if(fromFile>0) { file.seek(pos);file.readFully(b,off,fromFile); }
            for(int i=fromFile;i<count;i++) {
                long padding=pos+i-original;b[off+i]=padding>=0&&padding<8?paddingHeader[(int)padding]:0;
            }
            reads.addAndGet(count);return count;
            } catch(IOException error) { throw new UncheckedIOException(error); }
        }
        public synchronized int read(byte[] b,int off,int length) {
            int count=readAt(cursor,b,off,length);if(count>0)cursor+=count;return count;
        }
        public synchronized void close() throws IOException { if(!closed) { closed=true;file.close(); } }
    }
    static void probe(LibVLC vlc,VideoStreamServer server,long duration,boolean audio) {
        Media media=LibVlcMedia.INSTANCE.open(vlc,Uri.parse(server.getUrl()));
        try {
            check(media.parse(IMedia.Parse.ParseNetwork),"native streaming metadata parse");
            check(Math.abs(media.getDuration()-duration)<200,"stream duration "+media.getDuration());
            int video=0,sound=0;
            for(int i=0;i<media.getTrackCount();i++) {
                IMedia.Track track=media.getTrack(i);
                if(track instanceof IMedia.VideoTrack) {
                    IMedia.VideoTrack v=(IMedia.VideoTrack)track;
                    check(v.width==320&&v.height==180,"stream dimensions");video++;
                }
                if(track.type==IMedia.Track.Type.Audio)sound++;
            }
            check(video==1,"one stream video track");check(sound==(audio?1:0),"stream audio track count");
        } finally { media.release(); }
    }
    static void virtual(LibVLC vlc,File root) throws Exception {
        VirtualMovie movie=new VirtualMovie(new File(root,"91-six-minute-audio-seek.mp4"));
        SharedPositionalResource owner=new SharedPositionalResource(movie,movie.getMetadata());
        VideoStreamServer server=new VideoStreamServer(owner);
        try {
            check(server.getBytesRead()==0&&movie.reads.get()==0,"opening never reads the movie body");
            probe(vlc,server,360000,true);
            check(server.getBytesRead()<16L*1024*1024,"metadata did not read the 512 MiB source");
            System.out.println("STREAM metadataBytes="+server.getBytesRead()+" sourceLength="+movie.size);
            pass("metadata-without-whole-file-copy");
            LibVlcRuntimeCheck.decodeSeekAudioClock(vlc,LibVlcMedia.INSTANCE.open(vlc,Uri.parse(server.getUrl())));
            check(movie.largest<=65536,"bounded native input requests");
            // Include native read-ahead across three seeks; the transport itself retains only chunks.
            check(server.getBytesRead()<64L*1024*1024,"three seeks stay below one eighth of the virtual movie");
            System.out.println("STREAM seekBytes="+server.getBytesRead()+" sourceLength="+movie.size+" requests="+server.getRequests());
            pass("positional-native-decode-and-long-seeks");
        } finally {
            System.out.println("STREAM finalBytes="+server.getBytesRead()+" requests="+server.getRequests()+" failure="+server.getLastFailure()+" positions="+movie.positions);
            server.close();owner.close();
        }
        check(movie.closed,"underlying source closes after native sessions");
    }
    static void compressed(LibVLC vlc,File root) throws Exception {
        File original=new File(root,"91-six-minute-audio-seek.mp4");
        SourceResource resource=new SourceResource() {
            final StreamMetadata m=metadata(original,original.length(),false);
            public StreamMetadata getMetadata() { return m; }
            public ByteSource open() {
                try {
                    ZipFile zip=new ZipFile(new File(root,"video-audio-seek-checks.zip"));
                    InputStream input=zip.getInputStream(zip.getEntry("video/91-six-minute-audio-seek.mp4"));
                    return new InputStreamSource(new FilterInputStream(input) {
                        public void close() throws IOException { try { super.close(); } finally { zip.close(); } }
                    },m,true,()->false);
                } catch(IOException error) { throw new java.io.UncheckedIOException(error); }
            }
        };
        VideoStreamServer server=new VideoStreamServer(resource);
        try {
            check(server.getBytesRead()==0,"no archive extraction on stream open");
            check(server.getAccess().equals("sequential-replay"),"honest replay access description");
            probe(vlc,server,360000,true);
            LibVlcRuntimeCheck.decodeSeekAudioClock(vlc,LibVlcMedia.INSTANCE.open(vlc,Uri.parse(server.getUrl())));
            check(server.getReplayedBytes()>0,"archive seeks replay a prefix rather than staging a file");
            System.out.println("ZIP streamedBytes="+server.getBytesRead()+" replayedBytes="+server.getReplayedBytes());
        } finally { server.close(); }
        pass("compressed-zip-native-seek-replay");
    }
    static SourceResource unknownSource(File file) {
        return new SourceResource() {
            final StreamMetadata m=metadata(file,null,false);
            public StreamMetadata getMetadata() { return m; }
            public ByteSource open() {
                try { return new InputStreamSource(new FileInputStream(file),m,true,()->false); }
                catch(IOException error) { throw new java.io.UncheckedIOException(error); }
            }
        };
    }
    static void unknown(LibVLC vlc,File root) throws Exception {
        VideoStreamServer server=new VideoStreamServer(unknownSource(new File(root,"02-vp8-vorbis.webm")));
        try { probe(vlc,server,12000,true);check(server.getAccess().equals("sequential-source"),"unknown total remains sequential"); }
        finally { server.close(); }
        // MP4 preparsing on an unknown-length stream can report provisional tracks.
        // Exercise actual playback discovery rather than treating that as final metadata.
        server=new VideoStreamServer(unknownSource(new File(root,"01-avc-aac.mp4")));
        Media media=LibVlcMedia.INSTANCE.open(vlc,Uri.parse(server.getUrl()));
        MediaPlayer player=new MediaPlayer(vlc);
        try {
            media.setHWDecoderEnabled(false,false);media.addOption(":no-video");
            check(player.setAudioOutput("dummy"),"unknown MP4 dummy output");
            player.setMedia(media);player.play();long deadline=System.currentTimeMillis()+8000;
            while((!player.isPlaying()||player.getTime()<100||media.getStats()==null||media.getStats().decodedAudio==0)&&System.currentTimeMillis()<deadline)Thread.sleep(20);
            check(player.isPlaying()&&player.getVideoTracksCount()>0,"unknown-length MP4 discovers video during playback");
            check(media.getStats()!=null&&media.getStats().decodedAudio>0,"unknown-length MP4 decodes audio clock");
        } finally { player.stop();player.release();media.release();server.close(); }
        pass("unknown-length-native-chunked-input");
    }
    static void archiveViews(File state) throws Exception {
        File zipFile=new File(state,"streaming-views.zip");
        byte[] payload=new byte[8192];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i%251);
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(zipFile))) {
            CRC32 crc=new CRC32();crc.update(payload);
            ZipEntry stored=new ZipEntry("stored.mp4");stored.setMethod(ZipEntry.STORED);
            stored.setSize(payload.length);stored.setCompressedSize(payload.length);stored.setCrc(crc.getValue());
            zip.putNextEntry(stored);zip.write(payload);zip.closeEntry();
            zip.putNextEntry(new ZipEntry("deflated.mp4"));zip.write(payload);zip.closeEntry();
        }
        File cache=new File(state,"streaming-view-cache-"+UUID.randomUUID());cache.mkdirs();
        ArchiveStorageProvider provider=new ArchiveStorageProvider(new LibVlcRuntimeCheck.TestContext(state),null,cache);
        Uri storedUri=ArchiveUri.INSTANCE.build(Uri.fromFile(zipFile),"stored.mp4",false);
        ProviderResult<BlockDevice> result=provider.openStreamingSeekable(new ResourceRef("archive",storedUri.toString()));
        check(result instanceof ProviderResult.Success,"stored ZIP yields actual positional slice "+result);
        BlockDevice device=(BlockDevice)((ProviderResult.Success<?>)result).getValue();
        try {
            byte[] actual=new byte[50];check(device.readAt(7000,actual,0,50)==50,"distant entry read");
            check(Arrays.equals(actual,Arrays.copyOfRange(payload,7000,7050)),"slice reads original member bytes");
        } finally { device.close(); }
        pass("stored-zip-native-positional-view");
        Uri deflatedUri=ArchiveUri.INSTANCE.build(Uri.fromFile(zipFile),"deflated.mp4",false);
        result=provider.openStreamingSeekable(new ResourceRef("archive",deflatedUri.toString()));
        check(result instanceof ProviderResult.Unsupported,"compressed ZIP declines extraction "+result);
        check(cache.listFiles()!=null&&cache.listFiles().length==0,"non-extracting open creates no cache payload");
        pass("compressed-zip-declines-eager-extraction");
    }
    public static void main(String[] args) {
        try {
            LibVLC vlc=LibVlcRuntimeCheck.createEngine(args);
            try {
                pass("packaged-native-linkage");
                File root=new File(args[0]);virtual(vlc,root);compressed(vlc,root);unknown(vlc,root);
                archiveViews(new File(args[1]));
                Media audible=LibVlcMedia.INSTANCE.open(vlc,new File(root,"91-six-minute-audio-seek.mp4"));
                try {
                    check(audible.parse(0),"audible long file parse");int audio=0;
                    for(int i=0;i<audible.getTrackCount();i++)if(audible.getTrack(i).type==IMedia.Track.Type.Audio)audio++;
                    check(audio==1&&Math.abs(audible.getDuration()-360000)<200,"six-minute companion has audio");
                } finally { audible.release(); }
                pass("six-minute-audible-fixture");
            } finally { vlc.release(); }
            System.exit(0);
        } catch(Throwable error) { error.printStackTrace();System.exit(1); }
    }
}
