package com.infinifolder.preview

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.infinifolder.FileTypeIcons
import com.infinifolder.LinkIcons
import com.infinifolder.model.ResourceMetadata
import android.widget.*
import com.infinifolder.theme.IconThemeManager
import com.infinifolder.theme.ThemeManager
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Edits the same registry consumed by listing, tree and floating-container artwork. */
class IconRegistryEditorActivity : Activity() {
    private lateinit var registry: IconRegistry
    private lateinit var custom: CustomIconStore
    private lateinit var theme: IconThemeManager
    private val worker=Executors.newSingleThreadExecutor()
    private var pickMime:String?=null
    private var pickRule:String?=null
    private var uris=false
    private var listGeneration=0
    private var mimeListState=Bundle()
    private var uriListState=Bundle()
    private var displayedUris=false
    private var displayedList:ListView?=null
    private var displayedFilter:EditText?=null
    private fun rememberList() {
        val list=displayedList ?: return
        val saved=if(displayedUris)uriListState else mimeListState
        saved.putString("filter",displayedFilter?.text?.toString().orEmpty())
        saved.putParcelable("scroll",list.onSaveInstanceState())
    }
    private val previewCache=object:android.util.LruCache<String,Bitmap>(2*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount
    }
    private data class Artwork(val theme:Bitmap,val override:Bitmap?,val current:Bitmap)
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun visible(bitmap:Bitmap?)=bitmap?.takeIf {IconBitmapUsability.visible(it)}
    private fun mimeArtwork(record:IconRegistry.MimeRecord,size:Int):Artwork {
        val fallback=if(record.iconName=="folder-archive")FileTypeIcons.folder(size,true)
            else FileTypeIcons.bitmap(ResourceMetadata("",record.mimeType,null,null,false),size,record.mimeType)
        val default=visible(theme.resolveLogicalIcon(record.iconName,size))
            ?: visible(theme.resolveMimeDefaults(record.mimeType,size))
            ?: visible(theme.resolveMimeCategory(record.mimeType,size)) ?: fallback
        val override=visible(IconOverrideAssets.decode(filesDir,custom,ThumbnailDecoder(contentResolver),record.overrideIcon,record.mimeType,size))
        return Artwork(default,override,override ?: default)
    }
    private fun ruleArtwork(rule:IconRegistry.UriRule,size:Int):Artwork {
        val scheme=rule.exactUri?.let {runCatching {Uri.parse(it).scheme}.getOrNull()} ?: rule.scheme
        val fallback=LinkIcons.protocol(scheme,size)
        val default=visible(theme.resolveLogicalIcon(rule.iconName,size)) ?: visible(theme.resolveSchemeIcon(scheme,size)) ?: fallback
        val override=visible(IconOverrideAssets.decode(filesDir,custom,ThumbnailDecoder(contentResolver),rule.overrideIcon,null,size))
        return Artwork(default,override,override ?: default)
    }
    private fun previewCell(parent:LinearLayout,label:String):Pair<ImageView,TextView> {
        val cell=column()
        val icon=ImageView(this).apply {contentDescription=label;scaleType=ImageView.ScaleType.FIT_CENTER}
        cell.addView(icon,LinearLayout.LayoutParams(-1,dp(64)))
        cell.addView(TextView(this).apply {text=label;gravity=android.view.Gravity.CENTER;setTextColor(ThemeManager.activeTheme.textPrimary)})
        val status=TextView(this).apply {gravity=android.view.Gravity.CENTER;setTextColor(ThemeManager.activeTheme.textPrimary)}
        cell.addView(status);parent.addView(cell,LinearLayout.LayoutParams(0,-2,1f))
        return icon to status
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        ThemeManager.init(applicationContext)
        registry=IconRegistry.at(filesDir);custom=CustomIconStore(filesDir);theme=IconThemeManager(this)
        pickMime=state?.getString("pickMime");pickRule=state?.getString("pickRule")
        uris=state?.getBoolean("uris") ?: false
        mimeListState=Bundle(state?.getBundle("mimeListState") ?: Bundle())
        uriListState=Bundle(state?.getBundle("uriListState") ?: Bundle())
        showList()
        intent.getStringExtra("mime")?.let(::editMime)
    }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(16,8,16,8) }
    private fun field(parent:LinearLayout,label:String,value:String):EditText {
        parent.addView(TextView(this).apply { text=label;setTextColor(ThemeManager.activeTheme.textPrimary) })
        return EditText(this).apply { setText(value);setTextColor(ThemeManager.activeTheme.textPrimary);setSingleLine();parent.addView(this) }
    }
    private fun message(error:Throwable) { Toast.makeText(this,error.message ?: "Cannot save setting",Toast.LENGTH_LONG).show() }
    private fun showList() {
        rememberList()
        val saved=if(uris)uriListState else mimeListState
        val generation=++listGeneration
        val showingUris=uris
        val root=column()
        root.setBackgroundColor(ThemeManager.activeTheme.background)
        root.setOnApplyWindowInsetsListener { view,insets ->
            val safe=if(android.os.Build.VERSION.SDK_INT>=30)insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()) else null
            view.setPadding(16+(safe?.left ?: insets.systemWindowInsetLeft),8+(safe?.top ?: insets.systemWindowInsetTop),
                16+(safe?.right ?: insets.systemWindowInsetRight),8+(safe?.bottom ?: insets.systemWindowInsetBottom));insets
        }
        val toolbar=LinearLayout(this)
        toolbar.addView(Button(this).apply { text="Back";setOnClickListener { finish() } })
        toolbar.addView(Button(this).apply { text=if(uris)"MIME types" else "URI rules";setOnClickListener { uris=!uris;showList() } })
        toolbar.addView(Button(this).apply { text="Add";setOnClickListener {
            if(uris)editRule(IconRegistry.UriRule(UUID.randomUUID().toString())) else {
                val input=EditText(this@IconRegistryEditorActivity)
                AlertDialog.Builder(this@IconRegistryEditorActivity).setTitle("MIME type").setView(input)
                    .setNegativeButton("Cancel",null).setPositiveButton("Edit") { _,_->editMime(input.text.toString().trim()) }.show()
            }
        } })
        root.addView(toolbar)
        root.addView(TextView(this).apply { text=if(uris)"URI icon rules · exact URI wins, then the most specific rule" else "MIME icons and extension associations";setTextColor(ThemeManager.activeTheme.textPrimary) })
        val search=EditText(this).apply { hint="Filter";setSingleLine() };root.addView(search)
        val rules=if(uris)registry.uriRules() else emptyList()
        val values=if(uris)rules.mapIndexed {index,rule -> (rule.exactUri ?: "${rule.scheme}://${rule.authority}${rule.pathPrefix}")+" · ${index+1}"} else registry.mimeTypes()
        val savedFilter=saved.getString("filter").orEmpty()
        search.setText(savedFilter)
        var filtered=values.filter {it.contains(savedFilter,true)}
        val revision="${registry.revision}:${theme.revision()}:${custom.revision}"
        val adapter=object:BaseAdapter() {
            override fun getCount()=filtered.size
            override fun getItem(position:Int)=filtered[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                val row=convertView as? LinearLayout ?: LinearLayout(this@IconRegistryEditorActivity).apply {
                    gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(6),dp(8),dp(6))
                    addView(ImageView(this@IconRegistryEditorActivity).apply {scaleType=ImageView.ScaleType.FIT_CENTER},LinearLayout.LayoutParams(dp(48),dp(48)))
                    addView(TextView(this@IconRegistryEditorActivity).apply {setPadding(dp(12),0,0,0);setTextColor(ThemeManager.activeTheme.textPrimary)},LinearLayout.LayoutParams(0,-2,1f))
                }
                val value=getItem(position)
                val icon=row.getChildAt(0) as ImageView
                (icon.tag as? java.util.concurrent.Future<*>)?.cancel(true)
                icon.setImageBitmap(null);icon.contentDescription="Current icon for $value"
                (row.getChildAt(1) as TextView).text=value
                row.tag=value
                val key="$revision:$value"
                val cached=previewCache.get(key)
                if(cached!=null)icon.setImageBitmap(cached) else icon.tag=worker.submit {
                    val bitmap=runCatching {
                        if(showingUris)ruleArtwork(rules[values.indexOf(value)],dp(48)).current else mimeArtwork(registry.mime(value),dp(48)).current
                    }.getOrNull()
                    if(bitmap!=null && !Thread.currentThread().isInterrupted) {
                        previewCache.put(key,bitmap)
                        runOnUiThread {if(!isDestroyed && listGeneration==generation && row.tag==value)icon.setImageBitmap(bitmap)}
                    }
                }
                return row
            }
        }
        val list=ListView(this).apply { this.adapter=adapter;setOnItemClickListener { _,_,position,_->
            val selected=adapter.getItem(position)
            if(uris)rules.getOrNull(values.indexOf(selected))?.let(::editRule) else editMime(selected)
        } }
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                filtered=values.filter {it.contains(s?.toString().orEmpty(),true)};adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        displayedUris=showingUris;displayedList=list;displayedFilter=search
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);root.requestApplyInsets()
        saved.getParcelable<android.os.Parcelable>("scroll")?.let {list.onRestoreInstanceState(it)}
    }
    private fun editMime(raw:String) {
        rememberList()
        val record=registry.mime(raw)
        val body=column()
        body.addView(TextView(this).apply {text=record.mimeType})
        val extensions=field(body,"Extensions (comma separated; multi-part extensions allowed)",record.extensions.sorted().joinToString(", "))
        val logical=field(body,"Theme icon name",record.iconName)
        val image=CheckBox(this).apply { text="Contents can be rendered as an image";isChecked=record.isImage };body.addView(image)
        val mode=CheckBox(this).apply { text="Use MIME icon instead of content thumbnail";isChecked=record.overrideImageIcon;isEnabled=image.isChecked };body.addView(mode)
        fun draft()=record.copy(extensions=extensions.text.toString().split(',').map {it.trim().trimStart('.')}.filter {it.isNotEmpty()}.toSet(),
            iconName=logical.text.toString().trim(),isImage=image.isChecked,overrideImageIcon=mode.isChecked)
        val preview=LinearLayout(this);body.addView(preview)
        val (currentView,currentStatus)=previewCell(preview,"Current icon")
        val (defaultView,_)=previewCell(preview,"Theme default")
        val (overrideView,overrideStatus)=previewCell(preview,"User override")
        var previewGeneration=0
        fun preview() {
            val current=draft();val generation=++previewGeneration
            currentStatus.text=if(current.isImage && !current.overrideImageIcon)"Per-file thumbnail; fallback shown" else "MIME icon"
            worker.execute {
                if(generation!=previewGeneration)return@execute
                val artwork=runCatching {mimeArtwork(current,dp(64))}.getOrNull()
                runOnUiThread {if(!isDestroyed && generation==previewGeneration && artwork!=null) {
                    currentView.setImageBitmap(artwork.current);defaultView.setImageBitmap(artwork.theme)
                    overrideView.setImageBitmap(artwork.override);overrideStatus.text=if(artwork.override==null)"None" else "Selected"
                }}
            }
        }
        image.setOnCheckedChangeListener { _,checked->mode.isEnabled=checked;preview() }
        mode.setOnCheckedChangeListener { _,_->preview() }
        logical.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {preview()}
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        val dialog=AlertDialog.Builder(this).setTitle("MIME icon settings").setView(ScrollView(this).apply {addView(body)})
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        body.addView(Button(this).apply {text="Choose override icon";setOnClickListener {
            runCatching {registry.save(draft());pickMime=record.mimeType;pickRule=null;dialog.dismiss();pickImage()}.onFailure(::message)
        }})
        body.addView(Button(this).apply {text="Clear override icon";setOnClickListener {
            runCatching {custom.clearMimeIcon(record.mimeType);registry.save(draft().copy(overrideIcon=null));dialog.dismiss();showList();editMime(record.mimeType)}.onFailure(::message)
        }})
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {registry.save(draft());dialog.dismiss();showList()}.onFailure(::message)
        }}
        dialog.show();preview()
    }
    private fun editRule(rule:IconRegistry.UriRule) {
        rememberList()
        val body=column()
        val exact=field(body,"Exact URI (leave empty for a rule)",rule.exactUri.orEmpty())
        val scheme=field(body,"Scheme",rule.scheme)
        val authority=field(body,"Authority (host and optional port)",rule.authority)
        val path=field(body,"Path prefix (case and escaping are significant)",rule.pathPrefix)
        val logical=field(body,"Theme icon name",rule.iconName)
        fun draft():IconRegistry.UriRule {
            val uri=exact.text.toString().trim().takeIf {it.isNotEmpty()}?.let(IconRegistry::normalizeUri)
            return rule.copy(exactUri=uri,scheme=scheme.text.toString().trim(),authority=authority.text.toString().trim(),
                pathPrefix=path.text.toString(),iconName=logical.text.toString().trim())
        }
        val preview=LinearLayout(this);body.addView(preview)
        val (currentView,_)=previewCell(preview,"Current rule icon")
        val (defaultView,_)=previewCell(preview,"Theme default")
        val (overrideView,overrideStatus)=previewCell(preview,"User override")
        var previewGeneration=0
        fun refreshPreview() {
            val current=runCatching {draft()}.getOrNull() ?: return
            val generation=++previewGeneration
            worker.execute {
                if(generation!=previewGeneration)return@execute
                val artwork=runCatching {ruleArtwork(current,dp(64))}.getOrNull()
                runOnUiThread {if(!isDestroyed && generation==previewGeneration && artwork!=null) {
                    currentView.setImageBitmap(artwork.current);defaultView.setImageBitmap(artwork.theme)
                    overrideView.setImageBitmap(artwork.override);overrideStatus.text=if(artwork.override==null)"None" else "Selected"
                }}
            }
        }
        for(input in listOf(exact,scheme,logical))input.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {refreshPreview()}
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        val dialog=AlertDialog.Builder(this).setTitle("URI icon rule").setView(ScrollView(this).apply {addView(body)})
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        body.addView(Button(this).apply {text="Choose override icon";setOnClickListener {
            runCatching {registry.save(draft());pickRule=rule.id;pickMime=null;dialog.dismiss();pickImage()}.onFailure(::message)
        }})
        body.addView(Button(this).apply {text="Clear override icon";setOnClickListener {
            runCatching {registry.save(draft().copy(overrideIcon=null));dialog.dismiss();showList();editRule(registry.uriRules().first {it.id==rule.id})}.onFailure(::message)
        }})
        body.addView(Button(this).apply {text="Delete rule";setOnClickListener {registry.removeUriRule(rule.id);dialog.dismiss();showList()}})
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {registry.save(draft());dialog.dismiss();showList()}.onFailure(::message)
        }}
        dialog.show();refreshPreview()
    }
    private fun pickImage() {startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},71)}
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=71 || resultCode!=RESULT_OK)return
        val uri=data?.data ?: return
        val mime=pickMime;val ruleId=pickRule
        worker.execute {
            val result=runCatching {
                val bitmap=ThumbnailDecoder(contentResolver).decode(uri,256) ?: error("Unsupported or unreadable image")
                val bytes=CustomIconStore.encodeBitmapToPngBytes(bitmap) ?: error("Cannot encode icon")
                if(mime!=null) {custom.setMimeIcon(mime,"png",bytes,bitmap);registry.save(registry.mime(mime).copy(overrideIcon="mime:$mime"))}
                else if(ruleId!=null) {
                    val directory=File(filesDir,"uri_icons").apply {mkdirs()}
                    val file=File(directory,"${UUID.randomUUID()}.png");file.writeBytes(bytes)
                    registry.uriRules().firstOrNull {it.id==ruleId}?.let {registry.save(it.copy(overrideIcon="uri_icons/${file.name}"))}
                }
            }
            runOnUiThread {if(!isDestroyed){
                result.onFailure(::message);showList()
                if(mime!=null)editMime(mime)
                else if(ruleId!=null)registry.uriRules().firstOrNull {it.id==ruleId}?.let(::editRule)
            }}
        }
    }
    override fun onSaveInstanceState(out:Bundle) {
        rememberList()
        super.onSaveInstanceState(out)
        out.putString("pickMime",pickMime);out.putString("pickRule",pickRule);out.putBoolean("uris",uris)
        out.putBundle("mimeListState",Bundle(mimeListState));out.putBundle("uriListState",Bundle(uriListState))
    }
    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
}
