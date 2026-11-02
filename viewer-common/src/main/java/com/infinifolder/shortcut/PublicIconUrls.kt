package com.infinifolder.shortcut

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** Public favicon discovery must not send path/query credentials or reach private networks. */
object PublicIconUrls {
    fun origin(raw:String):String? = runCatching {
        val uri=URI(raw)
        val scheme=uri.scheme?.lowercase(Locale.ROOT)
        if(scheme !in setOf("http","https") || uri.rawUserInfo!=null || uri.host.isNullOrBlank())return null
        URI(scheme,null,uri.host.lowercase(Locale.ROOT),uri.port,"/",null,null).toASCIIString()
    }.getOrNull()
    fun publicAddress(address:InetAddress):Boolean {
        if(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress)return false
        val bytes=address.address.map {it.toInt() and 255}
        return when(bytes.size) {
            4 -> bytes[0] !in setOf(0,10,127) && bytes[0]<224 &&
                !(bytes[0]==100 && bytes[1] in 64..127) && !(bytes[0]==169 && bytes[1]==254) &&
                !(bytes[0]==172 && bytes[1] in 16..31) && !(bytes[0]==192 && bytes[1] in setOf(0,168)) &&
                !(bytes[0]==198 && bytes[1] in 18..19)
            16 -> bytes[0] and 0xfe != 0xfc && !(bytes[0]==0x20 && bytes[1]==1 && bytes[2]==0x0d && bytes[3]==0xb8)
            else -> false
        }
    }
    private val dns=java.util.concurrent.ThreadPoolExecutor(2,2,30,java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(16))
    private fun lookupBounded(host:String):Array<InetAddress> {
        val task=dns.submit<Array<InetAddress>> {InetAddress.getAllByName(host)}
        return try {task.get(800,java.util.concurrent.TimeUnit.MILLISECONDS)}
        catch(error:Exception) {task.cancel(true);emptyArray()}
    }
    fun allowed(raw:String, lookup:(String)->Array<InetAddress> = ::lookupBounded):Boolean = runCatching {
        val uri=URI(raw)
        uri.scheme?.lowercase(Locale.ROOT) in setOf("http","https") && uri.rawUserInfo==null &&
            !uri.host.isNullOrBlank() && lookup(uri.host).let {it.isNotEmpty() && it.all(::publicAddress)}
    }.getOrDefault(false)
}
