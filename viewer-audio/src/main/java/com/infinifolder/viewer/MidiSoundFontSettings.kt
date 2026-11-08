package com.infinifolder.viewer

import android.content.Context
import android.net.Uri
import com.infinifolder.audio.AudioProcessing
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/** User-owned MIDI soundfont selection, copied into private storage so SAF grants may expire safely. */
internal object MidiSoundFontSettings {
    private const val PREFS = "audio_player"
    private const val PATH_KEY = "midi_soundfont_path"
    private const val NAME_KEY = "midi_soundfont_name"
    private const val MAX_BYTES = 1024L * 1024L * 1024L

    fun selectedName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(NAME_KEY, null)

    /** Must be called after AudioProcessing.initialize so a stored selection overrides the default. */
    fun apply(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString(PATH_KEY, null) ?: return
        val file = File(path)
        try {
            if (file.isFile && file.canRead() && hasSoundFontHeader(file)) {
                AudioProcessing.setMidiSoundFont(file)
            } else {
                prefs.edit().remove(PATH_KEY).remove(NAME_KEY).apply()
            }
        } catch (_: Exception) {
            prefs.edit().remove(PATH_KEY).remove(NAME_KEY).apply()
        }
    }

    /** Stream and validate SF2/SF3 without loading the bank into the managed heap. */
    fun import(context: Context, uri: Uri): String {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "soundfont.sf2"
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension != "sf2" && extension != "sf3") {
            throw IOException("Choose an .sf2 or .sf3 soundfont")
        }

        val directory = File(context.filesDir, "soundfonts")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create soundfont storage")
        val destination = File(directory, "user-soundfont.$extension")
        val temporary = File.createTempFile("user-soundfont-", ".partial", directory)
        try {
            val source = resolver.openInputStream(uri) ?: throw IOException("Cannot read the selected soundfont")
            source.use { input ->
                val header = ByteArray(12)
                readFully(input, header)
                validateHeader(header)
                FileOutputStream(temporary).use { output ->
                    output.write(header)
                    var written = header.size.toLong()
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Soundfont import cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > MAX_BYTES) throw IOException("Soundfont exceeds the 1 GiB import limit")
                        output.write(buffer, 0, count)
                    }
                    val declaredLength = riffLength(header)
                    if (declaredLength < 12L || declaredLength > written) throw IOException("Soundfont RIFF length is invalid or incomplete")
                    output.fd.sync()
                }
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(PATH_KEY, destination.absolutePath).putString(NAME_KEY, displayName).apply()
            File(directory, if (extension == "sf2") "user-soundfont.sf3" else "user-soundfont.sf2").delete()
            AudioProcessing.setMidiSoundFont(destination)
            return displayName
        } finally {
            temporary.delete()
        }
    }

    fun useBundled(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PATH_KEY).remove(NAME_KEY).apply()
        File(context.filesDir, "soundfonts/user-soundfont.sf2").delete()
        File(context.filesDir, "soundfonts/user-soundfont.sf3").delete()
        AudioProcessing.initialize(context.applicationContext)
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw IOException("Selected file is too short to be an SF2/SF3 soundfont")
            offset += count
        }
    }

    private fun validateHeader(header: ByteArray) {
        if (header.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF" ||
            header.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "sfbk") {
            throw IOException("File is not an SF2/SF3 RIFF soundfont")
        }
    }

    private fun hasSoundFontHeader(file: File): Boolean = file.inputStream().use {
        val header = ByteArray(12)
        readFully(it, header)
        validateHeader(header)
        riffLength(header) in 12L..file.length()
    }

    private fun riffLength(header: ByteArray): Long =
        (header[4].toLong() and 0xff) or
            ((header[5].toLong() and 0xff) shl 8) or
            ((header[6].toLong() and 0xff) shl 16) or
            ((header[7].toLong() and 0xff) shl 24) or 8L
}
