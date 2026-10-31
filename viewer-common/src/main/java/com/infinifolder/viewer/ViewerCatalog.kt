package com.infinifolder.viewer

/** Host-independent launch contract. Libraries use granted URI + name/MIME/edit extras. */
object ViewerCatalog {
    fun playbackActivity(operationId: String): String? = when (operationId) {
        "playback_video" -> activity(MediaType.VIDEO)
        "playback_audio" -> activity(MediaType.AUDIO)
        else -> null
    }

    fun activity(type: MediaType): String? = when (type) {
        MediaType.IMAGE -> "ImageViewerActivity"
        MediaType.TEXT -> "TextViewerActivity"
        MediaType.MARKDOWN -> "MarkdownViewerActivity"
        MediaType.HTML -> "HtmlViewerActivity"
        MediaType.PDF -> "PdfViewerActivity"
        MediaType.EPUB -> "EpubViewerActivity"
        MediaType.SPREADSHEET -> "CsvViewerActivity"
        MediaType.AUDIO -> "AudioPlayerActivity"
        MediaType.VIDEO -> "VideoPlayerActivity"
        else -> null
    }?.let { "com.infinifolder.viewer.$it" }
}
