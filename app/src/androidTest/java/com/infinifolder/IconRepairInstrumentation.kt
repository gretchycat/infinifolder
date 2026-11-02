package com.infinifolder

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.infinifolder.archive.ArchiveUri
import com.infinifolder.model.*
import com.infinifolder.preview.*
import com.infinifolder.shortcut.ShortcutInfo
import com.infinifolder.storage.*
import com.infinifolder.theme.IconThemeManager
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Native device acceptance runner. It exercises MainActivity's real list/grid icon assignments. */
class IconRepairInstrumentation : Instrumentation() {
    private lateinit var activity:Activity
    private lateinit var fixture:File
    private val completed=mutableListOf<String>()
    override fun onCreate(arguments:Bundle?) {super.onCreate(arguments);start()}
    private fun onUi(action:()->Unit)=runOnMainSync(action)
    private fun waitFor(label:String,condition:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+20000
        while(SystemClock.uptimeMillis()<deadline) {if(condition())return;SystemClock.sleep(80)}
        error("Timed out: $label")
    }
    private fun png(file:File,color:Int) {file.parentFile?.mkdirs();val bitmap=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color)
        file.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()}
    private fun zip(file:File,entries:Map<String,ByteArray>) {
        ZipOutputStream(file.outputStream()).use {out ->entries.forEach {(name,bytes)->out.putNextEntry(ZipEntry(name));out.write(bytes);out.closeEntry()}}
    }
    private fun navigate(uri:Uri) {onUi {MainActivity::class.java.getDeclaredMethod("navigate",Uri::class.java,Boolean::class.javaPrimitiveType,String::class.java,Boolean::class.javaPrimitiveType)
        .apply {isAccessible=true}.invoke(activity,uri,false,"Icon acceptance fixtures",false)}}
    @Suppress("UNCHECKED_CAST") private fun icon(file:File):ResourceIconResolver.Icon {
        var result:ResourceIconResolver.Icon?=null
        waitFor("displayed icon ${file.name}") {
            onUi {
                val views=MainActivity::class.java.getDeclaredField("entryIconViews").apply {isAccessible=true}.get(activity) as Map<Uri,FrameLayout>
                val frame=views[Uri.fromFile(file)]
                frame?.requestRectangleOnScreen(android.graphics.Rect(0,0,frame.width,frame.height),true)
                result=frame?.tag as? ResourceIconResolver.Icon
            };result!=null
        }
        return result!!
    }
    private fun refresh() {onUi {MainActivity::class.java.getDeclaredMethod("refreshCurrentLocation").apply {isAccessible=true}.invoke(activity)}}
    private fun theme(directory:File,name:String,color:Int):String {
        val root=File(directory,name).apply {mkdirs()};val icons=File(root,"48x48/mimetypes").apply {mkdirs()}
        root.resolve("index.theme").writeText("""[Icon Theme]
Name=$name
Directories=48x48/mimetypes
[48x48/mimetypes]
Size=48
Type=Fixed
Context=MimeTypes
""")
        for(key in listOf("image-png","application-pdf","text-plain","folder-archive","folder","application-vnd.openxmlformats-officedocument.wordprocessingml.document","application-epub+zip"))png(File(icons,"$key.png"),color)
        return root.name
    }
    private fun snapshotFile(file:File)=file.takeIf {it.exists()}?.readBytes()
    private fun restore(file:File,bytes:ByteArray?) {if(bytes==null)file.delete() else {file.parentFile?.mkdirs();file.writeBytes(bytes)}}
    override fun onStart() {
        sendStatus(1,Bundle().apply {putString("class",this@IconRepairInstrumentation.javaClass.name);putString("test","listingToIconRepairMatrix");putInt("current",1);putInt("numtests",1)})
        val context=targetContext
        fixture=File(context.filesDir,"icon-acceptance-${UUID.randomUUID()}").apply {mkdirs()}
        val registryFile=File(context.filesDir,"icon_registry.json");val savedRegistry=snapshotFile(registryFile)
        val legacyIndex=File(context.filesDir,"mime_icons/index.json");val savedLegacy=snapshotFile(legacyIndex)
        val preferences=context.getSharedPreferences("browser",0)
        val originalPreferences=preferences.all.toMap()
        val themes=File(context.filesDir,"icon_themes")
        val token=UUID.randomUUID().toString()
        val themeA=File(themes,"icon-acceptance-A-$token");val themeB=File(themes,"icon-acceptance-B-$token")
        var failure:Throwable?=null
        try {
            // Use a temporary registry/index, restoring byte-for-byte in finally.
            restore(registryFile,null);restore(legacyIndex,"{}".toByteArray())
            val registry=IconRegistry.at(context.filesDir)
            png(File(fixture,"00-cover/cover.png"),Color.RED);png(File(fixture,"00-cover/.icon.png"),Color.BLUE)
            File(fixture,"01-corrupt").mkdirs();File(fixture,"01-corrupt/cover.png").writeText("not a PNG");png(File(fixture,"01-corrupt/.icon.png"),Color.BLUE)
            png(File(fixture,"image.png"),Color.RED);File(fixture,"image.png").copyTo(File(fixture,"extensionless"))
            File(fixture,"notes.txt").writeText("plain text")
            File(fixture,"document.pdf").writeText("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n%%EOF")
            zip(File(fixture,"archive.zip"),mapOf("inside.txt" to "real ZIP payload".toByteArray()))
            zip(File(fixture,"book.docx"),mapOf("[Content_Types].xml" to "<Types/>".toByteArray(),"word/document.xml" to "<document/>".toByteArray()))
            zip(File(fixture,"known.txt"),mapOf("inside.txt" to "known extension container".toByteArray()))
            File(fixture,"faux.zip").writeText("PKxx invalid archive")
            File(fixture,"art.ans").writeText("\u001b[31m██ANSI UTF-8\u001b[0m\r\n")
            File(fixture,"magnet.iflink").writeText(ShortcutInfo(targetUri="magnet:?xt=urn:btih:0123456789012345678901234567890123456789").toJson())
            File(fixture,"broken.iflink").writeText("not link JSON")
            val override=File(fixture,"uri-override.png");png(override,Color.MAGENTA)
            registry.save(IconRegistry.UriRule("acceptance-exact",exactUri="https://example.test/private?secret=never-request",overrideIcon=override.relativeTo(context.filesDir).path))
            File(fixture,"web.iflink").writeText(ShortcutInfo(targetUri="https://example.test/private?secret=never-request").toJson())
            val a=theme(themes,themeA.name,Color.GREEN);val b=theme(themes,themeB.name,Color.CYAN)
            preferences.edit().putString("active_icon_theme",a).putBoolean("grid",true).putBoolean("tree_visible",false).commit()
            activity=startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            navigate(Uri.fromFile(fixture))
            check(icon(File(fixture,"00-cover")).source=="directory-cover")
            check(icon(File(fixture,"00-cover")).bitmap!!.getPixel(8,8)==Color.RED)
            check(icon(File(fixture,"01-corrupt")).source=="directory-icon")
            check(icon(File(fixture,"01-corrupt")).bitmap!!.getPixel(8,8)==Color.BLUE)
            completed+="real-listing-cover-and-corrupt-fallthrough"
            val initial=icon(File(fixture,"image.png"));check(initial.source=="theme_mime");check(initial.bitmap!!.getPixel(8,8)==Color.GREEN)
            check(icon(File(fixture,"extensionless")).mimeType=="image/png")
            completed+="default-image-mode-and-extensionless-magic"
            val editor=startActivitySync(Intent(context,IconRegistryEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onUi {IconRegistryEditorActivity::class.java.getDeclaredMethod("editMime",String::class.java).apply {isAccessible=true}.invoke(editor,"image/png")}
            waitFor("real MIME checkbox") {uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Use MIME icon instead of content thumbnail")?.isNotEmpty()==true}
            val checkbox=uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Use MIME icon instead of content thumbnail").first {it.isCheckable}
            check(checkbox.isChecked);check(checkbox.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            val save=uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Save").first {it.text?.toString()=="Save"}
            check(save.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            onUi {editor.finish()};refresh()
            waitFor("thumbnail toggle reflected in listing") {icon(File(fixture,"image.png")).source=="thumbnail"}
            check(icon(File(fixture,"image.png")).bitmap!!.getPixel(8,8)==Color.RED)
            check(!IconRegistry.at(context.filesDir).mime("image/png").overrideImageIcon)
            completed+="image-mode-persisted-and-consumed-by-listing"
            registry.save(registry.mime("image/png").copy(overrideImageIcon=false,overrideIcon=override.relativeTo(context.filesDir).path));refresh()
            waitFor("thumbnail wins over user MIME icon in thumbnail mode") {icon(File(fixture,"image.png")).source=="thumbnail"}
            File(fixture,"corrupt.png").writeText("not an image");refresh()
            check(icon(File(fixture,"corrupt.png")).source=="mime_override")
            registry.save(registry.mime("image/png").copy(overrideImageIcon=true));refresh()
            waitFor("MIME override") {icon(File(fixture,"image.png")).source=="mime_override"}
            IconThemeManager(context).setActiveTheme(b);refresh()
            check(icon(File(fixture,"image.png")).bitmap!!.getPixel(8,8)==Color.MAGENTA)
            registry.save(registry.mime("image/png").copy(overrideIcon=null));refresh()
            waitFor("theme B") {icon(File(fixture,"image.png")).source=="theme_mime" && icon(File(fixture,"image.png")).bitmap!!.getPixel(8,8)==Color.CYAN}
            completed+="theme-change-and-persistent-MIME-override"
            check(icon(File(fixture,"document.pdf")).mimeType=="application/pdf")
            check(icon(File(fixture,"book.docx")).mimeType.contains("wordprocessingml"))
            check(icon(File(fixture,"known.txt")).mimeType=="text/plain")
            check(icon(File(fixture,"magnet.iflink")).source=="scheme")
            check(icon(File(fixture,"web.iflink")).source=="uri-rule")
            check(icon(File(fixture,"broken.iflink")).source=="broken-link")
            completed+="MIME-package-identity-and-real-iflink-files"
            val runtime=StorageRuntime.get(context)
            val service=ResourceIconResolver(context)
            for(name in listOf("archive.zip","book.docx","known.txt")) {
                val entry=runtime.local.entry(File(fixture,name));check(service.archives.inspect(entry).browsable)
                val uri=ArchiveUri.toArchiveUri(entry.uri);val root=runtime.archives.refForUri(uri)!!
                val rows=(runtime.archives.list(root) as ProviderResult.Success).value
                check(rows.isNotEmpty())
                val file=rows.firstOrNull {!it.metadata.isDirectory}
                if(file!=null)check((runtime.archives.openRead(file.ref) as ProviderResult.Success).value.use {it.readBytes()}.isNotEmpty())
            }
            check(!service.archives.inspect(runtime.local.entry(File(fixture,"faux.zip"))).browsable)
            service.close();completed+="real-archive-provider-list-and-open"
            // A real archive-backed directory exercises the non-local storage abstraction.
            val bytes=File(fixture,"image.png").readBytes()
            val nonlocal=File(fixture,"nonlocal.zip");zip(nonlocal,mapOf("cover.png" to bytes,".icon.png" to override.readBytes()))
            val uri=ArchiveUri.toArchiveUri(Uri.fromFile(nonlocal));val entry=ResourceEntry(runtime.archives.refForUri(uri)!!,ResourceMetadata("nonlocal","vnd.android.document/directory",null,null,true),uri)
            val remoteService=ResourceIconResolver(context);check(remoteService.resolve(entry,64).source=="directory-cover");remoteService.close()
            completed+="archive-backed-directory-cover"
            onUi {MainActivity::class.java.getDeclaredField("grid").apply {isAccessible=true}.setBoolean(activity,false)};refresh()
            waitFor("list mode") {icon(File(fixture,"document.pdf")).mimeType=="application/pdf"}
            completed+="same-identity-in-real-grid-and-list"
            // Invoke the real long-press handler and click the actual menu action.
            val zipEntry=runtime.local.entry(File(fixture,"archive.zip"))
            onUi {MainActivity::class.java.getDeclaredMethod("showEntryMenu",ResourceEntry::class.java).apply {isAccessible=true}.invoke(activity,zipEntry)}
            waitFor("Browse archive menu action") {uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Browse archive")?.any {it.text?.toString()=="Browse archive"}==true}
            val action=uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Browse archive").first {it.text?.toString()=="Browse archive"}
            var clickable=action
            while(!clickable.isClickable && clickable.parent!=null)clickable=clickable.parent
            check(clickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("archive action actually navigates") {
                var uri:Uri?=null;onUi {uri=MainActivity::class.java.getDeclaredField("current").apply {isAccessible=true}.get(activity) as? Uri}
                uri?.scheme=="archive"
            }
            completed+="actual-long-press-browse-action"
            val screenshots=File(context.getExternalFilesDir(null),"icon-acceptance").apply {mkdirs()}
            uiAutomation.takeScreenshot()?.let {image->File(screenshots,"listing.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}}
        } catch(error:Throwable) {failure=error}
        finally {
            if(::activity.isInitialized)onUi {activity.finish()}
            restore(registryFile,savedRegistry);restore(legacyIndex,savedLegacy)
            val edit=preferences.edit().clear()
            originalPreferences.forEach {(key,value)->when(value) {
                is String->edit.putString(key,value);is Boolean->edit.putBoolean(key,value);is Int->edit.putInt(key,value)
                is Long->edit.putLong(key,value);is Float->edit.putFloat(key,value)
                is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
            }};edit.commit()
            fixture.deleteRecursively();themeA.deleteRecursively();themeB.deleteRecursively()
        }
        sendStatus(if(failure==null)0 else -2,Bundle().apply {
            putString("class",this@IconRepairInstrumentation.javaClass.name);putString("test","listingToIconRepairMatrix")
            putInt("current",1);putInt("numtests",1);putString("stack",failure?.stackTraceToString())
        })
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
            putString("completed",completed.joinToString("\n"));putString("failure",failure?.stackTraceToString())
        })
    }
}
