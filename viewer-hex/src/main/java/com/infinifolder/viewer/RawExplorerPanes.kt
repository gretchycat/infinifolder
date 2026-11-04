package com.infinifolder.viewer

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.infinifolder.audio.model.RawAudioExplorer
import com.infinifolder.preview.RawImageExplorer
import com.infinifolder.preview.RawImagePalettes
import com.infinifolder.theme.ThemeManager
import io.github.gretchycat.infiniscribe.HexEncoding
import java.util.concurrent.atomic.AtomicLong

internal abstract class RawExplorerPane(protected val activity: Activity, protected val session: HexSession,
    protected val status:(String)->Unit, protected val save:(RawMediaConfigStore.Record)->Unit) {
    val tools=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
    val actions=LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL}
    abstract val view:View
    protected val main=Handler(Looper.getMainLooper())
    protected val generation=AtomicLong()
    @Volatile var active=false
    protected var ready=false
    private var disposed=false
    protected val inputs=mutableListOf<View>()
    val saveButton=button("Save metadata") {working?.let(save)}.apply {isEnabled=false}
    protected var working:RawMediaConfigStore.Record?=null
    protected val theme get()=ThemeManager.activeTheme
    protected fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    protected fun button(text:String, action:()->Unit)=Button(activity).apply {
        this.text=text;isAllCaps=false;minWidth=0;minimumWidth=0;inputs.add(this);setOnClickListener{action()}
    }
    protected fun input(hint:String,value:String)=EditText(activity).apply {
        this.hint=hint;setText(value);isSingleLine=true;textSize=13f;minWidth=dp(96)
        filters=arrayOf(android.text.InputFilter.LengthFilter(32));inputs.add(this)
        addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(ready)invalidateInterpretation()}
            override fun afterTextChanged(s:android.text.Editable?)=Unit
        })
    }
    protected fun toggle(text:String)=Switch(activity).apply {this.text=text;inputs.add(this)}
    protected fun adapter(labels:List<String>)=object:ArrayAdapter<String>(activity,android.R.layout.simple_spinner_dropdown_item,labels){
        private fun themed(view:View)=view.apply {(this as? TextView)?.setTextColor(theme.textPrimary);setBackgroundColor(theme.alternateBackground)}
        override fun getView(position:Int,convertView:View?,parent:ViewGroup)=themed(super.getView(position,convertView,parent))
        override fun getDropDownView(position:Int,convertView:View?,parent:ViewGroup)=themed(super.getDropDownView(position,convertView,parent))
    }
    protected fun spinner(labels:List<String>,selected:()->Unit={})=Spinner(activity).apply {
        adapter=adapter(labels);inputs.add(this)
        onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){if(ready){selected();schedule()}}
        }
    }
    protected fun row(vararg children:View) {
        tools.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled=true;isScrollbarFadingEnabled=false;scrollBarSize=dp(4)
            addView(LinearLayout(activity).apply {gravity=Gravity.CENTER_VERTICAL
                children.forEach {addView(it,LinearLayout.LayoutParams(-2,dp(48)))}})
        },LinearLayout.LayoutParams(-1,dp(48)))
    }
    protected fun toggleGrid(vararg switches:Switch){
        tools.addView(RawToggleGrid(activity,switches.toList()),LinearLayout.LayoutParams(-1,-2))
    }
    protected fun actionRow(vararg buttons:Button){
        buttons.forEach {
            it.textSize=13f;it.maxLines=2;it.setPadding(dp(4),0,dp(4),0)
            actions.addView(it,LinearLayout.LayoutParams(0,dp(48),1f))
        }
    }
    private val pending=Runnable {render()}
    protected fun invalidateInterpretation(){working=null;saveButton.isEnabled=false;generation.incrementAndGet();main.removeCallbacks(pending)}
    protected fun schedule() {if(!ready)return;invalidateInterpretation();main.postDelayed(pending,150)}
    private var quickPending=false
    private val quick=Runnable{quickPending=false;render()}
    protected fun scheduleQuick(){
        if(!ready)return
        working=null;saveButton.isEnabled=false
        if(!quickPending){quickPending=true;main.postDelayed(quick,30)}
    }
    fun enabled(enabled:Boolean){inputs.forEach{it.isEnabled=enabled};saveButton.isEnabled=enabled&&working!=null;formatAvailability()}
    protected open fun formatAvailability()=Unit
    abstract fun render()
    abstract fun step(direction:Int)
    fun dispose(){disposed=true;active=false;generation.incrementAndGet();main.removeCallbacksAndMessages(null);failed()}
    protected open fun discarded(value:Any?)=Unit
    protected open fun calculationFinished()=Unit
    protected fun <T> calculate(task:()->T,done:(T)->Unit) {
        if(!ready||!active||session.busy||disposed)return
        working=null;saveButton.isEnabled=false
        val request=generation.incrementAndGet()
        session.worker.execute {
            if(disposed||!active||request!=generation.get()){main.post{calculationFinished()};return@execute}
            try {
                val value=task()
                main.post {try{if(!disposed&&active&&request==generation.get())done(value)else discarded(value)}finally{calculationFinished()}}
            } catch(error:Exception){main.post{try{if(!disposed&&active&&request==generation.get()){failed();status(error.message?:"Cannot interpret these bytes")}}finally{calculationFinished()}}}
        }
    }
    protected fun success(record:RawMediaConfigStore.Record,message:String){working=record;saveButton.isEnabled=!session.busy;status(message)}
    protected abstract fun failed()
    protected fun notice(message:String)=ThemeManager.createDialogBuilder(activity).setMessage(message).setPositiveButton("OK",null).show()
}

internal class RawPicPane(activity:Activity,session:HexSession,status:(String)->Unit,save:(RawMediaConfigStore.Record)->Unit)
    :RawExplorerPane(activity,session,status,save){
    private val imageView=ZoomImageView(activity).apply {contentDescription="Raw image explorer"}
    override val view=RawImageViewport(activity,imageView)
    var config=RawImageExplorer.Config()
        private set
    private var bitmap:Bitmap?=null
    private var filePalette:List<Int>?=null
    private var filePaletteBits=8
    private var paletteChoices=emptyList<RawImagePalettes.Preset>()
    private var paletteDepth=-1
    private var selectedWidth=128
    private var selectedHeight=128
    private var layouts=emptyList<RawImageExplorer.Dimensions>()
    private var decodedBytes=0L
    private var fitHeightOnSizing=true
    private var rendering=false
    private var previewPending=false
    private var detectAfterRender=false
    private data class LayoutKey(val document:io.github.gretchycat.infiniscribe.HexDocument,val offset:Long,
        val bits:Int,val stride:Int,val compression:RawImageExplorer.Compression)
    private var layoutKey:LayoutKey?=null
    private val offset=input("Offset","0x0")
    private val stride=input("Row stride (0=auto)","0")
    private val depth:Spinner=spinner(RawImageExplorer.depths.map{"$it bpp"}) {
        if(RawImageExplorer.depths[depth.selectedItemPosition]!=config.bits)fitHeightOnSizing=true
        formatAvailability();invalidateSizing()
    }
    private val palette:Spinner=spinner(RawImagePalettes.Preset.entries.map{it.title}){updatePaletteStrip()}
    private val paletteStrip=RawPaletteStrip(activity)
    private data class StripKey(val bits:Int,val preset:RawImagePalettes.Preset?,val file:List<Int>?)
    private var stripKey:StripKey?=null
    private val compression:Spinner=spinner(RawImageExplorer.Compression.entries.map{it.title}) {
        if(RawImageExplorer.Compression.entries[compression.selectedItemPosition]!=config.compression)fitHeightOnSizing=true
        invalidateSizing()
    }
    private val endian=toggle("Little endian").apply {isChecked=true}
    private val bgr=toggle("BGR")
    private val bottomUp=toggle("Bottom up")
    private val alpha=toggle("Alpha")
    private val smooth=toggle("Smooth scaling")
    var smoothScaling:Boolean
        get()=smooth.isChecked
        set(value){smooth.isChecked=value;applyScaling()}
    private val widthControl=DimensionControl("Width",true)
    private val heightControl=DimensionControl("Height",false)
    private val layoutValues=RawImageLayoutValues(activity)

    init {
        actionRow(button("Auto detect"){autoDetect()},button("Render"){render()},button("Fit"){imageView.resetZoom()})
        tools.addView(LinearLayout(activity).apply {
            addView(widthControl.view,LinearLayout.LayoutParams(0,-2,1f))
            addView(heightControl.view,LinearLayout.LayoutParams(0,-2,1f))
        })
        tools.addView(layoutValues,LinearLayout.LayoutParams(-1,-2))
        row(depth,palette,compression)
        tools.addView(paletteStrip,LinearLayout.LayoutParams(-1,dp(24)).apply {
            topMargin=dp(2);bottomMargin=dp(4)
        })
        row(offset,stride)
        toggleGrid(endian,bgr,bottomUp,alpha,smooth)
        smooth.contentDescription="Pixel scaling when off; smooth scaling when on"
        smooth.setOnCheckedChangeListener{_,_->
            applyScaling()
            working=working?.copy(smoothScaling=smooth.isChecked)
        }
        listOf(endian,bgr,bottomUp,alpha).forEach {it.setOnCheckedChangeListener{_,_->if(ready)schedule()}}
        listOf(offset,stride).forEach{it.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(ready){fitHeightOnSizing=true;invalidateSizing();schedule()}}
            override fun afterTextChanged(s:android.text.Editable?)=Unit
        })}
        install(config,preserveGeometry=false)
    }
    private fun applyScaling(){
        (imageView.drawable as? android.graphics.drawable.BitmapDrawable)?.paint?.apply {
            isFilterBitmap=smooth.isChecked;isDither=smooth.isChecked
        }
        imageView.invalidate()
    }

    /** Native click/long-click recognition, with a progressively shorter repeat interval. */
    private fun repeatButton(label:String,action:()->Unit):Button {
        val button=button(label,action).apply {setPadding(0,0,0,0);textSize=18f}
        var began=0L
        val repeat=object:Runnable {
            override fun run(){
                if(!active||session.busy||!button.isPressed||!button.isEnabled||!activity.hasWindowFocus())return
                action()
                val held=android.os.SystemClock.uptimeMillis()-began
                val interval=when{held<700->250L;held<1800->120L;held<3000->60L;held<5000->30L;else->16L}
                main.postDelayed(this,interval)
            }
        }
        button.setOnLongClickListener {began=android.os.SystemClock.uptimeMillis();repeat.run();true}
        button.setOnTouchListener{_,event->
            if(event.actionMasked==android.view.MotionEvent.ACTION_UP||event.actionMasked==android.view.MotionEvent.ACTION_CANCEL)
                main.removeCallbacks(repeat)
            false
        }
        return button
    }
    private inner class DimensionControl(val title:String,val isWidth:Boolean){
        private var updating=false
        val label=TextView(activity).apply {text=title}
        val field=EditText(activity).apply {
            inputType=android.text.InputType.TYPE_CLASS_NUMBER;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            isSingleLine=true;gravity=Gravity.CENTER;textSize=14f;minWidth=0
            setSelectAllOnFocus(true)
            filters=arrayOf(android.text.InputFilter.LengthFilter(4));inputs.add(this)
        }
        private val minus=repeatButton("−"){adjust(-1)}
        private val plus=repeatButton("+"){adjust(1)}
        val view=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;addView(label)
            addView(LinearLayout(activity).apply {
                gravity=Gravity.CENTER_VERTICAL
                addView(minus,LinearLayout.LayoutParams(dp(40),dp(44)))
                addView(field,LinearLayout.LayoutParams(0,dp(44),1f))
                addView(plus,LinearLayout.LayoutParams(dp(40),dp(44)))
            })
        }
        init {
            minus.contentDescription="Decrease $title";plus.contentDescription="Increase $title"
            field.contentDescription="$title in pixels"
            field.addTextChangedListener(object:android.text.TextWatcher{
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
                override fun afterTextChanged(s:android.text.Editable?)=Unit
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                    if(!ready||updating)return
                    val value=s?.toString()?.toIntOrNull()
                    if(value!=null&&value in 1..maximum()){
                        field.error=null;changeDimension(isWidth,value);scheduleQuick()
                    }else {
                        field.error=if(maximum()>0)"1–${maximum()}" else "No complete row fits"
                        invalidateInterpretation()
                    }
                }
            })
            field.setOnEditorActionListener {_,action,_->
                if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){finishEntry();render();false}else false
            }
            field.setOnFocusChangeListener {_,focused->if(!focused&&ready)finishEntry()}
        }
        private fun maximum()=if(isWidth)layouts.lastOrNull()?.width?:0 else maximumHeight()
        private fun current()=if(isWidth)selectedWidth else selectedHeight
        private fun finishEntry(){
            val value=field.text.toString().toIntOrNull()
            if(value==null||value !in 1..maximum()){display(current());scheduleQuick()}
            field.error=null
        }
        private fun adjust(direction:Int){
            if(!active||session.busy||maximum()<1)return
            val value=(current()+direction).coerceIn(1,maximum())
            if(value!=current()){changeDimension(isWidth,value);scheduleQuick()}
        }
        fun display(value:Int){
            updating=true
            if(field.text.toString()!=value.toString()){field.setText(value.toString());field.setSelection(field.length())}
            updating=false
        }
        fun availability(){
            val enabled=!session.busy&&session.document!=null&&maximum()>0
            field.isEnabled=enabled;minus.isEnabled=enabled&&current()>1;plus.isEnabled=enabled&&current()<maximum()
            label.setTextColor(theme.textPrimary)
        }
    }
    private fun maximumHeight()=layouts.getOrNull(selectedWidth-1)?.height?:0
    private fun changeDimension(isWidth:Boolean,value:Int){
        if(isWidth){
            val changed=selectedWidth!=value
            selectedWidth=value
            selectedHeight=if(changed)maximumHeight() else selectedHeight.coerceAtMost(maximumHeight())
        }else selectedHeight=value
        widthControl.display(selectedWidth);heightControl.display(selectedHeight)
        summarize();formatAvailability()
    }
    private fun summarize(){
        val key=layoutKey?:return
        val row=if(key.stride>0)key.stride else (selectedWidth*key.bits+7)/8
        val used=row.toLong()*selectedHeight
        val padding=row*8L-selectedWidth*key.bits
        layoutValues.show(selectedHeight,row,used,decodedBytes,padding,
            decoded=key.compression!=RawImageExplorer.Compression.RAW)
    }
    private fun geometryValid()=layouts.isNotEmpty()&&selectedWidth in 1..layouts.last().width&&selectedHeight in 1..maximumHeight()&&
        widthControl.field.text.toString().toIntOrNull()==selectedWidth&&heightControl.field.text.toString().toIntOrNull()==selectedHeight
    fun invalidateSizing(){
        layoutKey=null;layouts=emptyList();layoutValues.clear()
        widthControl.availability();heightControl.availability()
    }
    fun install(value:RawImageExplorer.Config,preserveGeometry:Boolean=true){
        ready=false;config=value;filePalette=value.filePalette;filePaletteBits=value.bits;paletteDepth=-1
        selectedWidth=value.width;selectedHeight=value.height;fitHeightOnSizing=!preserveGeometry;invalidateSizing()
        widthControl.display(selectedWidth);heightControl.display(selectedHeight)
        offset.setText("0x${value.offset.toString(16)}");stride.setText(value.stride.toString())
        depth.setSelection(RawImageExplorer.depths.indexOf(value.bits));compression.setSelection(value.compression.ordinal)
        updatePalettes(value.palette,preferFile=filePalette!=null)
        endian.isChecked=value.littleEndian;bgr.isChecked=value.bgr;bottomUp.isChecked=value.bottomUp;alpha.isChecked=value.alpha
        ready=true;formatAvailability()
    }
    private fun hasFilePalette(bits:Int)=filePalette!=null&&bits==filePaletteBits&&bits in 1..8&&filePalette!!.size<=(1 shl bits)
    private fun updatePalettes(preferred:RawImagePalettes.Preset?=null,preferFile:Boolean=false){
        val bits=RawImageExplorer.depths[depth.selectedItemPosition]
        if(paletteDepth==bits)return
        val previous=preferred?:paletteChoices.getOrNull(palette.selectedItemPosition-(if(hasFilePalette(paletteDepth))1 else 0))?:config.palette
        val wasReady=ready;ready=false
        paletteChoices=RawImagePalettes.available(bits);paletteDepth=bits
        palette.adapter=adapter((if(hasFilePalette(bits))listOf("File palette")else emptyList())+
            if(paletteChoices.isEmpty())listOf("Direct color (no palette)") else paletteChoices.map{RawImagePalettes.title(it,bits)})
        val compatible=when {
            previous==RawImagePalettes.Preset.CGA&&bits==1 -> RawImagePalettes.Preset.CGA_MONO
            previous==RawImagePalettes.Preset.CGA&&bits==4 -> RawImagePalettes.Preset.CGA_RGBI
            else -> previous
        }
        palette.setSelection(if(hasFilePalette(bits)&&preferFile)0 else (if(hasFilePalette(bits))1 else 0)+paletteChoices.indexOf(compatible).coerceAtLeast(0))
        ready=wasReady
    }
    private fun updatePaletteStrip(){
        val bits=RawImageExplorer.depths[depth.selectedItemPosition]
        if(bits>8){paletteStrip.visibility=View.INVISIBLE;stripKey=null;return}
        val index=palette.selectedItemPosition-(if(hasFilePalette(bits))1 else 0)
        val file=if(index<0&&hasFilePalette(bits))filePalette else null
        val preset=if(file==null)paletteChoices.getOrNull(index)else null
        if(file==null&&preset==null){paletteStrip.visibility=View.INVISIBLE;stripKey=null;return}
        paletteStrip.visibility=View.VISIBLE
        val key=StripKey(bits,preset,file)
        if(key==stripKey)return
        stripKey=key
        paletteStrip.palette(if(file!=null)"File palette"else RawImagePalettes.title(checkNotNull(preset),bits),
            file?.toIntArray()?:RawImagePalettes.colors(checkNotNull(preset),bits))
    }
    private fun readConfig(validate:Boolean=true):RawImageExplorer.Config {
        val bits=RawImageExplorer.depths[depth.selectedItemPosition]
        val paletteIndex=palette.selectedItemPosition-(if(hasFilePalette(bits))1 else 0)
        return config.copy(offset=HexEncoding.offset(offset.text.toString()),width=selectedWidth,height=selectedHeight,
            bits=bits,compression=RawImageExplorer.Compression.entries[compression.selectedItemPosition],
            palette=paletteChoices.getOrNull(paletteIndex)?:config.palette,filePalette=if(paletteIndex<0&&hasFilePalette(bits))filePalette else null,
            stride=stride.text.toString().toInt(),littleEndian=endian.isChecked,bgr=bgr.isChecked,bottomUp=bottomUp.isChecked,alpha=alpha.isChecked).also{if(validate)it.validate()}
    }
    override fun formatAvailability(){
        if(!ready)return
        updatePalettes()
        updatePaletteStrip();paletteStrip.outline(theme.textSecondary)
        val enabled=!session.busy&&session.document!=null
        val bits=RawImageExplorer.depths[depth.selectedItemPosition]
        palette.isEnabled=enabled&&bits<=8;endian.isEnabled=enabled&&bits==16
        bgr.isEnabled=enabled&&bits>=12;alpha.isEnabled=enabled&&bits==32
        widthControl.availability();heightControl.availability();layoutValues.colors(theme.textPrimary,theme.textSecondary)
        view.setBackgroundColor(theme.background)
    }
    override fun calculationFinished(){
        rendering=false
        if(detectAfterRender&&active){detectAfterRender=false;autoDetect();return}
        if(previewPending&&active){previewPending=false;scheduleQuick()}
    }
    override fun render(){
        if(!ready||!active||session.busy)return
        if(rendering){previewPending=true;return}
        formatAvailability()
        val input=try{readConfig(false)}catch(error:Exception){invalidateSizing();failed();status(error.message?:"Invalid image settings");return}
        val doc=session.document?:return
        val key=LayoutKey(doc,input.offset,input.bits,input.stride,input.compression)
        if(layoutKey!=key){
            invalidateSizing();rendering=true
            calculate({
                require(input.offset<=doc.length){"Image offset is outside the file"}
                val remaining=doc.length-input.offset
                val count=if(input.compression==RawImageExplorer.Compression.RAW)remaining else {
                    require(remaining<=RawImageExplorer.MAX_BYTES){"Compressed input exceeds 16 MiB"}
                    RawImageExplorer.decodedSize(doc.read(input.offset,remaining.toInt()),input.compression)
                }
                count to RawImageExplorer.dimensions(count,input.bits,input.stride)
            }){(count,choices)->
                layoutKey=key;decodedBytes=count;layouts=choices
                if(choices.isEmpty()){
                    failed();layoutValues.clear()
                    status("No complete image row fits. Adjust offset, depth or stride.")
                }else {
                    selectedWidth=selectedWidth.coerceIn(1,choices.last().width)
                    selectedHeight=if(fitHeightOnSizing)maximumHeight() else selectedHeight.coerceIn(1,maximumHeight())
                    fitHeightOnSizing=false
                    widthControl.display(selectedWidth);heightControl.display(selectedHeight)
                    summarize();formatAvailability();previewPending=true
                }
            }
            return
        }
        if(!geometryValid())return
        val value=try{input.also{it.validate()}}catch(error:Exception){failed();status(error.message?:"Invalid image settings");return}
        rendering=true
        calculate({RawMediaRendering.image(doc,value)}){image->
            val old=bitmap;bitmap=image;config=value
            view.resolution(value.width,value.height)
            imageView.setImageBitmapPreservingViewport(image,preserveAspectRatio=true)
            applyScaling();if(old!==image)old?.recycle()
            com.infinifolder.storage.DiagnosticLog.record("hex.pic.render",
                "width=${value.width} height=${value.height} bits=${value.bits} palette=${if(value.filePalette!=null)"file" else value.palette.name} offset=${value.offset} stride=${value.rowStride} filtered=${smooth.isChecked}")
            if(geometryValid()&&runCatching{readConfig(false)==value}.getOrDefault(false)){
                success(RawMediaConfigStore.Record("image",image=value,smoothScaling=smooth.isChecked),"Image preview ready")
            }else {
                working=null;saveButton.isEnabled=false;previewPending=true
                status("Updating PIC ${selectedWidth}×${selectedHeight}…")
            }
        }
    }
    override fun failed(){
        working=null;saveButton.isEnabled=false;imageView.setImageBitmap(null);bitmap?.recycle();bitmap=null
        if(layoutKey==null)layoutValues.clear()
    }
    override fun discarded(value:Any?){(value as? Bitmap)?.recycle()}
    override fun step(direction:Int){
        val value=runCatching{readConfig()}.getOrNull()?:return
        if(value.compression!=RawImageExplorer.Compression.RAW){notice("Compressed streams have variable lengths. Set the next stream's byte offset manually.");return}
        val next=if(direction<0)(value.offset-value.byteCount).coerceAtLeast(0)else(value.offset+value.byteCount).coerceAtMost(session.document?.length?:0)
        offset.setText("0x${next.toString(16)}");fitHeightOnSizing=true;render()
    }
    private fun autoDetect(){
        if(rendering){detectAfterRender=true;previewPending=false;return}
        val preferred=RawImageExplorer.depths[depth.selectedItemPosition]
        val start=runCatching{HexEncoding.offset(offset.text.toString())}.getOrElse{notice(it.message?:"Invalid offset");return}
        rendering=true
        calculate({
            val doc=checkNotNull(session.document);require(start<=doc.length){"Image offset is outside the file"}
            RawImageExplorer.detect(doc.read(start,minOf(doc.length-start,65536).toInt()),doc.length-start,preferred)
                .map{it.copy(config=it.config.copy(offset=start+it.config.offset))}
        }){choices->
            if(choices.isEmpty())notice("No supported header or useful raw-size hypothesis was found. Set the layout manually.")
            else ThemeManager.createDialogBuilder(activity).setTitle("Image detection").setItems(choices.map{it.description}.toTypedArray()){_,which->install(choices[which].config);render()}.show()
        }
    }
}

internal class RawWavePane(activity:Activity,session:HexSession,status:(String)->Unit,save:(RawMediaConfigStore.Record)->Unit,
    private val play:(RawAudioExplorer.Config)->Unit,private val select:(Long)->Unit)
    :RawExplorerPane(activity,session,status,save){
    override val view=RawWaveView(activity)
    var config=RawAudioExplorer.Config()
        private set
    val playButton=button("Play"){if(session.busy)session.cancelled.set(true)else runCatching{readConfig()}.onSuccess(play).onFailure{notice(it.message?:"Invalid PCM format")}}
    private val offset=input("Offset","0x0")
    private val rate=input("Sample rate (Hz)","22050")
    private val frames=input("Frames in window","16384")
    private val format=spinner(RawAudioExplorer.Encoding.entries.map{it.title})
    private val channels=spinner(listOf("Mono","Stereo"))
    private val endian=toggle("Little endian").apply{isChecked=true}
    init {
        actionRow(button("Auto detect"){autoDetect()},button("Render"){render()},playButton)
        row(offset,format,channels)
        toggleGrid(endian)
        row(rate,frames)
        endian.setOnCheckedChangeListener{_,_->if(ready)schedule()}
        view.onFrame={frame->select(config.offset+frame.toLong()*config.frameBytes)}
        install(config)
    }
    fun install(value:RawAudioExplorer.Config){
        ready=false;config=value;offset.setText("0x${value.offset.toString(16)}");rate.setText(value.sampleRate.toString());frames.setText(value.frames.toString())
        format.setSelection(value.encoding.ordinal);channels.setSelection(value.channels-1);endian.isChecked=value.littleEndian;ready=true
    }
    private fun readConfig():RawAudioExplorer.Config {
        val start=HexEncoding.offset(offset.text.toString())
        val remaining=config.byteLength?.let{(config.offset+it-start).coerceAtLeast(0)}
        return RawAudioExplorer.Config(start,rate.text.toString().toInt(),channels.selectedItemPosition+1,
            RawAudioExplorer.Encoding.entries[format.selectedItemPosition],endian.isChecked,frames.text.toString().toInt(),remaining).also{it.validate()}
    }
    override fun render(){
        val value=try{readConfig()}catch(error:Exception){failed();status(error.message?:"Invalid PCM settings");return}
        calculate({
            val doc=checkNotNull(session.document);require(value.offset<=doc.length){"Audio offset is outside the file"}
            val available=minOf(value.byteLength?: (doc.length-value.offset),doc.length-value.offset)
            val count=minOf(available,value.frames.toLong()*value.frameBytes)/value.frameBytes*value.frameBytes
            require(count>0){"No complete sample frames"}
            RawAudioExplorer.envelope(RawAudioExplorer.decode(doc.read(value.offset,count.toInt()),value))
        }){envelope->config=value;view.config=value;view.envelope=envelope
            success(RawMediaConfigStore.Record("audio",audio=value),"WAVE ${value.encoding.title}, ${value.channels} channel(s), ${value.sampleRate} Hz · offset 0x${value.offset.toString(16)}")}
    }
    override fun failed(){working=null;saveButton.isEnabled=false;view.envelope=null}
    override fun step(direction:Int){
        val value=runCatching{readConfig()}.getOrNull()?:return
        val next=(value.offset+direction.toLong()*value.frames*value.frameBytes).coerceIn(0,session.document?.length?:0)
        offset.setText("0x${next.toString(16)}");render()
    }
    private fun autoDetect(){
        calculate({val doc=checkNotNull(session.document);RawAudioExplorer.detect(doc.read(0,minOf(doc.length,65536).toInt()),doc.length)}){choices->
            if(choices.isEmpty())notice("No audio interpretation was found.")else
                ThemeManager.createDialogBuilder(activity).setTitle("Waveform detection").setItems(choices.map{it.description}.toTypedArray()){_,which->install(choices[which].config);render()}.show()
        }
    }
}
