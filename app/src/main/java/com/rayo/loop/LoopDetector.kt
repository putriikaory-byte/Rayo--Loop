package com.rayo.loop

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class LoopPoint(val startMs: Long, val endMs: Long, val similarity: Int)

object LoopDetector {

    private class Pcm(val data: FloatArray, val rate: Double, val t0Ms: Double)

    fun durationOf(ctx: Context, uri: Uri): Long {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            return r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            return 0L
        } finally {
            r.release()
        }
    }

    // ======================= SUARA =======================

    private fun decode(ctx: Context, uri: Uri, fromMs: Long, toMs: Long): Pcm? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) {
                val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            if (fromMs > 0) ex.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var k = max(1, sr / 8000)
            var out = FloatArray(1 shl 18)
            var n = 0
            var acc = 0f
            var cnt = 0
            var t0 = -1.0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = c.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (size < 0 || (toMs >= 0 && t > toMs * 1000)) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, size, t, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    if (info.size > 0) {
                        if (t0 < 0) t0 = info.presentationTimeUs / 1000.0
                        val ob = c.getOutputBuffer(oi)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                        while (sb.remaining() >= ch) {
                            var s = 0f
                            for (q in 0 until ch) s += sb.get()
                            acc += s / ch
                            cnt++
                            if (cnt >= k) {
                                if (n == out.size) out = out.copyOf(n * 2)
                                out[n++] = acc / cnt / 32768f
                                acc = 0f
                                cnt = 0
                            }
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    k = max(1, sr / 8000)
                }
            }
            if (n < 2000 || t0 < 0) return null
            return Pcm(out.copyOf(n), sr.toDouble() / k, t0)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            ex.release()
        }
    }

    // Dua fitur per potongan 25 ms: kekuatan suara rendah dan tinggi (skala log)
    private fun feats(x: FloatArray, hop: Int): FloatArray {
        val n = x.size / hop
        val out = FloatArray(n * 2)
        var lp = 0f
        for (f in 0 until n) {
            var el = 0f
            var eh = 0f
            for (k in 0 until hop) {
                val v = x[f * hop + k]
                lp += 0.25f * (v - lp)
                val hi = v - lp
                el += lp * lp
                eh += hi * hi
            }
            out[f * 2] = ln(sqrt(el / hop) + 1e-4f)
            out[f * 2 + 1] = ln(sqrt(eh / hop) + 1e-4f)
        }
        return out
    }

    fun detectAudio(ctx: Context, uri: Uri, say: (String) -> Unit): LoopPoint? {
        val dur = durationOf(ctx, uri)
        if (dur < 4000) return null
        say("Membaca suara...")
        val short = dur <= 70_000L
        val region = min(30_000L, dur * 45 / 100)
        val head: Pcm
        val tail: Pcm
        if (short) {
            val all = decode(ctx, uri, 0L, -1L) ?: return null
            head = all
            tail = all
        } else {
            head = decode(ctx, uri, 0L, region + 1000L) ?: return null
            tail = decode(ctx, uri, dur - region - 1000L, -1L) ?: return null
        }
        say("Mencari titik loop...")
        val hop = max(1, (head.rate * 0.025).toInt())
        val fh = feats(head.data, hop)
        val ft = if (tail === head) fh else feats(tail.data, hop)
        val nA = fh.size / 2
        val nB = ft.size / 2
        if (nA < 40 || nB < 40) return null
        val frameMs = hop * 1000.0 / head.rate
        val w = 16
        val minLen = max(2000.0, dur * 0.4)
        val sMax = if (short) dur * 0.45 else region.toDouble()
        val eMin = if (short) dur * 0.55 else (dur - region).toDouble()
        var best = Double.MAX_VALUE
        var bi = -1
        var bj = -1
        var bd = 0.0
        for (i in 0 until nA) {
            val ts = head.t0Ms + i * frameMs
            if (ts > sMax) break
            for (j in w..nB) {
                val te = tail.t0Ms + j * frameMs
                if (te < eMin || te - ts < minLen) continue
                var pp = 0f
                for (q in 1..w) {
                    val e0 = (j - q) * 2
                    val s0 = max(0, i - q) * 2
                    pp += abs(ft[e0] - fh[s0]) + abs(ft[e0 + 1] - fh[s0 + 1])
                }
                pp /= (w * 2)
                var jn = 0f
                for (q in 1..4) {
                    val e0 = (j - q) * 2
                    val s0 = min(nA - 1, i + q - 1) * 2
                    jn += abs(ft[e0] - fh[s0]) + abs(ft[e0 + 1] - fh[s0 + 1])
                }
                jn /= 8
                val d = 0.6 * pp + 0.4 * jn
                val lost = max(0.0, ts + (dur - te)) / dur
                val score = d + 0.25 * lost
                if (score < best) {
                    best = score
                    bi = i
                    bj = j
                    bd = d
                }
            }
        }
        if (bi < 0) return null

        // Haluskan: geser titik akhir +-30 ms agar gelombangnya menyambung
        val rate = head.rate
        val sIdx = bi * hop
        val eIdx = min(bj * hop, tail.data.size)
        val len = 800
        var bestDelta = 0
        if (sIdx >= len) {
            var bc = -2.0
            for (dl in -240..240) {
                val ee = eIdx + dl
                if (ee - len < 0 || ee > tail.data.size) continue
                var dot = 0.0
                var na = 0.0
                var nb = 0.0
                for (q in 1..len) {
                    val a = head.data[sIdx - q].toDouble()
                    val b = tail.data[ee - q].toDouble()
                    dot += a * b
                    na += a * a
                    nb += b * b
                }
                val c = dot / sqrt(na * nb + 1e-9)
                if (c > bc) {
                    bc = c
                    bestDelta = dl
                }
            }
        }
        var sMs = head.t0Ms + sIdx * 1000.0 / rate
        var eMs = tail.t0Ms + (eIdx + bestDelta) * 1000.0 / rate
        if (sMs < 80) sMs = 0.0
        if (eMs > dur - 80) eMs = dur.toDouble()
        if (eMs - sMs < 1000) return null
        val sim = ((1.0 - bd / 0.5) * 100).toInt().coerceIn(0, 100)
        return LoopPoint(sMs.toLong(), eMs.toLong(), sim)
    }

    // ======================= VIDEO =======================

    private fun frameAt(r: MediaMetadataRetriever, ms: Long): FloatArray? {
        try {
            val b = r.getScaledFrameAtTime(
                ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 32, 18
            ) ?: return null
            val s = if (b.width == 32 && b.height == 18) b else Bitmap.createScaledBitmap(b, 32, 18, true)
            val px = IntArray(32 * 18)
            s.getPixels(px, 0, 32, 0, 0, 32, 18)
            val out = FloatArray(px.size)
            for (i in px.indices) {
                val c = px[i]
                out[i] = (((c shr 16) and 255) * 0.3f + ((c shr 8) and 255) * 0.59f +
                    (c and 255) * 0.11f) / 255f
            }
            return out
        } catch (e: Exception) {
            return null
        }
    }

    private fun grab(
        ctx: Context, uri: Uri, times: List<Long>, tick: () -> Unit
    ): Map<Long, FloatArray> {
        val res = arrayOfNulls<FloatArray>(times.size)
        val threads = ArrayList<Thread>()
        val count = 2
        for (t in 0 until count) {
            val th = Thread {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ctx, uri)
                    var idx = t
                    while (idx < times.size) {
                        res[idx] = frameAt(r, times[idx])
                        tick()
                        idx += count
                    }
                } catch (_: Exception) {
                } finally {
                    r.release()
                }
            }
            th.start()
            threads.add(th)
        }
        for (th in threads) th.join()
        val map = HashMap<Long, FloatArray>()
        for (i in times.indices) {
            val f = res[i]
            if (f != null) map[times[i]] = f
        }
        return map
    }

    private fun frameDist(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += abs(a[i] - b[i])
        return s / a.size
    }

    private class Best(val ts: Long, val te: Long, val d: Double)

    private fun searchPairs(
        sTimes: List<Long>, eTimes: List<Long>, frames: Map<Long, FloatArray>,
        step: Long, minLen: Long, dur: Long
    ): Best? {
        var best: Best? = null
        var bestScore = Double.MAX_VALUE
        for (ts in sTimes) {
            val a0 = frames[ts] ?: continue
            for (te in eTimes) {
                if (te - ts < minLen) continue
                val b0 = frames[te] ?: continue
                var sum = frameDist(a0, b0)
                var n = 1
                for (k in 1..2) {
                    val a = frames[ts - k * step]
                    val b = frames[te - k * step]
                    if (a != null && b != null) {
                        sum += frameDist(a, b)
                        n++
                    }
                }
                val d = sum / n
                val lost = (ts + max(0L, dur - te)).toDouble() / dur
                val score = d + 0.02 * lost
                if (score < bestScore) {
                    bestScore = score
                    best = Best(ts, te, d)
                }
            }
        }
        return best
    }

    fun detectVideo(ctx: Context, uri: Uri, say: (String) -> Unit): LoopPoint? {
        val dur = durationOf(ctx, uri)
        if (dur < 2000) return null
        val region = min(15_000L, dur * 45 / 100)
        val step = 250L
        val set = java.util.TreeSet<Long>()
        var t = 0L
        while (t <= region) {
            set.add(t)
            t += step
        }
        t = dur - region
        while (t <= dur - 100) {
            set.add(t)
            t += step
        }
        val times = ArrayList<Long>(set)
        val total = times.size
        val done = AtomicInteger(0)
        say("Menganalisis video... 0%")
        val frames = grab(ctx, uri, times) {
            say("Menganalisis video... " + (done.incrementAndGet() * 85 / total) + "%")
        }
        if (frames.size < 10) return null
        val minLen = min(max(1500L, dur * 40 / 100), dur * 60 / 100)
        val coarse = searchPairs(
            times.filter { it <= region }, times.filter { it >= dur - region },
            frames, step, minLen, dur
        ) ?: return null

        say("Mempertajam titik loop... 90%")
        fun window(c: Long): List<Long> {
            val l = ArrayList<Long>()
            var x = c - 320
            while (x <= c + 240) {
                if (x >= 0 && x <= dur - 100) l.add(x)
                x += 40
            }
            return l
        }
        val ws = window(coarse.ts)
        val we = window(coarse.te)
        val fineTimes = (ws + we).toSortedSet().toList()
        val fine = grab(ctx, uri, fineTimes) { }
        val fb = searchPairs(
            ws.filter { it >= coarse.ts - 240 }, we.filter { it >= coarse.te - 240 },
            fine, 40L, minLen, dur
        ) ?: coarse
        var s = fb.ts
        var e = fb.te
        if (s < 150) s = 0
        if (e > dur - 150) e = dur
        val sim = ((1.0 - fb.d / 0.10) * 100).toInt().coerceIn(0, 100)
        return LoopPoint(s, e, sim)
    }
}
