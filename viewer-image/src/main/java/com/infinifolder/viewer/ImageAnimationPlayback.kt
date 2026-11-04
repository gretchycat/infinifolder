package com.infinifolder.viewer

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.infinifolder.preview.BitmapAnimation
import com.infinifolder.preview.InteractiveBitmapAnimation
import com.infinifolder.preview.ImagePointerKind
import java.io.Closeable
import java.util.concurrent.Executors

/** One reusable frame buffer, one render in flight, and no storage access after decoding. */
internal class ImageAnimationPlayback(private val animation:BitmapAnimation,private val bitmap:Bitmap,
    initialElapsedNanos:Long=0,private val changed:()->Unit,private val failed:(Exception)->Unit):Closeable {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor{Thread(it,"image-animation")}
    private val pixels=IntArray(animation.width*animation.height)
    private var accumulated=initialElapsedNanos.coerceAtLeast(0)
    private var started=0L
    private var generation=0
    private var playing=false
    private var closed=false
    private var inFlight=false
    private var forceNext=true
    private var repaintPending=false
    private sealed interface Input {
        data class Pointer(val kind:ImagePointerKind,val x:Float,val y:Float):Input
        data class State(val name:String):Input
    }
    private val pendingInputs=java.util.ArrayDeque<Input>()
    val stateNames get()=(animation as? InteractiveBitmapAnimation)?.stateNames.orEmpty()
    val currentStateName get()=(animation as? InteractiveBitmapAnimation)?.currentStateName
    val durationNanos get()=animation.durationNanos
    val elapsedNanos get()=accumulated+if(playing)SystemClock.elapsedRealtimeNanos()-started else 0
    private val tick=Runnable{render()}
    private val pausedTick=Runnable{render(true)}

    fun play(){
        if(closed||playing)return
        main.removeCallbacks(pausedTick)
        playing=true;started=SystemClock.elapsedRealtimeNanos();generation++;forceNext=true
        render()
    }
    fun pause(cancelPending:Boolean=false){
        if(!playing&&!cancelPending)return
        if(playing)accumulated=elapsedNanos
        playing=false;generation++
        main.removeCallbacks(tick)
        main.removeCallbacks(pausedTick)
        if(cancelPending)repaintPending=false
    }
    fun seekTo(positionNanos:Long){
        if(closed||durationNanos<=0)return
        accumulated=positionNanos.coerceIn(0,durationNanos-1)
        started=SystemClock.elapsedRealtimeNanos();generation++;forceNext=true
        main.removeCallbacks(tick)
        main.removeCallbacks(pausedTick)
        if(inFlight)repaintPending=true else render(true)
    }
    fun pointerEvent(kind:ImagePointerKind,x:Float,y:Float){
        if(closed || animation !is InteractiveBitmapAnimation)return
        if(kind==ImagePointerKind.MOVE && (pendingInputs.peekLast() as? Input.Pointer)?.kind==ImagePointerKind.MOVE)pendingInputs.removeLast()
        if(pendingInputs.size>=64)pendingInputs.clear()
        pendingInputs.add(Input.Pointer(kind,x,y));requestInputFrame()
    }
    fun selectState(name:String){
        if(closed || name !in stateNames)return
        pendingInputs.add(Input.State(name));requestInputFrame()
    }
    private fun requestInputFrame(){
        generation++;forceNext=true
        main.removeCallbacks(tick);main.removeCallbacks(pausedTick)
        if(inFlight)repaintPending=true else render(true)
    }
    private fun render(pausedFrame:Boolean=false){
        if(closed||(!playing&&!pausedFrame)||inFlight)return
        inFlight=true
        val request=generation;val elapsed=elapsedNanos.let{if(durationNanos>0)it%durationNanos else it};val force=forceNext
        val inputs=pendingInputs.toList();pendingInputs.clear()
        forceNext=false
        worker.execute {
            var error:Exception?=null
            val updated=try{
                (animation as? InteractiveBitmapAnimation)?.let{interactive->
                    for(input in inputs)when(input){
                        is Input.Pointer->interactive.pointerEvent(input.kind,input.x,input.y)
                        is Input.State->interactive.selectState(input.name)
                    }
                }
                animation.render(elapsed,pixels,force)
            }catch(problem:Exception){error=problem;false}
            main.post {
                inFlight=false
                if(closed)return@post
                if(request==generation&&(playing||pausedFrame)){
                    if(error!=null){pause();failed(checkNotNull(error));return@post}
                    if(updated&&!bitmap.isRecycled){
                        bitmap.setPixels(pixels,0,animation.width,0,0,animation.width,animation.height)
                        changed()
                    }
                }
                if(repaintPending){repaintPending=false;render(true)}
                else if(request==generation && pausedFrame && !playing && animation.needsMoreRendering)main.postDelayed(pausedTick,1)
                else if(playing && (animation.needsContinuousFrames || animation.needsMoreRendering))main.postDelayed(tick,33)
            }
        }
    }
    override fun close(){
        if(closed)return
        pause(cancelPending=true);closed=true;pendingInputs.clear();main.removeCallbacksAndMessages(null);worker.shutdownNow()
    }
}
