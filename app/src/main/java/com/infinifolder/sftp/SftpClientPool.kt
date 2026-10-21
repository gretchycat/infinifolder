package com.infinifolder.sftp

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class SftpConfig(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val initialPath: String = "/",
    val timeoutSeconds: Int = 15
)

class SftpClientPool : Closeable {

    private data class ActiveClient(
        val session: Session,
        val channel: ChannelSftp,
        val config: SftpConfig,
        val credentials: SftpCredentials,
        var lastUsedMillis: Long = System.currentTimeMillis()
    )

    private val clients = mutableMapOf<String, ActiveClient>()
    private val lock = Any()

    fun <T> withChannel(config: SftpConfig, credentials: SftpCredentials, block: (ChannelSftp) -> T): T {
        val client = getOrCreateClient(config, credentials)
        return try {
            synchronized(client) {
                client.lastUsedMillis = System.currentTimeMillis()
                block(client.channel)
            }
        } catch (e: Exception) {
            // If the connection was dropped by server, clear and retry once
            if (!client.session.isConnected || !client.channel.isConnected) {
                disconnect(config.id)
                val freshClient = getOrCreateClient(config, credentials)
                synchronized(freshClient) {
                    freshClient.lastUsedMillis = System.currentTimeMillis()
                    block(freshClient.channel)
                }
            } else {
                throw e
            }
        }
    }

    private fun getOrCreateClient(config: SftpConfig, credentials: SftpCredentials): ActiveClient {
        synchronized(lock) {
            val existing = clients[config.id]
            if (existing != null && existing.session.isConnected && existing.channel.isConnected) {
                return existing
            }
            if (existing != null) {
                disconnect(config.id)
            }

            val fresh = createClient(config, credentials)
            clients[config.id] = fresh
            return fresh
        }
    }

    private fun createClient(config: SftpConfig, credentials: SftpCredentials): ActiveClient {
        val jsch = JSch()

        if (credentials.authType == SftpAuthType.KEY_CERT) {
            val prvKeyBytes = credentials.privateKey?.trim()?.toByteArray(StandardCharsets.UTF_8)
                ?: throw IOException("Private key required for certificate/key authentication")
            val certBytes = credentials.certificate?.trim()?.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)
            val passBytes = credentials.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(StandardCharsets.UTF_8)

            jsch.addIdentity("sftp_${config.id}", prvKeyBytes, certBytes, passBytes)
        }

        val session = jsch.getSession(config.username, config.host, config.port)
        if (credentials.authType == SftpAuthType.PASSWORD) {
            session.setPassword(credentials.password ?: "")
        }

        // Custom SocketFactory with TCP_NODELAY and 1MB socket send/receive buffers
        val socketFactory = object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(host: String, port: Int): java.net.Socket {
                val socket = java.net.Socket()
                socket.tcpNoDelay = true
                socket.receiveBufferSize = 1024 * 1024
                socket.sendBufferSize = 1024 * 1024
                socket.connect(java.net.InetSocketAddress(host, port), config.timeoutSeconds * 1000)
                return socket
            }

            override fun getInputStream(socket: java.net.Socket): InputStream = socket.getInputStream()

            override fun getOutputStream(socket: java.net.Socket): OutputStream = socket.getOutputStream()
        }
        session.setSocketFactory(socketFactory)

        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig(
            "PreferredAuthentications",
            if (credentials.authType == SftpAuthType.PASSWORD) {
                "password,keyboard-interactive"
            } else {
                "publickey,keyboard-interactive,password"
            }
        )

        // Prioritize hardware-accelerated ciphers (GCM / CTR) on ARM/x86 processors
        val defaultCiphers = JSch.getConfig("cipher.s2c") ?: ""
        val fastCiphers = listOf(
            "aes128-gcm@openssh.com",
            "aes256-gcm@openssh.com",
            "chacha20-poly1305@openssh.com",
            "aes128-ctr",
            "aes192-ctr",
            "aes256-ctr"
        )
        val preferredCiphers = (fastCiphers + defaultCiphers.split(",")).distinct().filter { it.isNotBlank() }.joinToString(",")
        session.setConfig("cipher.s2c", preferredCiphers)
        session.setConfig("cipher.c2s", preferredCiphers)
        session.setConfig("compression.s2c", "none")
        session.setConfig("compression.c2s", "none")

        session.timeout = config.timeoutSeconds * 1000
        session.setServerAliveInterval(30_000)
        session.connect()

        val channel = session.openChannel("sftp") as ChannelSftp
        try {
            channel.setBulkRequests(128)
        } catch (_: Throwable) {}
        channel.connect()

        return ActiveClient(session, channel, config, credentials)
    }

    fun testConnection(config: SftpConfig, credentials: SftpCredentials): Result<String> {
        return try {
            val client = createClient(config, credentials)
            val pwd = client.channel.pwd()
            val serverVersion = client.session.serverVersion ?: "SSH-2.0"
            client.channel.disconnect()
            client.session.disconnect()
            Result.success("Connected successfully!\nServer: $serverVersion\nWorking directory: $pwd")
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun openStream(config: SftpConfig, credentials: SftpCredentials, remotePath: String): InputStream {
        val client = getOrCreateClient(config, credentials)
        val channel = client.session.openChannel("sftp") as ChannelSftp
        try {
            channel.setBulkRequests(128)
        } catch (_: Throwable) {}
        channel.connect()
        val sftpStream = channel.get(remotePath)
        val buffered = java.io.BufferedInputStream(sftpStream, 128 * 1024)
        return object : InputStream() {
            private var closed = false

            override fun read(): Int = buffered.read()

            override fun read(b: ByteArray): Int = buffered.read(b, 0, b.size)

            override fun read(b: ByteArray, off: Int, len: Int): Int = buffered.read(b, off, len)

            override fun skip(n: Long): Long = buffered.skip(n)

            override fun available(): Int = buffered.available()

            override fun close() {
                if (!closed) {
                    closed = true
                    try { buffered.close() } catch (_: Throwable) {}
                    try { channel.disconnect() } catch (_: Throwable) {}
                }
            }
        }
    }

    fun openWriteStream(config: SftpConfig, credentials: SftpCredentials, remotePath: String): OutputStream {
        val client = getOrCreateClient(config, credentials)
        val channel = client.session.openChannel("sftp") as ChannelSftp
        try {
            channel.setBulkRequests(128)
        } catch (_: Throwable) {}
        channel.connect()
        val sftpOut = channel.put(remotePath, ChannelSftp.OVERWRITE)
        val buffered = java.io.BufferedOutputStream(sftpOut, 128 * 1024)
        return object : OutputStream() {
            private var closed = false

            override fun write(b: Int) {
                buffered.write(b)
            }

            override fun write(b: ByteArray) {
                buffered.write(b, 0, b.size)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                buffered.write(b, off, len)
            }

            override fun flush() {
                buffered.flush()
            }

            override fun close() {
                if (!closed) {
                    closed = true
                    try { buffered.flush() } catch (_: Throwable) {}
                    try { buffered.close() } catch (_: Throwable) {}
                    try { channel.disconnect() } catch (_: Throwable) {}
                }
            }
        }
    }

    fun disconnect(connectionId: String) {
        synchronized(lock) {
            clients.remove(connectionId)?.let { client ->
                try { client.channel.disconnect() } catch (_: Exception) {}
                try { client.session.disconnect() } catch (_: Exception) {}
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            for ((_, client) in clients) {
                try { client.channel.disconnect() } catch (_: Exception) {}
                try { client.session.disconnect() } catch (_: Exception) {}
            }
            clients.clear()
        }
    }
}
