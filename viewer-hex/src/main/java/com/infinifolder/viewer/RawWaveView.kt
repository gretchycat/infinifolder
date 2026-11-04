package com.infinifolder.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.infinifolder.audio.model.RawAudioExplorer
import com.infinifolder.theme.ThemeManager

internal class RawWaveView(context: Context) : View(context) {
    var envelope: RawAudioExplorer.Envelope? = null
        set(value) { field=value;invalidate() }
    var config = RawAudioExplorer.Config()
    var selectedFrame = 0
        set(value) {field=value;invalidate()}
    var onFrame: (Int)->Unit = {}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val gestures=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){
        override fun onDown(e:MotionEvent)=true
        override fun onSingleTapUp(e:MotionEvent):Boolean {
            val frames=envelope?.frames ?: return false
            val frame=((e.x/width.coerceAtLeast(1))*frames).toInt().coerceIn(0,frames-1)
            selectedFrame=frame;onFrame(frame);performClick();return true
        }
    })
    init { contentDescription="Raw PCM waveform. Tap to select a sample frame.";isClickable=true }
    override fun performClick():Boolean {super.performClick();return true}
    override fun onTouchEvent(event:MotionEvent)=gestures.onTouchEvent(event)
    override fun onDraw(canvas:Canvas) {
        val theme=ThemeManager.activeTheme
        canvas.drawColor(theme.background)
        val data=envelope ?: return
        val density=resources.displayMetrics.density
        val top=24*density;val bottom=24*density
        val lane=(height-top-bottom).coerceAtLeast(1f)/data.minimum.size
        paint.textSize=12*resources.displayMetrics.scaledDensity
        for(ch in data.minimum.indices) {
            val center=top+lane*(ch+.5f);val amplitude=lane*.42f
            paint.color=theme.cardStroke;paint.strokeWidth=density
            canvas.drawLine(0f,center,width.toFloat(),center,paint)
            paint.color=if(ch==0)theme.accent else theme.textHighlight
            val lo=data.minimum[ch];val hi=data.maximum[ch]
            for(x in lo.indices) {
                val px=(x+.5f)*width/lo.size
                canvas.drawLine(px,center-hi[x]*amplitude,px,center-lo[x]*amplitude,paint)
            }
            paint.color=theme.textSecondary
            canvas.drawText("Channel ${ch+1}",6*density,top+lane*ch+14*density,paint)
        }
        paint.color=theme.textSecondary
        canvas.drawText("0 s",6*density,height-6*density,paint)
        val end="%.4f s".format(data.frames.toDouble()/config.sampleRate)
        canvas.drawText(end,(width-paint.measureText(end)-6*density).coerceAtLeast(0f),height-6*density,paint)
        paint.color=theme.accent;paint.strokeWidth=2*density
        val cursor=selectedFrame.toFloat()/data.frames.coerceAtLeast(1)*width
        canvas.drawLine(cursor,top,cursor,height-bottom,paint)
    }
}

/** Playback has its own native sink; all reads still happen on the document's serial worker. */
internal object RawPcmPlayback {
    fun play(context:Context, document:io.github.gretchycat.infiniscribe.HexDocument,
        config:RawAudioExplorer.Config,cancelled:java.util.concurrent.atomic.AtomicBoolean,progress:(Long)->Unit) {
        config.validate()
        require(config.offset<=document.length) {"Audio offset is outside the file"}
        val available=minOf(config.byteLength ?: (document.length-config.offset),document.length-config.offset)
        val byteCount=available/config.frameBytes*config.frameBytes
        require(byteCount>0) {"No complete sample frames"}
        val channel=if(config.channels==1)android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
        val minimum=android.media.AudioTrack.getMinBufferSize(config.sampleRate,channel,android.media.AudioFormat.ENCODING_PCM_16BIT)
        require(minimum>0) {"Android does not support this playback rate"}
        val manager=context.getSystemService(android.media.AudioManager::class.java)
        val focus=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { if(it<0)cancelled.set(true) }.build()
        require(manager.requestAudioFocus(focus)==android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {"Audio focus is unavailable"}
        var track:android.media.AudioTrack?=null
        try {
            track=android.media.AudioTrack.Builder().setAudioAttributes(focus.audioAttributes)
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(config.sampleRate).setChannelMask(channel)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(android.media.AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum,4096*config.channels)).build()
            require(track.state==android.media.AudioTrack.STATE_INITIALIZED) {"Cannot initialize audio output"}
            track.play()
            var at=0L
            while(at<byteCount && !cancelled.get()) {
                val count=minOf(1024L*config.frameBytes,byteCount-at).toInt()
                val pcm=RawAudioExplorer.decode(document.read(config.offset+at,count),config)
                var written=0
                while(written<pcm.samples.size && !cancelled.get()) {
                    val n=track.write(pcm.samples,written,pcm.samples.size-written,android.media.AudioTrack.WRITE_BLOCKING)
                    check(n>0) {"Audio output failed ($n)"};written+=n
                }
                at+=count;progress(config.offset+at)
            }
            // Let queued samples finish on ordinary completion; Stop discards them immediately.
            if(!cancelled.get()) {
                val frames=byteCount/config.frameBytes
                val deadline=android.os.SystemClock.uptimeMillis()+track.bufferSizeInFrames*1000L/config.sampleRate+500
                while(!cancelled.get() && (track.playbackHeadPosition.toLong() and 0xffffffffL)<frames && android.os.SystemClock.uptimeMillis()<deadline)
                    Thread.sleep(10)
            }
        } finally {runCatching {track?.pause();track?.flush();track?.release()};manager.abandonAudioFocusRequest(focus)}
    }
}
