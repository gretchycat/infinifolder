package com.infinifolder.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.BackgroundColorSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.infinifolder.preview.CanvasFonts
import com.infinifolder.audio.AnsiMusic
import com.infinifolder.audio.stream.PcmWaveWriter
import io.github.gretchycat.infiniscribe.DosTextDocument
import io.github.gretchycat.infiniscribe.DosTextDocuments
import io.github.gretchycat.infinistream.CancellationToken
import io.github.gretchycat.infinistream.readBytes
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.ceil

/** Native selectable text, a fixed DOS terminal width, and native scale/scroll gestures. */
class Cp437ViewerActivity : ViewerActivity() {
    private val worker=Executors.newSingleThreadExecutor()
    private var document:DosTextDocument?=null
    private lateinit var text:TextView
    private lateinit var vertical:ZoomScroll
    private lateinit var horizontal:HorizontalScrollView
    private lateinit var status:TextView
    private lateinit var play:Button
    private var basePixels=12f
    private var zoom=1f
    private var initialFit=false
    private var pendingScaleScroll:Pair<Float,Float>?=null
    private var waitingForScaleLayout=false
    private var player:MediaPlayer?=null
    private var focus:AudioFocusRequest?=null
    private var audioFile:File?=null
    private var musicTask:java.util.concurrent.Future<*>?=null
    private var name="DOS text"
    @Volatile private var musicGeneration=0

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val uri=intent.data ?: intent.getStringExtra("uri")?.let(android.net.Uri::parse)
            ?: intent.getStringExtra("extra_uri")?.let(android.net.Uri::parse)
        if(uri==null){finish();return}
        name=intent.getStringExtra("displayName") ?: intent.getStringExtra("extra_name") ?: uri.lastPathSegment ?: "DOS text"
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK)}
        val toolbar=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
        fun button(label:String, action:()->Unit)=Button(this).apply {this.text=label;setOnClickListener {action()}}
        toolbar.addView(button("‹"){finish()})
        status=TextView(this).apply {setTextColor(Color.LTGRAY);text=name;maxLines=2}
        toolbar.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        toolbar.addView(button("−"){scaleTo(zoom/1.25f)})
        toolbar.addView(button("+"){scaleTo(zoom*1.25f)})
        toolbar.addView(button("Fit"){scaleTo(1f)})
        layout.addView(toolbar)
        val actions=LinearLayout(this)
        play=button("Play music"){if(player!=null || musicTask?.isDone==false)stopMusic() else playMusic()}.apply {isEnabled=false}
        actions.addView(play)
        actions.addView(button("Text source"){
            startActivity(Intent(intent).setClass(this,TextViewerActivity::class.java).putExtra("forcePlainText",true))
        })
        layout.addView(actions)
        text=TextView(this).apply {
            typeface=CanvasFonts.cp437(this@Cp437ViewerActivity)
            setTextColor(0xffaaaaaa.toInt());setBackgroundColor(Color.BLACK)
            setTextIsSelectable(true);setHorizontallyScrolling(true);includeFontPadding=false
            setLineSpacing(0f,1f);textDirection=View.TEXT_DIRECTION_LTR
            gravity=android.view.Gravity.TOP or android.view.Gravity.LEFT
            breakStrategy=android.text.Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency=android.text.Layout.HYPHENATION_FREQUENCY_NONE
            letterSpacing=0f
            fontFeatureSettings="'liga' 0, 'kern' 0"
            paint.isAntiAlias=false
            setPadding(8,8,8,8)
        }
        horizontal=HorizontalScrollView(this).apply {
            isFillViewport=false
            addView(text,ViewGroup.LayoutParams(640,-2))
        }
        vertical=ZoomScroll(this).apply {addView(horizontal,ViewGroup.LayoutParams(-1,-2))}
        layout.addView(vertical,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(layout)
        vertical.addOnLayoutChangeListener {_,left,_,right,_,oldLeft,_,oldRight,_ ->
            if(right-left>0 && (!initialFit || right-left!=oldRight-oldLeft)) {
                basePixels=((right-left-text.paddingLeft-text.paddingRight).coerceAtLeast(80)/80f)*2f
                initialFit=true;applyScale()
            }
        }
        worker.submit {
            try {
                val cancellation=CancellationToken {isDestroyed || Thread.currentThread().isInterrupted}
                val bytes=MediaSourceAdapter.openDocumentSource(this,uri).use {readBytes(it,4*1024*1024,cancellation)}
                val parsed=DosTextDocuments.parse(bytes,cancellation=cancellation)
                cancellation.check()
                val styled=SpannableString(parsed.text)
                for(run in parsed.runs) {
                    styled.setSpan(ForegroundColorSpan(run.foreground),run.start,run.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    styled.setSpan(BackgroundColorSpan(run.background),run.start,run.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                runOnUiThread {
                    if(!isDestroyed) {document=parsed;text.text=styled;play.isEnabled=parsed.music.isNotEmpty();applyScale()}
                }
            } catch(error:Exception) {
                if(!isDestroyed)runOnUiThread {status.text=error.message ?: "Cannot open DOS text"}
            }
        }
    }

    private fun applyScale() {
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX,(basePixels*zoom).coerceAtLeast(1f))
        text.layoutParams=text.layoutParams.apply {width=ceil(text.paint.measureText("M")*80).toInt()+text.paddingLeft+text.paddingRight}
        status.text="$name · 80 columns · ${document?.rows ?: 0} rows · ${(zoom*100).toInt()}%"
    }
    private fun scaleTo(value:Float,focusX:Float=vertical.width/2f,focusY:Float=vertical.height/2f) {
        val next=value.coerceIn(maxOf(0.1f,1f/basePixels),24f)
        val ratio=next/zoom
        val prior=pendingScaleScroll
        val x=((prior?.first ?: horizontal.scrollX.toFloat())+focusX-text.paddingLeft)*ratio+text.paddingLeft-focusX
        val y=((prior?.second ?: vertical.scrollY.toFloat())+focusY-text.paddingTop)*ratio+text.paddingTop-focusY
        pendingScaleScroll=x to y
        zoom=next;applyScale()
        if(!waitingForScaleLayout) {
            waitingForScaleLayout=true
            val observer=text.viewTreeObserver
            observer.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw():Boolean {
                    if(observer.isAlive)observer.removeOnPreDrawListener(this)
                    waitingForScaleLayout=false
                    pendingScaleScroll?.let {(left,top)->horizontal.scrollTo(left.toInt().coerceAtLeast(0),0);vertical.scrollTo(0,top.toInt().coerceAtLeast(0))}
                    pendingScaleScroll=null
                    return true
                }
            })
        }
    }
    private inner class ZoomScroll(context:Context):ScrollView(context) {
        private var pinching=false
        private val detector=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScaleBegin(detector:ScaleGestureDetector)=true
            override fun onScale(detector:ScaleGestureDetector):Boolean {
                scaleTo(zoom*detector.scaleFactor,detector.focusX,detector.focusY);return true
            }
        })
        override fun dispatchTouchEvent(event:MotionEvent):Boolean {
            detector.onTouchEvent(event)
            if(event.pointerCount>1 || detector.isInProgress) {
                if(!pinching) {
                    val cancel=MotionEvent.obtain(event).apply {action=MotionEvent.ACTION_CANCEL}
                    super.dispatchTouchEvent(cancel);cancel.recycle();pinching=true
                }
                return true
            }
            if(pinching) {
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)pinching=false
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private fun stopMusic() {
        musicGeneration++
        musicTask?.cancel(true);musicTask=null
        player?.release();player=null
        focus?.let {(getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(it)};focus=null
        audioFile?.delete();audioFile=null
        if(::play.isInitialized){play.text="Play music";play.isEnabled=document?.music?.isNotEmpty()==true}
    }
    private fun playMusic() {
        val music=document?.music?.takeIf {it.isNotEmpty()} ?: return
        stopMusic();val generation=musicGeneration
        play.text="Stop music"
        musicTask=worker.submit {
            var file:File?=null
            try {
                file=File.createTempFile("ansi_music_",".wav",cacheDir)
                AnsiMusic.open(music).use {pcm ->
                    val required=pcm.format.totalFrames!!*2+44
                    if(cacheDir.usableSpace<required+1024*1024)throw IOException("Not enough cache space for ANSI music")
                    PcmWaveWriter.write(pcm,file,cancelled={isDestroyed || generation!=musicGeneration || Thread.currentThread().isInterrupted})
                }
                val ready=file
                runOnUiThread {
                    if(isDestroyed || generation!=musicGeneration){ready.delete();return@runOnUiThread}
                    try {
                        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                            .setOnAudioFocusChangeListener {if(it<0)stopMusic()}.build()
                        if((getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                            throw IOException("Audio focus unavailable")
                        focus=request;audioFile=ready
                        player=MediaPlayer().also {media ->
                            media.setAudioAttributes(attributes);media.setDataSource(ready.absolutePath)
                            media.setOnCompletionListener {stopMusic()}
                            media.setOnErrorListener {_,_,_->stopMusic();true}
                            media.setOnPreparedListener {if(generation==musicGeneration)it.start() else it.release()}
                            media.prepareAsync()
                        }
                    } catch(error:Exception){stopMusic();ready.delete();Toast.makeText(this,error.message,Toast.LENGTH_LONG).show()}
                }
                file=null
            } catch(error:Exception) {
                file?.delete()
                if(!isDestroyed && generation==musicGeneration)runOnUiThread {stopMusic();Toast.makeText(this,error.message,Toast.LENGTH_LONG).show()}
            }
        }
    }
    override fun onPause(){stopMusic();super.onPause()}
    override fun onDestroy(){stopMusic();worker.shutdownNow();super.onDestroy()}
}
