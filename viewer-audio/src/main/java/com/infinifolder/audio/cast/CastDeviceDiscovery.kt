package com.infinifolder.audio.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers DLNA/UPnP MediaRenderers via SSDP (UDP 1900) and Google Cast
 * devices via Android NsdManager (_googlecast._tcp).
 */
class CastDeviceDiscovery(
    private val context: Context,
    private val onDevicesChanged: (List<CastDevice>) -> Unit
) {
    private val isDiscovering = AtomicBoolean(false)
    private val devicesMap = ConcurrentHashMap<String, CastDevice>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdManager: NsdManager? = null
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private var ssdpThread: Thread? = null

    fun startDiscovery() {
        if (isDiscovering.getAndSet(true)) return

        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        try {
            multicastLock = wifi?.createMulticastLock("CastDiscoveryLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}

        startSsdpDiscovery()
        startNsdDiscovery()
    }

    fun stopDiscovery() {
        if (!isDiscovering.getAndSet(false)) return

        try {
            ssdpThread?.interrupt()
            ssdpThread = null
        } catch (_: Exception) {}

        try {
            nsdListener?.let { nsdManager?.stopServiceDiscovery(it) }
            nsdListener = null
            nsdManager = null
        } catch (_: Exception) {}

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
            multicastLock = null
        } catch (_: Exception) {}
    }

    fun getDiscoveredDevices(): List<CastDevice> {
        return devicesMap.values.toList()
    }

    private fun addOrUpdateDevice(device: CastDevice) {
        devicesMap[device.id] = device
        onDevicesChanged(devicesMap.values.toList())
    }

    private fun startNsdDiscovery() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            nsdListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains("_googlecast._tcp", ignoreCase = true)) {
                        resolveCastService(serviceInfo)
                    }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val id = "cast_${serviceInfo.serviceName}"
                    if (devicesMap.remove(id) != null) {
                        onDevicesChanged(devicesMap.values.toList())
                    }
                }
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            nsdManager?.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, nsdListener)
        } catch (e: Exception) {
            android.util.Log.w("CastDiscovery", "NSD Cast discovery not available: ${e.message}")
        }
    }

    private fun resolveCastService(serviceInfo: NsdServiceInfo) {
        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host ?: return
                val port = resolved.port
                val name = resolved.serviceName.replace('-', ' ')
                val id = "cast_${resolved.serviceName}"
                val device = CastDevice(
                    id = id,
                    name = name,
                    protocol = CastProtocol.CHROMECAST,
                    ip = host,
                    port = port
                )
                addOrUpdateDevice(device)
            }
        })
    }

    private fun startSsdpDiscovery() {
        ssdpThread = Thread({
            val ssdpGroup = InetAddress.getByName("239.255.255.250")
            val ssdpPort = 1900
            var socket: DatagramSocket? = null

            try {
                socket = DatagramSocket()
                socket.soTimeout = 4000

                // Search specifically for MediaRenderer devices
                val searchRequests = listOf(
                    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
                    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: ssdp:all\r\n\r\n"
                )

                for (msg in searchRequests) {
                    val bytes = msg.toByteArray(Charsets.US_ASCII)
                    val packet = DatagramPacket(bytes, bytes.size, ssdpGroup, ssdpPort)
                    socket.send(packet)
                }

                val buf = ByteArray(4096)
                val respPacket = DatagramPacket(buf, buf.size)

                while (isDiscovering.get()) {
                    try {
                        socket.receive(respPacket)
                        val respText = String(respPacket.data, 0, respPacket.length, Charsets.US_ASCII)
                        val senderIp = respPacket.address
                        parseSsdpResponse(respText, senderIp)
                    } catch (_: Exception) {
                        if (!isDiscovering.get()) break
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("CastDiscovery", "SSDP discovery ended: ${e.message}")
            } finally {
                socket?.close()
            }
        }, "CastDiscovery-SSDP").apply {
            isDaemon = true
            start()
        }
    }

    private fun parseSsdpResponse(response: String, senderIp: InetAddress) {
        val lines = response.lines()
        var location: String? = null
        var usn: String? = null
        var st: String? = null

        for (line in lines) {
            val trim = line.trim()
            if (trim.startsWith("LOCATION:", ignoreCase = true)) {
                location = trim.substringAfter(':').trim()
            } else if (trim.startsWith("USN:", ignoreCase = true)) {
                usn = trim.substringAfter(':').trim()
            } else if (trim.startsWith("ST:", ignoreCase = true)) {
                st = trim.substringAfter(':').trim()
            }
        }

        if (location != null && (st?.contains("MediaRenderer", true) == true || st?.contains("AVTransport", true) == true || st == "ssdp:all")) {
            val devId = usn ?: "dlna_${senderIp.hostAddress}_$location"
            if (!devicesMap.containsKey(devId)) {
                fetchDeviceDescription(location, devId, senderIp)
            }
        }
    }

    private fun fetchDeviceDescription(locationUrl: String, devId: String, senderIp: InetAddress) {
        Thread {
            try {
                val url = URL(locationUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                if (conn.responseCode in 200..299) {
                    val xml = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()

                    val friendlyName = xml.substringAfter("<friendlyName>", "").substringBefore("</friendlyName>").trim()
                    val manufacturer = xml.substringAfter("<manufacturer>", "").substringBefore("</manufacturer>").trim()
                    val model = xml.substringAfter("<modelName>", "").substringBefore("</modelName>").trim()

                    // Check for AVTransport service controlURL
                    if (xml.contains("urn:schemas-upnp-org:service:AVTransport:1")) {
                        val transportBlock = xml.substringAfter("urn:schemas-upnp-org:service:AVTransport:1")
                            .substringBefore("</service>")
                        var controlUrl = transportBlock.substringAfter("<controlURL>", "").substringBefore("</controlURL>").trim()

                        if (controlUrl.isNotEmpty()) {
                            if (!controlUrl.startsWith("http://") && !controlUrl.startsWith("https://")) {
                                val baseUrl = "${url.protocol}://${url.host}:${url.port}"
                                controlUrl = if (controlUrl.startsWith("/")) "$baseUrl$controlUrl" else "$baseUrl/$controlUrl"
                            }

                            val name = if (friendlyName.isNotEmpty()) friendlyName else "DLNA Renderer (${senderIp.hostAddress})"
                            val device = CastDevice(
                                id = devId,
                                name = name,
                                protocol = CastProtocol.DLNA,
                                ip = senderIp,
                                port = url.port,
                                controlUrl = controlUrl,
                                manufacturer = manufacturer.takeIf { it.isNotEmpty() },
                                model = model.takeIf { it.isNotEmpty() }
                            )
                            addOrUpdateDevice(device)
                        }
                    }
                }
            } catch (_: Exception) {}
        }.start()
    }
}
