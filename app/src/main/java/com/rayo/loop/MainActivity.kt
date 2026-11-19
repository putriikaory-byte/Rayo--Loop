package com.rayo.loop

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val BG = Color.parseColor("#0B2A24")
    private val CARD = Color.parseColor("#123B33")
    private val CHIP = Color.parseColor("#1C4D43")
    private val ACC = Color.parseColor("#7CF5C4")
    private val GOLD = Color.parseColor("#FFC857")
    private val MUTED = Color.parseColor("#9DB8B0")
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val ui = Handler(Looper.getMainLooper())
    private var onPicked: ((Uri) -> Unit)? = null

    private lateinit var statusCard: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var cancelBtn: Button
    private val tabButtons = ArrayList<TextView>()
    private val tabPages = ArrayList<View>()

    private val durLabels = listOf("5 menit", "10 menit", "30 menit", "1 jam", "2 jam", "3 jam")
    private val durFile = listOf("5mnt", "10mnt", "30mnt", "1jam", "2jam", "3jam")
    private val durMs = longArrayOf(300_000L, 600_000L, 1_800_000L, 3_600_000L, 7_200_000L, 10_800_000L)

    private val q4Labels = listOf("96 kbps", "128 kbps", "192 kbps", "256 kbps")
    private val q4Vals = listOf(96, 128, 192, 256)
    private val q5Labels = listOf("Asli (tercepat)", "128 kbps", "192 kbps", "256 kbps", "320 kbps")
    private val q5Vals = listOf(0, 128, 192, 256, 320)
    private val mixLabels = listOf("128 kbps", "192 kbps", "256 kbps", "320 kbps")
    private val mixVals = listOf(128, 192, 256, 320)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(0, dp(28), 0, 0)
        setContentView(root)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(16), dp(4), dp(16), dp(4))
        val logo = ImageView(this)
        logo.setImageResource(R.drawable.ic_launcher)
        header.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))
        val title = TextView(this)
        title.text = "  Rayo Loop"
        title.setTextColor(Color.WHITE)
        title.textSize = 22f
        title.setTypeface(null, Typeface.BOLD)
        header.addView(title)
        root.addView(header)

        statusCard = LinearLayout(this)
        statusCard.orientation = LinearLayout.VERTICAL
        statusCard.setPadding(dp(14), dp(10), dp(14), dp(10))
        statusCard.background = rounded(CARD, 14)
        statusCard.visibility = View.GONE
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.setMargins(dp(14), dp(8), dp(14), 0)
        statusText = TextView(this)
        statusText.setTextColor(Color.WHITE)
        statusText.textSize = 14f
        statusCard.addView(statusText)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        statusCard.addView(progress, LinearLayout.LayoutParams(MATCH, WRAP))
        cancelBtn = Button(this)
        cancelBtn.text = "Batalkan"
        cancelBtn.setOnClickListener {
            startService(Intent(this, ProcessService::class.java).setAction(ProcessService.ACTION_CANCEL))
        }
        statusCard.addView(cancelBtn)
        root.addView(statusCard, slp)

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val pages = listOf(
            buildVideoTab(), buildAudioTab(), buildMixTab(), buildCompressTab(), buildCombineTab()
        )
        for (p in pages) {
            content.addView(p, FrameLayout.LayoutParams(MATCH, MATCH))
            tabPages.add(p)
        }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(CARD)
        val names = listOf("Video", "Audio", "Mix", "Kompres", "Gabung")
        for ((i, n) in names.withIndex()) {
            val t = TextView(this)
            t.text = n
            t.gravity = Gravity.CENTER
            t.textSize = 14f
            t.setPadding(0, dp(14), 0, dp(14))
            t.setOnClickListener { selectTab(i) }
            bar.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
            tabButtons.add(t)
        }
        root.addView(bar)
        selectTab(0)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
        }
    }

    // ---------- Tab ----------

    private fun buildVideoTab(): View = page { col ->
        var vUri: Uri? = null
        var mUri: Uri? = null
        var dIdx = 0
        var kIdx = 2
        val c1 = card(col, "1. Video sumber")
        pickerRow(c1, "video/*", "Belum ada video") { vUri = it }
        val c2 = card(col, "2. Durasi hasil")
        choice(c2, durLabels, 0, 3) { dIdx = it }
        val c3 = card(col, "3. Musik / backsound (opsional)")
        val sw = Switch(this)
        sw.text = "Tambah musik loop"
        sw.setTextColor(Color.WHITE)
        c3.addView(sw)
        pickerRow(c3, "audio/*", "Belum ada musik") { mUri = it }
        val keep = Switch(this)
        keep.text = "Campur dengan suara asli video"
        keep.setTextColor(Color.WHITE)
        c3.addView(keep)
        val c4 = card(col, "Kualitas suara musik")
        choice(c4, q4Labels, 2, 4) { kIdx = it }
        startBtn(col, "MULAI BUAT VIDEO LOOP") {
            val v = vUri
            val m = if (sw.isChecked) mUri else null
            if (v == null) {
                toast("Pilih video dulu")
            } else if (sw.isChecked && m == null) {
                toast("Pilih file musiknya")
            } else {
                start(
                    JobSpec(
                        kind = "video", videoUri = v, audioUri = m,
                        targetMs = durMs[dIdx], audioKbps = q4Vals[kIdx],
                        keepOriginalAudio = keep.isChecked,
                        label = "video_" + durFile[dIdx]
                    )
                )
            }
        }
    }

    private fun buildAudioTab(): View = page { col ->
        var aUri: Uri? = null
        var dIdx = 0
        var kIdx = 2
        val c1 = card(col, "1. Audio sumber")
        pickerRow(c1, "audio/*", "Belum ada audio") { aUri = it }
        val c2 = card(col, "2. Durasi hasil")
        choice(c2, durLabels, 0, 3) { dIdx = it }
        val c3 = card(col, "3. Kualitas suara")
        choice(c3, q5Labels, 2, 3) { kIdx = it }
        startBtn(col, "MULAI BUAT AUDIO LOOP") {
            val a = aUri
            if (a == null) {
                toast("Pilih audio dulu")
            } else {
                start(
                    JobSpec(
                        kind = "audio", audioUri = a, targetMs = durMs[dIdx],
                        audioKbps = q5Vals[kIdx], label = "audio_" + durFile[dIdx]
                    )
                )
            }
        }
    }

    private fun buildMixTab(): View = page { col ->
        var aUri: Uri? = null
        var bUri: Uri? = null
        var dIdx = 0
        var kIdx = 1
        val c1 = card(col, "1. Sound pertama")
        pickerRow(c1, "audio/*", "Belum ada audio") { aUri = it }
        val c2 = card(col, "2. Sound kedua")
        pickerRow(c2, "audio/*", "Belum ada audio") { bUri = it }
        val c3 = card(col, "3. Durasi hasil")
        choice(c3, durLabels, 0, 3) { dIdx = it }
        val c4 = card(col, "4. Kualitas suara")
        choice(c4, mixLabels, 1, 4) { kIdx = it }
        startBtn(col, "MULAI GABUNG DUA SOUND") {
            val a = aUri
            val b = bUri
            if (a == null || b == null) {
                toast("Pilih kedua sound dulu")
            } else {
                start(
                    JobSpec(
                        kind = "mix", audioUri = a, audio2Uri = b, targetMs = durMs[dIdx],
                        audioKbps = mixVals[kIdx], label = "mix_" + durFile[dIdx]
                    )
                )
            }
        }
    }

    private fun buildCompressTab(): View = page { col ->
        var vUri: Uri? = null
        var sIdx = 2
        var rIdx = 0
        var kIdx = 1
        val mbVals = listOf(10, 25, 50, 100, 250, 500)
        val hVals = listOf(0, 1080, 720, 480)
        val c1 = card(col, "1. Video sumber")
        pickerRow(c1, "video/*", "Belum ada video") { vUri = it }
        val c2 = card(col, "2. Ukuran target")
        choice(c2, mbVals.map { "$it MB" }, 2, 3) { sIdx = it }
        val c3 = card(col, "3. Resolusi")
        choice(c3, listOf("Asli", "1080p", "720p", "480p"), 0, 4) { rIdx = it }
        val c4 = card(col, "4. Kualitas suara")
        choice(c4, listOf("96 kbps", "128 kbps", "192 kbps"), 1, 3) { kIdx = it }
        val note = TextView(this)
        note.text = "Tips: makin kecil ukuran target, makin turun kualitas gambar. " +
            "Untuk hasil terbaik biarkan resolusi Asli dan pilih ukuran yang tidak terlalu kecil."
        note.setTextColor(MUTED)
        note.textSize = 12f
        note.setPadding(dp(4), dp(10), dp(4), 0)
        col.addView(note)
        startBtn(col, "MULAI KOMPRES") {
            val v = vUri
            if (v == null) {
                toast("Pilih video dulu")
            } else {
                start(
                    JobSpec(
                        kind = "compress", videoUri = v, targetMb = mbVals[sIdx],
                        heightPx = hVals[rIdx], audioKbps = listOf(96, 128, 192)[kIdx],
                        label = "kompres_" + mbVals[sIdx] + "MB"
                    )
                )
            }
        }
    }

    private fun buildCombineTab(): View = page { col ->
        var vUri: Uri? = null
        var aUri: Uri? = null
        var mIdx = 0
        var kIdx = 1
        val c1 = card(col, "1. Video")
        pickerRow(c1, "video/*", "Belum ada video") { vUri = it }
        val c2 = card(col, "2. Suara / musik")
        pickerRow(c2, "audio/*", "Belum ada audio") { aUri = it }
        val c3 = card(col, "3. Samakan durasi dengan")
        choice(c3, listOf("Terpanjang", "Video", "Audio"), 0, 3) { mIdx = it }
        val keep = Switch(this)
        keep.text = "Campur dengan suara asli video"
        keep.setTextColor(Color.WHITE)
        c3.addView(keep)
        val c4 = card(col, "4. Kualitas suara")
        choice(c4, mixLabels, 1, 4) { kIdx = it }
        startBtn(col, "MULAI GABUNG VIDEO + SUARA") {
            val v = vUri
            val a = aUri
            if (v == null || a == null) {
                toast("Pilih video dan suara dulu")
            } else {
                start(
                    JobSpec(
                        kind = "combine", videoUri = v, audioUri = a, matchMode = mIdx,
                        audioKbps = mixVals[kIdx], keepOriginalAudio = keep.isChecked,
                        label = "gabung"
                    )
                )
            }
        }
    }

    // ---------- Mulai tugas ----------

    private fun start(spec: JobSpec) {
        if (JobState.active) {
            toast("Masih ada proses berjalan")
            return
        }
        JobState.spec = spec
        JobState.finished = false
        JobState.error = null
        JobState.percent = 0
        JobState.message = "Menyiapkan..."
        val i = Intent(this, ProcessService::class.java).setAction(ProcessService.ACTION_START)
        startForegroundService(i)
    }

    // ---------- Status ----------

    private val tick = object : Runnable {
        override fun run() {
            val show = JobState.active || JobState.finished || JobState.error != null ||
                JobState.message == "Dibatalkan"
            statusCard.visibility = if (show) View.VISIBLE else View.GONE
            var msg = JobState.message
            if (JobState.active && JobState.percent > 0) msg += " (" + JobState.percent + "%)"
            statusText.text = msg
            progress.isIndeterminate = JobState.active && JobState.percent <= 0
            progress.progress = JobState.percent
            progress.visibility = if (JobState.active) View.VISIBLE else View.GONE
            cancelBtn.visibility = if (JobState.active) View.VISIBLE else View.GONE
            ui.postDelayed(this, 700)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    // ---------- Pilih file ----------

    private fun pickFile(mime: String, cb: (Uri) -> Unit) {
        onPicked = cb
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = mime
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        startActivityForResult(i, 77)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
            onPicked?.invoke(uri)
        }
    }

    private fun nameOf(uri: Uri): String {
        var name = uri.lastPathSegment ?: "file"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
            }
        } catch (_: Exception) {
        }
        return name
    }

    // ---------- Komponen tampilan ----------

    private fun selectTab(i: Int) {
        tabPages.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { idx, t ->
            t.setTextColor(if (idx == i) ACC else MUTED)
            t.setTypeface(null, if (idx == i) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun page(builder: (LinearLayout) -> Unit): ScrollView {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14), dp(6), dp(14), dp(24))
        builder(col)
        sv.addView(col)
        return sv
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14), dp(12), dp(14), dp(12))
        c.background = rounded(CARD, 16)
        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.setMargins(0, dp(10), 0, 0)
        val t = TextView(this)
        t.text = title
        t.setTextColor(ACC)
        t.textSize = 15f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, 0, 0, dp(6))
        c.addView(t)
        parent.addView(c, lp)
        return c
    }

    private fun pickerRow(parent: LinearLayout, mime: String, hint: String, onUri: (Uri) -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val name = TextView(this)
        name.text = hint
        name.setTextColor(MUTED)
        name.textSize = 14f
        row.addView(name, LinearLayout.LayoutParams(0, WRAP, 1f))
        val btn = Button(this)
        btn.text = "Pilih"
        btn.setOnClickListener {
            pickFile(mime) { uri ->
                name.text = nameOf(uri)
                name.setTextColor(Color.WHITE)
                onUri(uri)
            }
        }
        row.addView(btn)
        parent.addView(row)
    }

    private fun choice(
        parent: LinearLayout,
        labels: List<String>,
        def: Int,
        perRow: Int,
        onChange: (Int) -> Unit
    ) {
        var selected = def
        val views = ArrayList<TextView>()
        fun paint() {
            views.forEachIndexed { i, v ->
                v.background = rounded(if (i == selected) ACC else CHIP, 20)
                v.setTextColor(if (i == selected) Color.BLACK else Color.WHITE)
            }
        }
        var row: LinearLayout? = null
        for ((i, l) in labels.withIndex()) {
            if (i % perRow == 0) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                parent.addView(r)
                row = r
            }
            val tv = TextView(this)
            tv.text = l
            tv.gravity = Gravity.CENTER
            tv.textSize = 13f
            tv.setPadding(dp(6), dp(12), dp(6), dp(12))
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            tv.setOnClickListener {
                selected = i
                paint()
                onChange(i)
            }
            row?.addView(tv, lp)
            views.add(tv)
        }
        paint()
        onChange(def)
    }

    private fun startBtn(parent: LinearLayout, text: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = text
        b.setTextColor(Color.BLACK)
        b.setTypeface(null, Typeface.BOLD)
        b.background = rounded(GOLD, 14)
        val lp = LinearLayout.LayoutParams(MATCH, dp(54))
        lp.setMargins(0, dp(18), 0, 0)
        parent.addView(b, lp)
        b.setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        return g
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
