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
    val label: String = "hasil",
    val vClip: LongArray? = null,
    val aClip: LongArray? = null,
    val a2Clip: LongArray? = null,
    val seamless: Boolean = false,
    val vFullMs: Long = 0L
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
    private var segFile: File? = null
    private var outName = ""
    private var isAudioOut = false
    private var usedFast = false
    private var usedSegment = false
    private var phase = 0
    private var vFullField = 0L
    private var currentSpec: JobSpec? = null
    private val progressHolder = ProgressHolder()

    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                val raw = progressHolder.progress
                val pct = when {
                    usedSegment && phase == 1 -> raw / 4
                    usedSegment -> 25 + raw * 3 / 4
                    else -> raw
                }
                JobState.percent = pct
                notifyProgress("Memproses $pct%", pct)
            }
            handler.postDelayed(this, 700)
        }
    }

    private val listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            if (phase == 1) afterSegment() else onExportDone()
        }

        override fun onError(
            composition: Composition,
            exportResult: ExportResult,
            exportException: ExportException
        ) {
            if (phase == 1) {
                handler.removeCallbacks(poll)
                finishWithError(
                    "Gagal menyiapkan video: " +
                        (exportException.message ?: exportException.errorCodeName)
                )
            } else {
                onExportFailed(exportException)
            }
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
        val dir = externalCacheDir ?: cacheDir
        tempFile = File(dir, "rayo_tmp." + (if (isAudioOut) "m4a" else "mp4"))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RayoLoop:job").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
        try {
            begin(spec)
        } catch (e: Exception) {
            finishWithError(e.message ?: "Gagal memulai")
        }
        return START_NOT_STICKY
    }

    // ---------- Alur tugas ----------

    private fun defaultFast(spec: JobSpec): Boolean = when (spec.kind) {
        "video", "combine" -> true
        "audio" -> spec.audioKbps == 0 && !spec.seamless
        else -> false
    }

    private fun begin(spec: JobSpec) {
        usedSegment = false
        val vu = spec.videoUri
        if ((spec.kind == "video" || spec.kind == "combine") && vu != null) {
            val dur = durationMs(vu)
            val c = fixClip(spec.vClip, dur)
            val clipNeeded = c != null && !(c[0] < 100L && c[1] >= dur - 100L)
            val scaleNeeded = spec.heightPx > 0 && videoHeight(vu) > spec.heightPx
            if (clipNeeded || scaleNeeded) {
                usedSegment = true
                startSegment(spec, if (clipNeeded) c else null, dur)
                return
            }
        }
        phase = 2
        runJob(spec, defaultFast(spec))
    }

    // Tahap 1: potong dan/atau perkecil video sekali saja
    private fun startSegment(spec: JobSpec, clip: LongArray?, fullMs: Long) {
        phase = 1
        vFullField = fullMs
        val uri = spec.videoUri ?: throw IllegalArgumentException("Video belum dipilih")
        val seg = File(externalCacheDir ?: cacheDir, "rayo_seg.mp4")
        if (seg.exists()) seg.delete()
        segFile = seg
        val origH = videoHeight(uri)
        val scale = spec.heightPx > 0 && origH > spec.heightPx
        val ratio = if (scale) spec.heightPx.toDouble() / origH else 1.0
        val orig = bitrateOf(uri)
        val base = if (orig > 0) orig.toDouble() else 8_000_000.0
        val br = (base * 1.2 * ratio * ratio).coerceIn(1_000_000.0, 40_000_000.0).toInt()

        val mb = MediaItem.Builder().setUri(uri)
        if (clip != null) {
            mb.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip[0])
                    .setEndPositionMs(clip[1])
                    .build()
            )
        }
        val eb = EditedMediaItem.Builder(mb.build())
        if (scale) {
            eb.setEffects(
                Effects(emptyList(), listOf<Effect>(Presentation.createForHeight(spec.heightPx)))
            )
        }
        val enc = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(br).build())
            .build()
        val t = Transformer.Builder(this)
            .setEncoderFactory(enc)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(listener)
            .build()
        transformer = t
        JobState.message = "Menyiapkan potongan video..."
        t.start(eb.build(), seg.absolutePath)
        schedulePoll()
    }

    // Tahap 2: ulang potongan itu tanpa encode ulang
    private fun afterSegment() {
        handler.removeCallbacks(poll)
        val seg = segFile
        val s = currentSpec
        if (seg == null || s == null) {
            finishWithError("Potongan video hilang")
            return
        }
        val ns = s.copy(
            videoUri = Uri.fromFile(seg), vClip = null, heightPx = 0, vFullMs = vFullField
        )
        currentSpec = ns
        phase = 2
        try {
            runJob(ns, defaultFast(ns))
        } catch (e: Exception) {
            finishWithError(e.message ?: "Gagal")
        }
    }

    private fun schedulePoll() {
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 700)
    }

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
        schedulePoll()
    }

    private fun buildTransformer(spec: JobSpec): Transformer {
        val kb = if (spec.audioKbps <= 0) 192 else spec.audioKbps.coerceAtLeast(64)
        val enc = DefaultEncoderFactory.Builder(this)
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder().setBitrate(kb * 1000).build()
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
            loopedItems(vUri, vDur, spec.targetMs, false, !keepOriginal, null)
        )
        val b = if (music != null) {
            Composition.Builder(vSeq, loopingAudio(music, fixClip(spec.aClip, durationMs(music))))
        } else {
            Composition.Builder(vSeq)
        }
        b.setTransmuxVideo(fastMode)
        b.setTransmuxAudio(fastMode && music == null && !spec.seamless)
        return b.build()
    }

    private fun audioLoop(spec: JobSpec, fastMode: Boolean): Composition {
        val aUri = spec.audioUri ?: throw IllegalArgumentException("Audio belum dipilih")
        val seq = EditedMediaItemSequence.withAudioFrom(
            loopedItems(aUri, durationMs(aUri), spec.targetMs, true, false, spec.aClip)
        )
        return Composition.Builder(seq).setTransmuxAudio(fastMode).build()
    }

    private fun mixSounds(spec: JobSpec): Composition {
        val a = spec.audioUri ?: throw IllegalArgumentException("Sound 1 belum dipilih")
        val b = spec.audio2Uri ?: throw IllegalArgumentException("Sound 2 belum dipilih")
        val seqA = EditedMediaItemSequence.withAudioFrom(
            loopedItems(a, durationMs(a), spec.targetMs, true, false, spec.aClip)
        )
        val seqB = EditedMediaItemSequence.withAudioFrom(
            loopedItems(b, durationMs(b), spec.targetMs, true, false, spec.a2Clip)
        )
        return Composition.Builder(seqA, seqB).build()
    }

    private fun combine(spec: JobSpec, fastMode: Boolean): Composition {
        val v = spec.videoUri ?: throw IllegalArgumentException("Video belum dipilih")
        val a = spec.audioUri ?: throw IllegalArgumentException("Audio belum dipilih")
        val vFull = if (spec.vFullMs > 0) spec.vFullMs else durationMs(v)
        val aFull = durationMs(a)
        val target = when (spec.matchMode) {
            1 -> vFull
            2 -> aFull
            else -> maxOf(vFull, aFull)
        }
        val vSeq = EditedMediaItemSequence.withAudioAndVideoFrom(
            loopedItems(v, durationMs(v), target, false, !spec.keepOriginalAudio, null)
        )
        val aSeq = EditedMediaItemSequence.withAudioFrom(
            loopedItems(a, aFull, target, true, false, spec.aClip)
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

    // ---------- Pembantu ----------

    private fun fixClip(c: LongArray?, durMs: Long): LongArray? {
        if (c == null) return null
        val s = c[0].coerceAtLeast(0L)
        val e = if (c[1] <= 0L || c[1] > durMs) durMs else c[1]
        require(e - s >= 500L) { "Bagian yang dipilih terlalu pendek (minimal setengah detik)" }
        return longArrayOf(s, e)
    }

    private fun loopedItems(
        uri: Uri,
        durMs: Long,
        targetMs: Long,
        audioOnly: Boolean,
        removeAudio: Boolean,
        clip: LongArray?
    ): List<EditedMediaItem> {
        require(durMs > 0) { "Durasi file tidak terbaca" }
        val c = fixClip(clip, durMs)
        val cs = c?.get(0) ?: 0L
        val seg = (c?.get(1) ?: durMs) - cs
        val count = (targetMs + seg - 1) / seg
        require(count <= 6000) {
            "Potongan loop terlalu pendek (${seg / 1000} detik) untuk durasi ini. Pilih durasi lebih pendek."
        }
        val list = ArrayList<EditedMediaItem>()
        var remaining = targetMs
        while (remaining > 300) {
            val take = minOf(seg, remaining)
            val mb = MediaItem.Builder().setUri(uri)
            if (cs > 0L || take < durMs) {
                mb.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(cs)
                        .setEndPositionMs(cs + take)
                        .build()
                )
            }
            val eb = EditedMediaItem.Builder(mb.build())
            if (removeAudio) eb.setRemoveAudio(true)
            if (audioOnly) eb.setRemoveVideo(true)
            list.add(eb.build())
            remaining -= take
        }
        return list
    }

    private fun loopingAudio(uri: Uri, clip: LongArray?): EditedMediaItemSequence {
        val mb = MediaItem.Builder().setUri(uri)
        if (clip != null) {
            mb.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip[0])
                    .setEndPositionMs(clip[1])
                    .build()
            )
        }
        val item = EditedMediaItem.Builder(mb.build()).setRemoveVideo(true).build()
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

    private fun videoHeight(uri: Uri): Int {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            return if (rot == 90 || rot == 270) w else h
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

    private fun cleanSeg() {
        try {
            segFile?.delete()
        } catch (_: Exception) {
        }
        segFile = null
    }

    private fun finishOk() {
        releaseWake()
        cleanSeg()
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
        cleanSeg()
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
        cleanSeg()
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
            cleanSeg()
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
