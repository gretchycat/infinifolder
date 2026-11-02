package com.infinifolder.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.infinifolder.preview.IconBitmapUsability
import java.io.File
import java.security.MessageDigest

/** Disposable local render cache. Read/write only on theme-resolution workers, never during drawing. */
internal object ThemeIconDiskCache {
    private const val MAX_BYTES=48L*1024*1024
    private const val MAX_FILES=2048
    private const val MAX_ENTRY=4L*1024*1024
    private val lock=Any()
    private val writer=java.util.concurrent.ThreadPoolExecutor(1,1,30,java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(128),java.util.concurrent.ThreadFactory {task->Thread(task,"ThemeIconCacheWrite").apply {isDaemon=true}})
    private val pending=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun enqueueWrite(directory:File,key:String,bitmap:Bitmap) {
        if(!pending.add(key))return
        try {writer.execute {try {write(directory,key,bitmap)}finally {pending.remove(key)}}}
        catch(_:java.util.concurrent.RejectedExecutionException){pending.remove(key)}
    }
    fun read(directory:File,key:String):Bitmap? {
        return runCatching {
            val file=entry(directory,key)
            if(!file.isFile)return null
            if(file.length() !in 1..MAX_ENTRY){file.delete();return null}
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(file.path,bounds)
            if(bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048){file.delete();return null}
            val bitmap=BitmapFactory.decodeFile(file.path) ?: run {file.delete();return null}
            if(!IconBitmapUsability.visible(bitmap)){bitmap.recycle();file.delete();return null}
            file.setLastModified(System.currentTimeMillis())
            bitmap
        }.getOrNull()
    }
    private fun write(directory:File,key:String,bitmap:Bitmap) {
        runCatching {
            if(bitmap.width>2048 || bitmap.height>2048)return
            if(!directory.isDirectory && !directory.mkdirs())return
            val temp=File.createTempFile("render-",".tmp",directory)
            try {
                temp.outputStream().use {out->check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));out.flush()}
                if(temp.length() !in 1..MAX_ENTRY)return
                synchronized(lock) {
                    if(!temp.renameTo(entry(directory,key)))return
                    trim(directory)
                }
            } finally {temp.delete()}
        }
        Unit
    }
    private fun entry(directory:File,key:String):File {
        val hash=MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        return File(directory,"$hash.png")
    }
    private fun trim(directory:File) {
        val files=directory.listFiles()?.filter {it.isFile && it.extension=="png"}?.sortedBy {it.lastModified()} ?: return
        var total=files.sumOf {it.length()};var count=files.size
        for(file in files) {
            if(total<=MAX_BYTES && count<=MAX_FILES)break
            val size=file.length();if(file.delete()){total-=size;count--}
        }
    }
}
