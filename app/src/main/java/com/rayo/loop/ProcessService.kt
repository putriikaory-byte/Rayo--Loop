package com.rayo.loop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class JobSpec(
    val kind: String,
    val videoUri: Uri? = null,
    val audioUri: Uri? = null,
    val audio2Uri: Uri? = null,
    val targetMs: Long = 0L,
    val audioKbps: Int = 128,
    val keepOriginalAudio: Boolean = false,
    val matchMode: Int = 0,
    val targetMb: Int = 0,
    val heightPx: Int = 0,
    val label: String = "hasil"
)

object JobState {
    @Volatile var spec: JobSpec? = null
    @Volatile var active = false
    @Volatile var finished = false
    @Volatile var error: String? = null
    @Volatile var percent = 0
    @Volatile var message = "Siap"
}

class ProcessService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tempFile: File? = null
    private var outName = ""
    private var isAudioOut = false
    private var usedFast = false
    private var currentSpec: JobSpec? = null
    private val progressHolder = ProgressHolder()

    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                JobState.percent = progressHolder.progress
                notifyProgress("Memproses ${JobState.percent}%", JobState.percent)
            }
            handler.postDelayed(this, 700)
        }
    }

    private val listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            onExportDone()
        }

        override fun onError(
            composition: Composition,
            exportResult: ExportResult,
            exportException: ExportException
        ) {
            onExportFailed(exportException)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WORK, "Proses", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Selesai", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL) {
            cancelJob()
            return START_NOT_STICKY
        }
        if (action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NOTIF_WORK,
            buildNotif("Menyiapkan...", -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        val spec = JobState.spec
        if (spec == null || JobState.active) {
            if (!JobState.active) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        JobState.active = true
        JobState.finished = false
        JobState.error = null
        JobState.percent = 0
        JobState.message = "Menyiapkan..."
        currentSpec = spec
        isAudioOut = spec.kind == "audio" || spec.kind == "mix"
        outName = makeName(spec)
        tempFile = File(externalCacheDir ?: cacheDir, "rayo_tmp." + (if (isAudioOut) "m4a" else "mp4"))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RayoLoop:job").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
        try {
            val fast = spec.kind == "video" || spec.kind == "combine" ||
                (spec.kind == "audio" && spec.audioKbps == 0)
            runJob(spec, fast)
        } catch (e: Exception) {
            finishWithError(e.message ?: "Gagal memulai")
        }
        return START_NOT_STICKY
    }

    // ---------- Menjalankan tugas ----------

    private fun runJob(spec: JobSpec, fastMode: Boolean) {
        usedFast = fastMode
        val out = tempFile ?: throw IllegalStateException("Folder sementara tidak ada")
        if (out.exists()) out.delete()
        JobState.message = if (fastMode) "Mode cepat berjalan..." else "Memproses..."
        val t = buildTransformer(spec)
        transformer = t
        if (spec.kind == "compress") {
            t.start(compressItem(spec), out.absolutePath)
        } else {
            t.start(buildComposition(spec, fastMode), out.absolutePath)
        }
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 700)
    }

    private fun buildTransformer(spec: JobSpec): Transformer {
        val enc = DefaultEncoderFactory.Builder(this)
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder()
                    .setBitrate(spec.audioKbps.coerceAtLeast(64) * 1000)
                    .build()
            )
        if (spec.kind == "compress") {
            enc.setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(compressBitrate(spec)).build()
            )
        }
        val tb = Transformer.Builder(this)
            .setEncoderFactory(enc.build())
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
        if (spec.kind == "compress") tb.setVideoMimeType(MimeTypes.VIDEO_H264)
        return tb.build()
    }

    private fun buildComposition(spec: JobSpec, fastMode: Boolean): Composition {
        return when (spec.kind) {
            "video" -> videoLoop(spec, fastMode)
            "audio" -> audioLoop(spec, fastMode)
            "mix" -> mixSounds(spec)
            "combine" -> combine(spec, fastMode)
            else -> throw IllegalArgumentException("Jenis tugas tidak dikenal")
        }
    }

    private fun videoLoop(spec: JobSpec, fastMode: Boolean): Composition {
        val vUri = spec.videoUri ?: throw IllegalArgumentException("Video belum dipilih")
        val vDur = durationMs(vUri)
        val music = spec.audioUri
        val keepOriginal = music == null || spec.keepOriginalAudio
        val vSeq = EditedMediaItemSequence.withAudioAndVideoFrom(
            loopedItems(vUri, vDur, spec.targetMs, false, !keepOriginal)
        )
        val b = if (music != null) {
            Composition.Builder(vSeq, loopingAudio(music))
        } else {
            Composition.Builder(vSeq)
        }
        b.setTransmuxVideo(fastMode)
        b.setTransmuxAudio(fastMode && music == null)
        return b.build()
    }

    private fun audioLoop(spec: JobSpec, fastMode: Boolean): Composition {
        val aUri = spec.audioUri ?: throw IllegalArgumentException("Audio belum dipilih")
        val aDur = durationMs(aUri)
        val seq = EditedMediaItemSequence.withAudioFrom(
            loopedItems(aUri, aDur, spec.targetMs, true, false)
        )
        return Composition.Builder(seq).setTransmuxAudio(fastMode).build()
    }

    private fun mixSounds(spec: JobSpec): Composition {
        val a = spec.audioUri ?: throw IllegalArgumentException("Sound 1 belum dipilih")
        val b = spec.audio2Uri ?: throw IllegalArgumentException("Sound 2 belum dipilih")
        val seqA = EditedMediaItemSequence.withAudioFrom(
            loopedItems(a, durationMs(a), spec.targetMs, true, false)
        )
        val seqB = EditedMediaItemSequence.withAudioFrom(
            loopedItems(b, durationMs(b), spec.targetMs, true, false)
        )
        return Composition.Builder(seqA, seqB).build()
    }

    private fun combine(spec: JobSpec, fastMode: Boolean): Composition {
        val v = spec.videoUri ?: throw IllegalArgumentException("Video belum dipilih")
        val a = spec.audioUri ?: throw IllegalArgumentException("Audio belum dipilih")
        val vDur = durationMs(v)
        val aDur = durationMs(a)
        val target = when (spec.matchMode) {
            1 -> vDur
            2 -> aDur
            else -> maxOf(vDur, aDur)
        }
        val vSeq = EditedMediaItemSequence.withAudioAndVideoFrom(
            loopedItems(v, vDur, target, false, !spec.keepOriginalAudio)
        )
        val aSeq = EditedMediaItemSequence.withAudioFrom(
            loopedItems(a, aDur, target, true, false)
        )
        return Composition.Builder(vSeq, aSeq).setTransmuxVideo(fastMode).build()
    }

    private fun compressItem(spec: JobSpec): EditedMediaItem {
        val uri = spec.videoUri ?: throw IllegalArgumentException("Video belum dipilih")
        val b = EditedMediaItem.Builder(MediaItem.fromUri(uri))
        if (spec.heightPx > 0) {
            b.setEffects(
                Effects(emptyList(), listOf<Effect>(Presentation.createForHeight(spec.heightPx)))
            )
        }
        return b.build()
    }

    private fun compressBitrate(spec: JobSpec): Int {
        val uri = spec.videoUri ?: throw IllegalArgumentException("Video belum dipilih")
        val dur = durationMs(uri)
        require(dur > 0) { "Durasi video tidak terbaca" }
        val totalBps = spec.targetMb * 8.0 * 1024.0 * 1024.0 / (dur / 1000.0)
        val orig = bitrateOf(uri)
        require(orig <= 0 || totalBps < orig) {
            "Target ${spec.targetMb} MB lebih besar dari video asli. Pilih ukuran lebih kecil."
        }
        return (totalBps - spec.audioKbps * 1000.0).coerceAtLeast(150_000.0).toInt()
    }

    private fun loopedItems(
        uri: Uri,
        durMs: Long,
        targetMs: Long,
        audioOnly: Boolean,
        removeAudio: Boolean
    ): List<EditedMediaItem> {
        require(durMs > 0) { "Durasi file tidak terbaca" }
        val list = ArrayList<EditedMediaItem>()
        var remaining = targetMs
        while (remaining > 300) {
            val take = minOf(durMs, remaining)
            val mb = MediaItem.Builder().setUri(uri)
            if (take < durMs) {
                mb.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0)
                        .setEndPositionMs(take)
                        .build()
                )
            }
            val eb = EditedMediaItem.Builder(mb.build())
            if (removeAudio) eb.setRemoveAudio(true)
            if (audioOnly) eb.setRemoveVideo(true)
            list.add(eb.build())
            remaining -= take
            require(list.size <= 3000) {
                "File terlalu pendek untuk durasi ini. Pakai file yang lebih panjang."
            }
        }
        return list
    }

    private fun loopingAudio(uri: Uri): EditedMediaItemSequence {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setRemoveVideo(true).build()
        return EditedMediaItemSequence.withAudioFrom(listOf(item))
            .buildUpon()
            .setIsLooping(true)
            .build()
    }

    private fun durationMs(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    }

    private fun bitrateOf(uri: Uri): Long {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull() ?: 0L
        } finally {
            r.release()
        }
    }

    // ---------- Hasil ----------

    private fun onExportDone() {
        handler.removeCallbacks(poll)
        JobState.percent = 100
        JobState.message = "Menyimpan ke galeri..."
        notifyProgress("Menyimpan ke galeri...", -1)
        val src = tempFile ?: return
        Thread {
            val ok = try {
                saveToGallery(src)
                true
            } catch (e: Exception) {
                false
            }
            handler.post {
                src.delete()
                tempFile = null
                if (ok) finishOk() else finishWithError("Gagal menyimpan ke galeri")
            }
        }.start()
    }

    private fun onExportFailed(e: ExportException) {
        handler.removeCallbacks(poll)
        val spec = currentSpec
        if (usedFast && spec != null) {
            try {
                runJob(spec, false)
                return
            } catch (x: Exception) {
                finishWithError(x.message ?: "Gagal")
                return
            }
        }
        finishWithError("Gagal: " + (e.message ?: e.errorCodeName))
    }

    private fun saveToGallery(src: File) {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, outName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, if (isAudioOut) "audio/mp4" else "video/mp4")
        values.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (isAudioOut) "Music/RayoLoop" else "Movies/RayoLoop"
        )
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val collection = if (isAudioOut) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Tidak bisa membuat file")
        contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out, 1 shl 20) }
        } ?: throw IllegalStateException("Tidak bisa menulis file")
        val done = ContentValues()
        done.put(MediaStore.MediaColumns.IS_PENDING, 0)
        contentResolver.update(uri, done, null, null)
    }

    private fun finishOk() {
        releaseWake()
        transformer = null
        JobState.active = false
        JobState.finished = true
        JobState.percent = 100
        val folder = if (isAudioOut) "Music/RayoLoop" else "Movies/RayoLoop"
        JobState.message = "Selesai! Hasil ada di folder $folder"
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyDone("Selesai", "Hasil ada di $folder ($outName)")
        stopSelf()
    }

    private fun finishWithError(msg: String) {
        handler.removeCallbacks(poll)
        try {
            transformer?.cancel()
        } catch (_: Exception) {
        }
        transformer = null
        tempFile?.delete()
        tempFile = null
        releaseWake()
        JobState.active = false
        JobState.error = msg
        JobState.message = msg
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifyDone("Gagal", msg)
        stopSelf()
    }

    private fun cancelJob() {
        handler.removeCallbacksAndMessages(null)
        try {
            transformer?.cancel()
        } catch (_: Exception) {
        }
        transformer = null
        tempFile?.delete()
        tempFile = null
        releaseWake()
        JobState.active = false
        JobState.error = null
        JobState.message = "Dibatalkan"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWake() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun makeName(spec: JobSpec): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "RayoLoop_" + spec.label + "_" + stamp + "." + (if (isAudioOut) "m4a" else "mp4")
    }

    // ---------- Notifikasi ----------

    private fun openApp(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
    }

    private fun buildNotif(text: String, percent: Int): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val cancelPi = PendingIntent.getService(
            this, 2, Intent(this, ProcessService::class.java).setAction(ACTION_CANCEL), flags
        )
        @Suppress("DEPRECATION")
        val action = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel, "Batal", cancelPi
        ).build()
        val b = Notification.Builder(this, CH_WORK)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Rayo Loop")
            .setContentText(text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .addAction(action)
        if (percent >= 0) b.setProgress(100, percent, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyProgress(text: String, percent: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_WORK, buildNotif(text, percent))
    }

    private fun notifyDone(title: String, text: String) {
        val n = Notification.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Rayo Loop - $title")
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseWake()
        if (JobState.active) {
            JobState.active = false
            JobState.message = "Proses berhenti tiba-tiba"
            tempFile?.delete()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.rayo.loop.START"
        const val ACTION_CANCEL = "com.rayo.loop.CANCEL"
        private const val CH_WORK = "rayo_work"
        private const val CH_DONE = "rayo_done"
        private const val NOTIF_WORK = 21
        private const val NOTIF_DONE = 22
    }
}
