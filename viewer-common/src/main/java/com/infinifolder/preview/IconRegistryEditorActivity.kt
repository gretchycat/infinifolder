package com.infinifolder.preview

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
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
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        ThemeManager.init(applicationContext)
        registry=IconRegistry.at(filesDir);custom=CustomIconStore(filesDir);theme=IconThemeManager(this)
        pickMime=state?.getString("pickMime");pickRule=state?.getString("pickRule")
        uris=state?.getBoolean("uris") ?: false
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
        val adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,values.toMutableList())
        val list=ListView(this).apply { this.adapter=adapter;setOnItemClickListener { _,_,position,_->
            val selected=adapter.getItem(position) ?: return@setOnItemClickListener
            if(uris)rules.getOrNull(values.indexOf(selected))?.let(::editRule)
            else editMime(selected)
        } }
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {adapter.filter.filter(s)}
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f));setContentView(root);root.requestApplyInsets()
    }
    private fun editMime(raw:String) {
        val record=registry.mime(raw)
        val body=column()
        body.addView(TextView(this).apply {text=record.mimeType})
        val extensions=field(body,"Extensions (comma separated; multi-part extensions allowed)",record.extensions.sorted().joinToString(", "))
        val logical=field(body,"Theme icon name",record.iconName)
        val image=CheckBox(this).apply { text="Contents can be rendered as an image";isChecked=record.isImage };body.addView(image)
        val mode=CheckBox(this).apply { text="Use MIME icon instead of content thumbnail";isChecked=record.overrideImageIcon;isEnabled=image.isChecked };body.addView(mode)
        image.setOnCheckedChangeListener { _,checked->mode.isEnabled=checked }
        fun draft()=record.copy(extensions=extensions.text.toString().split(',').map {it.trim().trimStart('.')}.filter {it.isNotEmpty()}.toSet(),
            iconName=logical.text.toString().trim(),isImage=image.isChecked,overrideImageIcon=mode.isChecked)
        val preview=LinearLayout(this)
        val defaultView=ImageView(this).apply { contentDescription="Resolved theme default" }
        val overrideView=ImageView(this).apply { contentDescription="User override" }
        preview.addView(defaultView,LinearLayout.LayoutParams(100,100));preview.addView(overrideView,LinearLayout.LayoutParams(100,100));body.addView(preview)
        body.addView(TextView(this).apply { text="Theme default (left) · user override (right)" })
        fun preview() { val name=logical.text.toString();worker.execute {
            val default=theme.resolveLogicalIcon(name,96) ?: theme.resolveMimeCategory(record.mimeType,96)
            val selected=IconOverrideAssets.decode(filesDir,custom,ThumbnailDecoder(contentResolver),record.overrideIcon,record.mimeType,96)
            runOnUiThread {if(!isDestroyed){defaultView.setImageBitmap(default);overrideView.setImageBitmap(selected)}}
        } }
        body.addView(Button(this).apply {text="Refresh preview";setOnClickListener {preview()}})
        val dialog=AlertDialog.Builder(this).setTitle("MIME icon settings").setView(ScrollView(this).apply {addView(body)})
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        body.addView(Button(this).apply {text="Choose override icon";setOnClickListener {
            runCatching {registry.save(draft());pickMime=record.mimeType;pickRule=null;dialog.dismiss();pickImage()}.onFailure(::message)
        }})
        body.addView(Button(this).apply {text="Clear override icon";setOnClickListener {
            runCatching {custom.clearMimeIcon(record.mimeType);registry.save(draft().copy(overrideIcon=null));dialog.dismiss();editMime(record.mimeType)}.onFailure(::message)
        }})
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {registry.save(draft());dialog.dismiss();showList()}.onFailure(::message)
        }}
        dialog.show();preview()
    }
    private fun editRule(rule:IconRegistry.UriRule) {
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
        val preview=ImageView(this).apply {contentDescription="URI rule icon preview"};body.addView(preview,LinearLayout.LayoutParams(100,100))
        body.addView(Button(this).apply {text="Refresh preview";setOnClickListener {
            val name=logical.text.toString();worker.execute {val icon=theme.resolveLogicalIcon(name,96);runOnUiThread {if(!isDestroyed)preview.setImageBitmap(icon)}}
        }})
        val dialog=AlertDialog.Builder(this).setTitle("URI icon rule").setView(ScrollView(this).apply {addView(body)})
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create()
        body.addView(Button(this).apply {text="Choose override icon";setOnClickListener {
            runCatching {registry.save(draft());pickRule=rule.id;pickMime=null;dialog.dismiss();pickImage()}.onFailure(::message)
        }})
        body.addView(Button(this).apply {text="Clear override icon";setOnClickListener {
            runCatching {registry.save(draft().copy(overrideIcon=null));dialog.dismiss();editRule(registry.uriRules().first {it.id==rule.id})}.onFailure(::message)
        }})
        body.addView(Button(this).apply {text="Delete rule";setOnClickListener {registry.removeUriRule(rule.id);dialog.dismiss();showList()}})
        dialog.setOnShowListener {dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching {registry.save(draft());dialog.dismiss();showList()}.onFailure(::message)
        }}
        dialog.show()
        worker.execute {
            val icon=IconOverrideAssets.decode(filesDir,custom,ThumbnailDecoder(contentResolver),rule.overrideIcon,null,96)
                ?: theme.resolveLogicalIcon(rule.iconName,96)
            runOnUiThread {if(!isDestroyed)preview.setImageBitmap(icon)}
        }
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
            runOnUiThread {if(!isDestroyed){result.onFailure(::message);showList()}}
        }
    }
    override fun onSaveInstanceState(out:Bundle) {super.onSaveInstanceState(out);out.putString("pickMime",pickMime);out.putString("pickRule",pickRule);out.putBoolean("uris",uris)}
    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
}
