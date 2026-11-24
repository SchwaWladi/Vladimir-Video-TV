package com.vladimir.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.res.Configuration
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.net.URLEncoder
import android.content.pm.ActivityInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import android.text.format.DateFormat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlin.concurrent.thread

class MainActivity : Activity() {

    data class Entry(
        val title: String,
        val url: String,
        val group: String,
        val logo: String = "",
        val type: String = "tv",
        val epgId: String = "",
        val archiveDays: Int = 0,
        val archiveBase: String = "",
        val archiveStreamId: String = "",
        val description: String = "",
        val catchupSource: String = "",
        val portalRequest: String = "",
        val portalCollection: Boolean = false,
        val videoMetadata: String = ""
    )

    private val prefs by lazy {
        getSharedPreferences("vladimir2", MODE_PRIVATE)
    }

    private val items = mutableListOf<Entry>()
    private val vodItems = mutableListOf<Entry>()
    private val vodPlaylistItems = linkedMapOf<String, List<Entry>>()
    private var vodLoadGeneration = 0
    private var currentEntry: Entry? = null
    private var catalogGroup: String? = null
    @Volatile private var epgLoadGeneration = 0
    private val epgCacheLock = Any()
    private var epgStatusText = ""
    private var epgStatusView: TextView? = null
    data class Programme(val channel: String, val start: Long, val stop: Long, val title: String, val description: String)
    private val epgSchedule = mutableMapOf<String, MutableList<Programme>>()
    private var epgAliases = emptyMap<String, String>()
    private var epgIcons = emptyMap<String, String>()
    private val qualitySuffix = Regex("""(?i)(?:\s+(?:UHD|FHD|HD|SD|4K|BY|RU|DE))+$""")
    private data class GuideFeed(
        val programmes: Map<String, MutableList<Programme>>,
        val names: Map<String, Set<String>>,
        val icons: Map<String, String>
    )
    private var playerFrame: FrameLayout? = null
    private var sidePanel: View? = null
    private var sidePanelBack: (() -> Unit)? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val lists = mutableListOf<Pair<String, String>>()

    private var current = "home"
    private var lang = "ru"
    private var activeList = -1

    private lateinit var base: LinearLayout
    private lateinit var content: LinearLayout

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var playbackErrorPanel: View? = null
    private var resumePlaybackOnStart = false
    private var playingVideo: Entry? = null
    private val saveVideoProgress = object : Runnable {
        override fun run() {
            if (current != "player" || playingVideo == null) return
            rememberVideoProgress()
            clockHandler.postDelayed(this, 15000L)
        }
    }

    @Volatile private var remoteServer: ServerSocket? = null
    private val remoteToken = UUID.randomUUID()
        .toString().replace("-", "")

    @Volatile private var remoteStarting = false
    @Volatile private var remoteRunning = false

    private val bg = Color.rgb(12, 17, 29)
    private val panel = Color.rgb(28, 36, 53)
    private val accent: Int
        get() = when (prefs.getString("theme", "blue")) {
            "red" -> Color.rgb(229, 35, 66)
            "green" -> Color.rgb(34, 171, 111)
            "purple" -> Color.rgb(148, 90, 222)
            "gold" -> Color.rgb(211, 155, 50)
            "sky" -> Color.rgb(102, 209, 255)
            else -> Color.rgb(48, 124, 232)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun isPhone(): Boolean {
        val mode = resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK
        val width = resources.displayMetrics.widthPixels /
            resources.displayMetrics.density
        return mode != Configuration.UI_MODE_TYPE_TELEVISION &&
            !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) &&
            !packageManager.hasSystemFeature("amazon.hardware.fire_tv") && width < 700
    }

    private fun tr(ru: String, de: String, en: String): String =
        when (lang) {
            "de" -> de
            "en" -> en
            else -> ru
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isPhone()) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }

        lang = prefs.getString("language", "ru") ?: "ru"

        try {
            val stored = prefs.getString("playlists", "[]") ?: "[]"
            val array = JSONArray(stored)

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                lists.add(
                    obj.optString("name") to obj.optString("url")
                )
            }
        } catch (_: Exception) {
        }

        activeList = prefs.getInt(
            "active",
            if (lists.isEmpty()) -1 else 0
        )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(45_000, 75_000, 5_000, 8_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val entry = currentEntry ?: return
                if (isPlaying && current == "player" && entry.type == "tv") {
                    val updated = (listOf(entry.url) + channelHistory().filterNot { it == entry.url }).take(30)
                    prefs.edit().putString("tv_history", JSONArray(updated).toString()).apply()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                clockHandler.removeCallbacks(bufferTimeout)
                if (playbackState == Player.STATE_BUFFERING && current == "player") {
                    clockHandler.postDelayed(bufferTimeout, 25_000L)
                }
                if (playbackState == Player.STATE_ENDED) rememberVideoProgress(completed = true)
            }

            override fun onPlayerError(error: PlaybackException) {
                clockHandler.removeCallbacks(bufferTimeout)
                showPlaybackError(error.errorCodeName)
            }
        })

        startRemoteServer()
        showHome()

        if (activeList in lists.indices) {
            loadPlaylist(activeList)
        }
        loadVodPlaylist()
        if (prefs.getString("xtream_server", "").orEmpty().isNotBlank()) loadXtream()
    }

    private fun saveLists() {
        val array = JSONArray()

        lists.forEach {
            array.put(
                JSONObject()
                    .put("name", it.first)
                    .put("url", it.second)
            )
        }

        prefs.edit()
            .putString("playlists", array.toString())
            .putInt("active", activeList)
            .apply()
    }

    private fun background(color: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
        }

    private fun label(
        value: String,
        size: Float = 19f,
        bold: Boolean = false
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))

            if (bold) {
                setTypeface(null, Typeface.BOLD)
            }
        }

    private fun button(
        value: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = value
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = background(panel)
            isFocusable = true
            setOnFocusChangeListener { _, hasFocus ->
                background = background(if (hasFocus) accent else panel)
            }
            setOnClickListener { action() }
        }

    private fun artwork(url: String, width: Int, height: Int): ImageView =
        ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(width), dp(height)).apply {
                marginEnd = dp(10)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(35, 45, 63))
            contentDescription = tr("Изображение канала или фильма", "Sender- oder Filmbild", "Channel or movie artwork")
            if (url.startsWith("https://") || url.startsWith("http://")) {
                Glide.with(this@MainActivity).load(url)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .error(android.R.drawable.ic_menu_gallery)
                    .centerInside().into(this)
            } else {
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }

    private fun sectionTitle(value: String) {
        content.addView(label(value, 25f, true))
    }

    private fun tile(
        title: String,
        subtitle: String,
        artwork: Int = 0,
        tint: Int = accent,
        action: () -> Unit
    ): LinearLayout {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(48, 58, 83),
                    Color.rgb(20, 26, 42)
                )
            ).apply {
                cornerRadius = dp(18).toFloat()
            }

            setOnFocusChangeListener { _, focused ->
                (background as GradientDrawable).setStroke(dp(if (focused) 3 else 1), tint)
            }
            (background as GradientDrawable).setStroke(dp(1), tint)
            setOnClickListener { action() }
        }

        if (artwork != 0) {
            val icon = ImageView(this).apply {
                setImageResource(artwork)
                if (artwork != R.drawable.ic_cinema && artwork != R.drawable.ic_live_television) setColorFilter(tint)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            view.addView(icon, LinearLayout.LayoutParams(dp(if (artwork == R.drawable.ic_cinema || artwork == R.drawable.ic_live_television) 64 else 36), dp(36)))
        }
        view.addView(label(title, 22f, true))
        view.addView(label(subtitle, 13f))
        return view
    }

    private fun scaffold(page: String) {
        clockHandler.removeCallbacks(bufferTimeout)
        internetPairGeneration++
        if (isPhone()) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        closeSidePanel()
        current = page
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        base = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        setContentView(base)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        header.addView(
            label("Владимир Видео ТВ", 26f, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        header.addView(
            button("⚙") { showSettings() }
        )

        val screen = base
        if (prefs.getBoolean("show_clock", true)) {
            val clock = label("", 17f, true).apply { gravity = Gravity.END }
            header.addView(clock)
            val refresh = object : Runnable {
                override fun run() {
                    if (base === screen && current != "player" && !isDestroyed) {
                        val now = Date()
                        val locale = Locale.forLanguageTag(lang)
                        val time = SimpleDateFormat("HH:mm", locale).format(now)
                        clock.text = if (prefs.getBoolean("show_date", true)) {
                            val date = SimpleDateFormat("EEEE, d MMMM", locale).format(now)
                            "$time\n$date"
                        } else time
                        clockHandler.postDelayed(this, 30000)
                    }
                }
            }
            refresh.run()
        }
        base.addView(header)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }

        scroll.addView(content)

        base.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )
    }

    private fun showHome() {
        scaffold("home")
        content.addView(label("Владимир Видео ТВ", 36f, true).apply {
            typeface = Typeface.create("serif", Typeface.BOLD_ITALIC)
        })
        val phone = isPhone()
        val row = LinearLayout(this).apply {
            orientation = if (phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        fun addCard(title: String, summary: String, icon: Int, tint: Int, id: String): View {
            val card = tile(title, summary, icon, tint) { openSection(id) }
            val params = if (phone) LinearLayout.LayoutParams(-1, dp(120))
                else LinearLayout.LayoutParams(0, dp(155), 1f)
            params.setMargins(dp(6), dp(6), dp(6), dp(6))
            row.addView(card, params)
            return card
        }
        val live = addCard(tr("\u041f\u0440\u044f\u043c\u043e\u0439 \u044d\u0444\u0438\u0440", "Live-TV", "Live TV"),
            tr("\u041a\u0430\u0442\u0435\u0433\u043e\u0440\u0438\u0438 \u0438 \u043a\u0430\u043d\u0430\u043b\u044b", "Kategorien und Sender", "Categories and channels"),
            R.drawable.ic_live_television, Color.rgb(81, 207, 255), "tv")
        addCard(tr("\u041c\u0435\u0434\u0438\u0430\u0442\u0435\u043a\u0430", "Mediathek", "Media library"),
            tr("\u0424\u0438\u043b\u044c\u043c\u044b", "Filme", "Movies"),
            R.drawable.ic_cinema, Color.rgb(225, 150, 255), "movies")
        addCard(tr("\u0422\u0435\u043b\u0435\u0433\u0438\u0434", "TV-Programm", "TV guide"),
            tr("\u041f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u0430 \u043f\u0435\u0440\u0435\u0434\u0430\u0447", "Sendungen", "TV schedule"),
            android.R.drawable.ic_menu_agenda, Color.rgb(255, 196, 106), "guide")
        content.addView(row)
        val setup = LinearLayout(this).apply { orientation = if (phone) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        setup.addView(button(tr("Мои источники", "Meine Quellen", "My sources")) { showSources() })
        setup.addView(button(tr("Подключить с телефона", "Per Handy einrichten", "Set up from phone")) { showInternetPairing() })
        content.addView(setup)
        live.requestFocus()
    }

    private fun openSection(id: String) {
        when (id) {
            "tv" -> showTvMenu()
            "sources" -> showSources()
            "playlists" -> showPlaylists()
            "remote" -> showRemote()
            "settings" -> showSettings()
            "guide" -> showCatalog("guide")
            else -> showCatalog(id)
        }
    }


    private fun channelHistory(): List<String> {
        val saved = prefs.getString("tv_history", null)
        if (saved == null) return listOfNotNull(prefs.getString("recent", null))
        val entries = runCatching { JSONArray(saved) }.getOrElse { JSONArray() }
        return (0 until entries.length()).map { entries.optString(it) }.filter { it.isNotBlank() }
    }

    private fun showChannelHistory() {
        scaffold("tv_history")
        sectionTitle(tr("Недавние каналы", "Zuletzt gesehene Sender", "Recent channels"))
        val byUrl = items.filter { it.type == "tv" }.associateBy { it.url }
        val recent = channelHistory().mapNotNull(byUrl::get)
        if (recent.isEmpty()) content.addView(label(tr("История пока пуста", "Noch kein Verlauf", "No viewing history yet")))
        recent.forEach { entry ->
            content.addView(button(entry.title) {
                portalPlaybackBack = { showChannelHistory() }
                play(entry)
            })
        }
    }

    private fun videoShelfModes(): List<String> = listOf(
        tr("Продолжить просмотр", "Weiterschauen", "Continue watching"),
        tr("Просмотренные", "Angesehen", "Watched"),
        tr("Смотреть позже", "Merkliste", "Watch later"),
        tr("Избранное", "Favoriten", "Favorites")
    )

    private fun videoKey(entry: Entry): String =
        if (entry.portalRequest.isBlank()) entry.url else "vportal:" + entry.portalRequest

    private fun videoRecords(): JSONObject = runCatching {
        JSONObject(prefs.getString("video_history", "{}").orEmpty())
    }.getOrElse { JSONObject() }

    private fun videoRecord(entry: Entry): JSONObject = videoRecords().optJSONObject(videoKey(entry)) ?: JSONObject()

    private fun putVideoRecord(records: JSONObject, entry: Entry, record: JSONObject) {
        record.put("title", entry.title).put("url", entry.url).put("logo", entry.logo)
            .put("description", entry.description).put("portal", entry.portalRequest)
            .put("collection", entry.portalCollection).put("metadata", entry.videoMetadata)
            .put("updated", System.currentTimeMillis())
        records.put(videoKey(entry), record)
        prefs.edit().putString("video_history", records.toString()).apply()
    }

    private fun rememberVideoProgress(completed: Boolean = false) {
        val entry = playingVideo ?: return
        val activePlayer = player ?: return
        if (!completed && activePlayer.playbackState !in setOf(Player.STATE_READY, Player.STATE_BUFFERING)) return
        val position = activePlayer.currentPosition
        if (!completed && position <= 0L) return
        val records = videoRecords()
        val record = records.optJSONObject(videoKey(entry)) ?: JSONObject()
        record.put("position", if (completed) 0L else position).put("completed", completed)
        if (completed) record.put("later", false)
        putVideoRecord(records, entry, record)
    }

    private fun watchLaterButton(entry: Entry, onChange: (() -> Unit)? = null): Button {
        fun caption(saved: Boolean) = if (saved)
            tr("✓ В списке", "✓ Gemerkt", "✓ On watchlist")
        else tr("+ Смотреть позже", "+ Merken", "+ Watch later")
        val view = button(caption(videoRecord(entry).optBoolean("later"))) {}
        view.setOnClickListener {
            val records = videoRecords()
            val record = records.optJSONObject(videoKey(entry)) ?: JSONObject()
            val saved = !record.optBoolean("later")
            record.put("later", saved)
            putVideoRecord(records, entry, record)
            view.text = caption(saved)
            onChange?.invoke()
        }
        view.textSize = 12f
        return view
    }

    private fun showVideoShelf(mode: Int) {
        scaffold("video_history")
        sectionTitle(videoShelfModes()[mode])
        val records = videoRecords()
        val visible = records.keys().asSequence().mapNotNull { records.optJSONObject(it) }
            .filter { record ->
                when (mode) {
                    0 -> !record.optBoolean("completed") && record.optLong("position") > 0
                    1 -> record.optBoolean("completed")
                    2 -> record.optBoolean("later")
                    else -> record.optBoolean("favorite") || isLegacyVideoFavorite(record.optString("url"))
                }
            }.sortedByDescending { it.optLong("updated") }.toList()
        if (visible.isEmpty()) content.addView(label(tr("Список пока пуст", "Die Liste ist noch leer", "This list is empty")))
        val posters = PosterGrid(content)
        visible.forEach { record ->
            val entry = Entry(record.optString("title"), record.optString("url"), "",
                logo = record.optString("logo"), type = "movies", description = record.optString("description"),
                portalRequest = record.optString("portal"), portalCollection = record.optBoolean("collection"),
                videoMetadata = record.optString("metadata"))
            posters.add(entry) {
                showPosterDetails(entry) { openSavedVideo(entry) { showVideoShelf(mode) } }
            }
        }
    }

    private inner class PosterGrid(private val target: LinearLayout) {
        private val columns = if (isPhone()) 2 else 4
        private var row: LinearLayout? = null
        private var count = 0
        private val gaps = mutableListOf<View>()

        fun breakRow() {
            row = null
            count = 0
            gaps.clear()
        }

        private fun posterCellParams() = LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }

        fun add(entry: Entry, action: () -> Unit) {
            val line = row ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                target.addView(this, LinearLayout.LayoutParams(-1, -2))
                repeat(columns) {
                    val gap = View(this@MainActivity)
                    gaps.add(gap)
                    addView(gap, posterCellParams())
                }
                row = this
            }
            val width = (resources.displayMetrics.widthPixels - dp(32)) / columns - dp(8)
            val height = width * 3 / 2
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                isClickable = true
                contentDescription = entry.title
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = background(panel)
                setOnFocusChangeListener { view, focused ->
                    view.background = background(if (focused) accent else panel)
                }
                setOnClickListener { action() }
            }
            card.addView(artwork(entry.logo, 100, 150).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, height))
            card.addView(label(entry.title, 14f, true).apply {
                maxLines = 2
                minLines = 2
                gravity = Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2))
            if (entry.videoMetadata.isNotBlank()) card.addView(label(entry.videoMetadata, 12f).apply {
                maxLines = 1
                gravity = Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            line.removeView(gaps[count])
            line.addView(card, count, posterCellParams())
            if (++count == columns) breakRow()
        }
    }

    private fun isLegacyVideoFavorite(url: String): Boolean =
        url.isNotBlank() && prefs.getStringSet("favorites", emptySet()).orEmpty().contains(url)

    private fun showPosterDetails(entry: Entry, playable: Boolean = true, playVideo: () -> Unit) {
        val host = findViewById<FrameLayout>(android.R.id.content)
        openPanel(false, entry.title, host) { body ->
            val watchLabel = if (entry.portalCollection)
                tr("Открыть список", "Auswahl öffnen", "Open selection")
            else tr("▶ Смотреть", "▶ Ansehen", "▶ Watch")
            val watch = button(watchLabel) {
                closeSidePanel()
                playVideo()
            }
            body.addView(watch)
            watch.isEnabled = playable
            fun favoriteLabel(saved: Boolean) = if (saved)
                tr("★ Убрать из избранного", "★ Aus Favoriten entfernen", "★ Remove favorite")
            else tr("☆ В избранное", "☆ Zu Favoriten hinzufügen", "☆ Add favorite")
            val favorite = button(favoriteLabel(videoRecord(entry).optBoolean("favorite") ||
                isLegacyVideoFavorite(entry.url))) {}
            if (playable) {
                favorite.setOnClickListener {
                    val records = videoRecords()
                    val record = records.optJSONObject(videoKey(entry)) ?: JSONObject()
                    val legacy = isLegacyVideoFavorite(entry.url)
                    val saved = !(record.optBoolean("favorite") || legacy)
                    if (legacy) toggleFav(entry)
                    record.put("favorite", saved)
                    putVideoRecord(records, entry, record)
                    favorite.text = favoriteLabel(saved)
                }
                body.addView(favorite)
                body.addView(watchLaterButton(entry))
            }
            if (entry.videoMetadata.isNotBlank()) body.addView(label(entry.videoMetadata, 14f))
            body.addView(label(entry.description.ifBlank {
                tr("Описание недоступно", "Keine Beschreibung verfügbar", "Description unavailable")
            }, 17f))
        }
    }

    private fun openSavedVideo(entry: Entry, back: () -> Unit) {
        if (entry.portalRequest.isBlank()) {
            portalPlaybackBack = back
            play(entry)
            return
        }
        val portal = parseVideoPortal(prefs.getString("video_portal_link", "").orEmpty())
        val operation = runCatching { JSONObject(entry.portalRequest) }.getOrNull()
        if (portal == null || operation == null) {
            toast(tr("Проверь настройки VPortal", "VPortal-Einstellungen prüfen", "Check VPortal settings"))
            return
        }
        if (entry.portalCollection) {
            showPortalPage(portal, operation, entry.title, back = back)
            return
        }
        val ticket = ++portalGeneration
        val page = base
        thread {
            val url = resolvePortalStream(portal, operation)
            runOnUiThread {
                if (isDestroyed || base !== page || ticket != portalGeneration) return@runOnUiThread
                if (url == null) toast(tr("Видео недоступно", "Video nicht verfügbar", "Video is unavailable"))
                else {
                    portalPlaybackBack = back
                    play(entry.copy(url = url))
                }
            }
        }
    }

    private fun resolvePortalStream(portal: VideoPortal, operation: JSONObject): String? {
        fun valid(value: String): String? = value.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val media = runCatching { requestVideoPortal(portal, operation) }.getOrNull() ?: return null
        val variants = media.optJSONObject("variants")
        return valid(media.optString("url"))
            ?: variants?.let { options ->
                options.keys().asSequence().mapNotNull { valid(options.optString(it)) }.firstOrNull()
            }
    }

    private fun showTvMenu() {
        scaffold("tv_menu")
        sectionTitle(tr("Телевидение", "Fernsehen", "Live TV"))
        val menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val width = if (isPhone()) -1 else dp(420)
        content.addView(menu, LinearLayout.LayoutParams(width, -2))
        val favorites = button(tr("Избранное", "Favoriten", "Favorites")) {
            showCatalog("favorites")
        }
        menu.addView(favorites)
        menu.addView(button(tr("Недавние каналы", "Zuletzt gesehen", "Recent channels")) { showChannelHistory() })
        val groups = items.filter { it.type == "tv" }.map { it.group }.distinct().sorted()
        menu.addView(label(tr("Категории", "Kategorien", "Categories"), 20f, true))
        if (groups.isEmpty()) {
            menu.addView(label(tr("Добавь ТВ-плейлист, чтобы увидеть категории.", "TV-Playlist für Kategorien hinzufügen.", "Add a TV playlist to see categories.")))
            menu.addView(button(tr("Добавить с телефона · QR", "Per Handy hinzufügen · QR", "Add from phone · QR")) { showRemote() })
            menu.addView(button("+ M3U") { showPlaylists() })
        }
        groups.forEach { group ->
            val name = group.ifBlank { tr("Другие", "Sonstige", "Other") }
            menu.addView(button(name) { showCatalog("tv", group) })
        }
        menu.addView(button(tr("Все каналы", "Alle Sender", "All channels")) { showCatalog("tv") })
        favorites.requestFocus()
    }

    private fun showCatalog(type: String, selectedGroup: String? = null) {
        scaffold(type)
        catalogGroup = selectedGroup

        sectionTitle(
            when (type) {
                "tv" -> tr("Телеканалы", "Fernsehen", "Live TV")
                "guide" -> tr("Телегид", "TV-Programm", "TV guide")
                "movies" -> tr("Фильмы", "Filme", "Movies")
                else -> tr("Избранное", "Favoriten", "Favorites")
            }
        )

        if (type == "movies") {
            content.addView(button(tr("☰ Разделы видеотеки", "☰ Bereiche", "☰ Library sections")) {
                val host = findViewById<FrameLayout>(android.R.id.content)
                openPanel(true, tr("Видеотека", "Mediathek", "Media library"), host) { menu ->
                    menu.addView(button("VPortal") { openVideoPortal() })
                    videoShelfModes().forEachIndexed { index, name ->
                        menu.addView(button(name) { showVideoShelf(index) })
                    }
                }
            })
        }

        val catalog = if (type == "movies") {
            (vodPlaylistItems.values.flatten() + vodItems).distinctBy { it.url } + Entry(
                title = "Big Buck Bunny",
                url = "https://download.blender.org/peach/bigbuckbunny_movies/big_buck_bunny_720p_h264.mov",
                group = tr("Открытые фильмы", "Offene Filme", "Open films"),
                logo = "https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg",
                type = "movies",
                description = tr("Анимационная комедия о добродушном кролике.",
                    "Animationskomödie über einen gutmütigen Hasen.",
                    "An animated comedy about a gentle rabbit.") +
                    " © 2008 Blender Foundation / www.bigbuckbunny.org · CC BY 3.0"
            )
        } else items
        if (catalog.isEmpty()) {
            content.addView(
                label(
                    tr(
                        "Сначала добавь M3U-плейлист",
                        "Bitte M3U-Playlist hinzufügen",
                        "Please add an M3U playlist"
                    )
                )
            )

            content.addView(
                button("+ M3U") { showPlaylists() }
            )
            return
        }

        val favorites =
            prefs.getStringSet("favorites", emptySet())
                ?: emptySet()

        val candidates = catalog.filter {
            when (type) {
                "favorites" -> favorites.contains(it.url)
                "tv", "guide" -> it.type == "tv" && (selectedGroup == null || it.group == selectedGroup)
                "movies" -> true
                else -> it.type == type
            }
        }

        if (candidates.isEmpty()) {
            content.addView(
                label(
                    tr(
                        "Нет данных",
                        "Keine Einträge",
                        "No entries"
                    )
                )
            )
            return
        }

        val orderedCandidates = if (type == "tv" && prefs.getBoolean("sort_channels_alpha", false))
            candidates.sortedBy { it.title.lowercase(Locale.ROOT) }
        else candidates

        val groups = orderedCandidates.map { it.group }
            .distinct()
            .sorted()

        val mediaFilters = if (type == "movies") listOf(
            tr("Фильмы", "Filme", "Movies"),
            tr("Сериалы", "Serien", "Series")
        ) else emptyList()
        val options = listOf(
            tr(
                "Все категории",
                "Alle Kategorien",
                "All categories"
            )
        ) + mediaFilters + groups

        val filter = Spinner(this)

        filter.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options
        )

        if (type != "tv" && type != "favorites") content.addView(filter)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(list)

        fun fill(group: String, mediaFilter: Int = 0, page: Int = 0) {
            val now = System.currentTimeMillis()
            list.removeAllViews()

            val matching = orderedCandidates.filter { entry ->
                (group.isEmpty() || entry.group == group) &&
                    (mediaFilter == 0 || isSeries(entry) == (mediaFilter == 2))
            }
            val pageSize = 100
            val visible = matching.drop(page * pageSize).take(pageSize)
            if (page > 0) list.addView(button(tr("Предыдущая страница", "Vorherige Seite", "Previous page")) {
                fill(group, mediaFilter, page - 1)
                list.getChildAt(0)?.requestFocus()
            })
            fun nextPage() {
                if ((page + 1) * pageSize < matching.size) {
                    list.addView(button(tr("Следующая страница", "Nächste Seite", "Next page")) {
                        fill(group, mediaFilter, page + 1)
                        list.getChildAt(0)?.requestFocus()
                    })
                }
            }
            if (visible.isEmpty()) list.addView(label(tr("Нет записей", "Keine Einträge", "No entries")))
            if (type == "movies") {
                val posters = PosterGrid(list)
                visible.forEach { entry ->
                    posters.add(entry) { showPosterDetails(entry) { play(entry) } }
                }
                nextPage()
                return
            }
            val channelList = type == "tv" || type == "favorites"
            val mode = if (channelList) prefs.getInt("channel_layout", 3) else 0
            val showProgramme = type == "guide" || prefs.getBoolean("channel_show_programme", true)
            if (channelList && mode in 3..4) {
                val logosOnly = mode == 4
                val columns = if (isPhone()) 2 else if (logosOnly) 4 else 5
                visible.chunked(columns).forEach { entries ->
                    val gridRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    entries.forEach { entry ->
                        val logo = channelLogo(entry)
                        val validLogo = logo.isNotBlank()
                        val card = channelCard(entry.title, vertical = true) { play(entry) }
                        card.setOnLongClickListener {
                            toggleFav(entry)
                            toast(if (prefs.getStringSet("favorites", emptySet()).orEmpty().contains(entry.url))
                                tr("Добавлено в избранное", "Zu Favoriten hinzugefügt", "Added to favorites")
                            else tr("Убрано из избранного", "Aus Favoriten entfernt", "Removed from favorites"))
                            if (type == "favorites") showCatalog(type, selectedGroup)
                            true
                        }
                        if (validLogo) card.addView(
                            channelArtwork(logo, 136, 106),
                            LinearLayout.LayoutParams(-1, dp(106)).apply { marginEnd = 0 }
                        )
                        if (!logosOnly || !validLogo) {
                            card.addView(channelText(entry.title, 15f, true, centered = true))
                        }
                        if (!logosOnly && showProgramme) {
                            card.addView(channelText(programmeLine(entry, now), 11f, false, centered = true))
                        }
                        gridRow.addView(card, LinearLayout.LayoutParams(0, dp(if (logosOnly) 124 else 154), 1f).apply {
                            setMargins(dp(4), dp(4), dp(4), dp(4))
                        })
                    }
                    repeat(columns - entries.size) {
                        gridRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                            setMargins(dp(4), 0, dp(4), 0)
                        })
                    }
                    list.addView(gridRow)
                }
                nextPage()
                return
            }

            visible.forEach { entry ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val card = channelCard(entry.title, vertical = false) {
                    if (type == "guide") showGuideDialog(entry) else play(entry)
                }
                if (prefs.getBoolean("show_artwork", true)) {
                    val width = when (mode) { 1 -> 172; 2 -> 72; else -> 144 }
                    val height = when (mode) { 1 -> 106; 2 -> 48; else -> 88 }
                    card.addView(channelArtwork(channelLogo(entry), width, height))
                }
                val details = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(4), dp(4), dp(4))
                }
                details.addView(channelText(entry.title, 18f, true))
                if (showProgramme) details.addView(channelText(programmeLine(entry, now), 12f, false))
                card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    setMargins(0, dp(3), dp(8), dp(3))
                })
                val star = button(if (favorites.contains(entry.url)) "★" else "☆") {}
                star.setOnClickListener {
                    toggleFav(entry)
                    if (type == "favorites") showCatalog(type, selectedGroup)
                    else star.text = if (prefs.getStringSet("favorites", emptySet()).orEmpty().contains(entry.url)) "★" else "☆"
                }
                row.addView(star)
                list.addView(row)
            }
            nextPage()
        }

        fill("")
        filter.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    if (position in 1..mediaFilters.size) fill("", position)
                    else fill(groups.getOrNull(position - mediaFilters.size - 1).orEmpty())
                }
            }
    }

    private fun isSeries(entry: Entry): Boolean {
        val category = entry.group.lowercase(Locale.ROOT)
        val path = Uri.parse(entry.url).path.orEmpty().lowercase(Locale.ROOT)
        return "/series/" in path ||
            listOf("сериал", "series", "serien", "tv shows").any { category.contains(it) } ||
            Regex("""(?i)\bS\d{1,3}E\d{1,4}\b""").containsMatchIn(entry.title)
    }

    private fun channelCard(title: String, vertical: Boolean, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) Gravity.CENTER else Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            contentDescription = title
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = background(panel)
            setOnFocusChangeListener { view, focused ->
                view.background = background(if (focused) Color.rgb(18, 91, 180) else panel)
            }
            setOnClickListener { action() }
        }

    private fun channelArtwork(url: String, width: Int, height: Int): ImageView =
        artwork(url, width, height).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun channelText(value: String, size: Float, bold: Boolean, centered: Boolean = false): TextView =
        label(value, size, bold).apply {
            if (centered) gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

    private fun programmeLine(entry: Entry, now: Long): String =
        guideEntries(entry).firstOrNull { now in it.start until it.stop }?.title
            ?: tr("Нет данных о передаче", "Keine Sendungsdaten", "No programme data")

    private fun toggleFav(entry: Entry) {
        val set = (
            prefs.getStringSet("favorites", emptySet())
                ?: emptySet()
        ).toMutableSet()

        if (!set.add(entry.url)) {
            set.remove(entry.url)
        }

        prefs.edit()
            .putStringSet("favorites", set)
            .apply()
    }

    private fun showPlaylists() {
        scaffold("playlists")

        sectionTitle(
            tr(
                "Управление плейлистами",
                "Playlisten verwalten",
                "Manage playlists"
            )
        )

        content.addView(
            button(
                tr(
                    "+ Добавить M3U",
                    "+ M3U hinzufügen",
                    "+ Add M3U"
                )
            ) {
                editList(-1)
            }
        )

        lists.toList().forEachIndexed { index, pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            row.addView(
                button(
                    (if (index == activeList) "✓ " else "") +
                        pair.first
                ) {
                    activeList = index
                    saveLists()
                    loadPlaylist(index)
                    showHome()
                },
                LinearLayout.LayoutParams(0, dp(60), 1f)
            )

            row.addView(
                button("✎") {
                    editList(index)
                }
            )

            row.addView(
                button("×") {
                    AlertDialog.Builder(this)
                        .setMessage(
                            tr(
                                "Удалить плейлист?",
                                "Playlist löschen?",
                                "Delete playlist?"
                            )
                        )
                        .setPositiveButton("OK") { _, _ ->
                            lists.removeAt(index)
                            activeList =
                                if (lists.isEmpty()) -1 else 0

                            saveLists()

                            if (activeList >= 0) {
                                loadPlaylist(activeList)
                            } else {
                                items.clear()
                            }

                            showPlaylists()
                        }
                        .setNegativeButton(
                            tr("Отмена", "Abbrechen", "Cancel"),
                            null
                        )
                        .show()
                }
            )

            content.addView(row)
        }
    }

    private fun importedPlaylistName(url: String): String? =
        prefs.getString("private_playlist:$url", null)

    private fun storeImportedPlaylistName(url: String, title: String) {
        prefs.edit().putString("private_playlist:$url", title).apply()
    }

    private fun editList(index: Int) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        val name = EditText(this).apply {
            hint = tr("Название", "Name", "Name")
            setSingleLine(true)
            setText(lists.getOrNull(index)?.first ?: "")
        }

        val storedAddress = lists.getOrNull(index)?.second
        val privateAddress = storedAddress?.takeIf { importedPlaylistName(it) != null }
        val url = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            if (privateAddress == null) setText(storedAddress.orEmpty())
        }

        form.addView(name)
        if (privateAddress == null) form.addView(url)

        AlertDialog.Builder(this)
            .setTitle("M3U")
            .setView(form)
            .setPositiveButton(
                tr("Сохранить", "Speichern", "Save")
            ) { _, _ ->

                val value = privateAddress ?: url.text.toString().trim()

                if (!value.startsWith("https://") &&
                    !value.startsWith("http://")
                ) {
                    toast("HTTP(S) URL required")
                    return@setPositiveButton
                }

                val title = name.text.toString()
                    .trim()
                    .ifEmpty { "Playlist ${lists.size + 1}" }

                if (index >= 0) {
                    lists[index] = title to value
                } else {
                    lists.add(title to value)
                }

                activeList =
                    if (index >= 0) index else lists.lastIndex

                saveLists()
                loadPlaylist(activeList)
                showPlaylists()
            }
            .setNegativeButton(
                tr("Отмена", "Abbrechen", "Cancel"),
                null
            )
            .show()
    }

    private fun loadPlaylist(index: Int) {
        val url = lists.getOrNull(index)?.second ?: return

        thread {
            try {
                val connection =
                    URL(url).openConnection() as HttpURLConnection

                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true

                val body = connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

                connection.disconnect()

                val parsed = parseM3u(body, url)
                val guides = m3uGuideUrls(body, url)

                runOnUiThread {
                    if (index == activeList && lists.getOrNull(index)?.second == url) {
                        playlistGuideUrls = guides
                        val merged = items.filter { it.url.contains("/live/") }.associateByTo(linkedMapOf()) { it.url }
                        parsed.forEach { merged[it.url] = it }
                        items.clear()
                        items.addAll(merged.values)
                        loadEpg()
                        toast("${parsed.size} items")
                        if (current == "home") showHome()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast(tr("Не удалось загрузить плейлист", "Playlist konnte nicht geladen werden", "Could not load playlist"))
                }
            }
        }
    }

    private fun m3uGuideUrls(body: String, playlistUrl: String): List<String> {
        val header = body.lineSequence().map { it.trim().trimStart('\uFEFF') }
            .firstOrNull { it.startsWith("#EXTM3U", true) }.orEmpty()
        return Regex("""(?:url-tvg|x-tvg-url|tvg-url)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""", RegexOption.IGNORE_CASE)
            .findAll(header).flatMap { match -> match.groupValues.drop(1).filter { it.isNotBlank() }.asSequence() }
            .flatMap { it.split(',').asSequence() }.mapNotNull { value ->
                runCatching { URL(URL(playlistUrl), value.trim()).toString() }.getOrNull()
            }.filter { it.startsWith("http://") || it.startsWith("https://") }.distinct().toList()
    }

    private fun parseM3u(raw: String, playlistUrl: String): List<Entry> {
        val result = ArrayList<Entry>()

        var title = ""
        var group = ""
        var logo = ""
        var epgId = ""
        var description = ""
        var catchup = ""
        var days = 0

        val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")
        fun attributesIn(line: String) = attributes.findAll(line).associate {
            it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
        }
        val header = raw.lineSequence().firstOrNull { it.trim().trimStart('\uFEFF').startsWith("#EXTM3U", true) }.orEmpty()
        val defaults = attributesIn(header)

        for (line in raw.lineSequence().map { it.trim().trimStart('\uFEFF') }) {
            if (line.startsWith("#EXTINF", true)) {
                val attrs = defaults + attributesIn(line)
                var quote: Char? = null
                val separator = line.indices.firstOrNull { i ->
                    val char = line[i]
                    if (char == quote) quote = null
                    else if (quote == null && (char == '"' || char.code == 39)) quote = char
                    char == ',' && quote == null
                }
                title = separator?.let { line.substring(it + 1).trim() } ?: attrs["tvg-name"].orEmpty()

                group = attrs["group-title"] ?: "Other"
                logo = webUrl(playlistUrl, attrs["tvg-logo"].orEmpty()).orEmpty()
                epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: attrs["tvg-name"].orEmpty()
                val source = attrs["catchup-source"].orEmpty()
                catchup = if (attrs["catchup"].equals("append", true) && source.isNotBlank()) "@append:" + source else source
                days = (attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"])?.toIntOrNull()?.coerceIn(0, 30) ?: 0
                description = sequenceOf("plot", "description", "tvg-description")
                    .mapNotNull(attrs::get).firstOrNull { it.isNotBlank() }.orEmpty()

            } else if (line.startsWith("#EXTGRP:", true)) {
                group = line.substringAfter(':').trim()
            } else if (listOf("http://", "https://", "rtsp://").any { line.startsWith(it, true) }) {
                // A TV playlist's group-title is a category, not proof of VOD.
                val type = "tv"

                result.add(
                    Entry(
                        title.ifBlank { "Stream" },
                        line,
                        group.ifBlank { "Other" },
                        logo,
                        type,
                        epgId,
                        archiveDays = days,
                        description = description,
                        catchupSource = catchup
                    )
                )

                title = ""
                group = ""
                logo = ""
                epgId = ""
                description = ""
                catchup = ""
                days = 0
            }
        }

        return result
    }

    private fun play(entry: Entry, streamUrl: String = entry.url, archived: Programme? = null) {
        clockHandler.removeCallbacks(bufferTimeout)
        bufferRetried = false
        rememberVideoProgress()
        clockHandler.removeCallbacks(saveVideoProgress)
        playingVideo = null
        clearPlaybackError()
        playerView?.player = null
        currentEntry = entry
        requestedOrientation = if (isPhone()) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        current = "player"
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val liveTv = entry.type == "tv" && archived == null
        val view = PlayerView(this).apply {
            useController = !liveTv
            controllerAutoShow = false
            controllerShowTimeoutMs = 5000
            setShowSubtitleButton(true)
            isFocusable = true
            isFocusableInTouchMode = true
            resizeMode = when (prefs.getString("video_scale", "fit")) {
                "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            player = this@MainActivity.player
            setOnClickListener { if (liveTv) showChannelOverlay() else showController() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MENU -> { if (liveTv) showChannelOverlay() else showController(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (liveTv) { showSideGuide(); true } else false
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (liveTv) { showChannelOverlay(); true } else false
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> if (liveTv) { changeChannel(1); true } else false
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (liveTv) { changeChannel(-1); true } else false
                    else -> false
                }
            }
        }
        playerView = view
        frame.addView(view, FrameLayout.LayoutParams(-1, -1))
        playerFrame = frame
        sidePanel = null
        sidePanelBack = null
        if (liveTv && isPhone()) {
            val left = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showSideGuide() }
            }
            val right = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { showChannelOverlay() }
            }
            frame.addView(left, FrameLayout.LayoutParams(dp(42), -1, Gravity.START))
            frame.addView(right, FrameLayout.LayoutParams(dp(42), -1, Gravity.END))
        }
        if (prefs.getBoolean("show_clock", true)) {
            val clock = android.widget.TextClock(this).apply {
                format24Hour = "HH:mm"; format12Hour = "hh:mm a"
                setTextColor(Color.WHITE); textSize = 19f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(0x99000000.toInt())
            }
            frame.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        }
        if (entry.type == "tv") showChannelInfo(frame, entry, archived)
        setContentView(frame)
        view.requestFocus()
        try {
            val uri = Uri.parse(streamUrl)
            val media = MediaItem.Builder().setUri(uri)
            when (uri.takeIf { it.isHierarchical }?.getQueryParameter("output")?.lowercase(Locale.ROOT)) {
                "m3u8", "hls" -> media.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                "mpd", "dash" -> media.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
            }
            val video = entry.takeIf { it.type == "movies" }
            val resumeAt = video?.let {
                videoRecord(it).takeUnless { record -> record.optBoolean("completed") }?.optLong("position")
            } ?: 0L
            player?.setMediaItem(media.build(), resumeAt)
            playingVideo = video
            if (video != null) clockHandler.postDelayed(saveVideoProgress, 15000L)
            player?.prepare()
            player?.play()
        } catch (e: Exception) {
            showPlaybackError(e.javaClass.simpleName)
        }
    }

    private var bufferRetried = false
    private val bufferTimeout = object : Runnable {
        override fun run() {
            val active = player
            if (current == "player" && active != null && active.playWhenReady &&
                active.playbackState == Player.STATE_BUFFERING && !isFinishing && !isDestroyed) {
                if (!bufferRetried) {
                    bufferRetried = true
                    recoverPlayback()
                    if (active.playbackState == Player.STATE_BUFFERING) {
                        clockHandler.removeCallbacks(this)
                        clockHandler.postDelayed(this, 25_000L)
                    }
                } else {
                    active.pause()
                    showPlaybackError(tr("Поток не отвечает", "Stream antwortet nicht", "Stream is not responding"))
                }
            }
        }
    }

    private fun recoverPlayback() {
        val active = player ?: return
        if (active.currentMediaItem == null) return
        try {
            active.stop()
            active.prepare()
            active.play()
        } catch (error: Exception) {
            showPlaybackError(error.javaClass.simpleName)
        }
    }

    private fun clearPlaybackError() {
        playbackErrorPanel?.let { playerFrame?.removeView(it) }
        playbackErrorPanel = null
    }

    private fun showPlaybackError(code: String) {
        val frame = playerFrame ?: return
        if (current != "player" || isFinishing || isDestroyed) return
        clearPlaybackError()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0192234.toInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        panel.addView(label(tr("Поток недоступен", "Stream nicht verfügbar", "Stream unavailable"), 22f, true))
        panel.addView(label(code, 14f))
        val retry = button(tr("Повторить", "Erneut versuchen", "Retry")) {
            clearPlaybackError()
            playerView?.requestFocus()
            bufferRetried = false
            recoverPlayback()
        }
        panel.addView(retry)
        panel.addView(button(tr("Назад", "Zurück", "Back")) { onBackPressed() })
        playbackErrorPanel = panel
        frame.addView(panel, FrameLayout.LayoutParams(dp(300), -2, Gravity.CENTER))
        retry.requestFocus()
    }

    private fun changeChannel(direction: Int) {
        val channels = items.filter { it.type == "tv" }
        if (channels.isEmpty()) return
        val index = channels.indexOfFirst { it.url == currentEntry?.url }
        play(channels[(index + direction + channels.size) % channels.size])
    }

    private var focusBeforePanel: View? = null

    private fun closeSidePanel() {
        sidePanel?.let { panelView -> (panelView.parent as? FrameLayout)?.removeView(panelView) }
        sidePanel = null
        sidePanelBack = null
        focusBeforePanel?.requestFocus()
        focusBeforePanel = null
    }

    private fun openPanel(
        left: Boolean,
        heading: String,
        host: FrameLayout? = playerFrame,
        overVideo: Boolean = false,
        fill: (LinearLayout) -> Unit
    ) {
        val frame = host ?: return
        closeSidePanel()
        focusBeforePanel = currentFocus
        val panelWidth = (resources.displayMetrics.widthPixels * 0.42f).toInt()
            .coerceAtMost(dp(480)).coerceAtLeast(dp(250))
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = if (overVideo) null else GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(27, 39, 66), Color.rgb(10, 17, 31))
            ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(2), accent) }
            isFocusableInTouchMode = true
        }
        val headingRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        headingRow.addView(label(heading, 21f, true).apply {
            if (overVideo) setShadowLayer(5f, 0f, 1f, Color.BLACK)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!overVideo) headingRow.addView(button("✕") { sidePanelBack?.invoke() ?: closeSidePanel() })
        shell.addView(headingRow)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(shell, FrameLayout.LayoutParams(panelWidth, -1,
            if (left) Gravity.START else Gravity.END))
        shell.translationX = if (left) -panelWidth.toFloat() else panelWidth.toFloat()
        shell.animate().translationX(0f).setDuration(220L).start()
        sidePanel = shell
        fill(body)
        shell.requestFocus()
        val focus = body.getChildAt(0)
        if (focus != null) focus.requestFocus()
    }

    private fun videoOverlayButton(value: String, action: () -> Unit): Button =
        button(value, action).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            setBackgroundColor(Color.TRANSPARENT)
            setOnFocusChangeListener { _, focused ->
                background = if (focused) background(0xB0203852.toInt()) else null
            }
        }

    private fun showChannelOverlay() {
        val all = items.filter { it.type == "tv" }
        val selected = currentEntry
        val group = if (catalogGroup == selected?.group) catalogGroup else null
        val available = if (group == null) all else all.filter { it.group == group }
        val channels = if (prefs.getBoolean("sort_channels_alpha", false))
            available.sortedBy { it.title.lowercase(Locale.ROOT) } else available
        val now = System.currentTimeMillis()
        openPanel(false, tr("\u0422\u0435\u043b\u0435\u043a\u0430\u043d\u0430\u043b\u044b", "Sender", "Channels"), overVideo = true) { body ->
            var selectedButton: View? = null
            channels.forEach { entry ->
                val title = guideEntries(entry).firstOrNull { now in it.start until it.stop }?.title
                val row = videoOverlayButton(entry.title + if (title.isNullOrBlank()) "" else "\n$title") { play(entry) }
                body.addView(row)
                if (entry.url == selected?.url) selectedButton = row
            }
            if (channels.isEmpty()) body.addView(label(tr("\u041d\u0435\u0442 \u043a\u0430\u043d\u0430\u043b\u043e\u0432", "Keine Sender", "No channels")))
            selectedButton?.let { row -> row.post { row.requestFocus() } }
        }
    }

    private fun showChannelInfo(frame: FrameLayout, entry: Entry, archived: Programme?) {
        val now = System.currentTimeMillis()
        val schedule = guideEntries(entry)
        val airing = schedule.firstOrNull { now in it.start until it.stop }
        val next = schedule.firstOrNull { it.start >= (airing?.stop ?: now) }
        val programme = archived ?: airing
        val time = SimpleDateFormat("HH:mm", Locale.forLanguageTag(lang))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(0xED172740.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }
        panel.addView(label(entry.title, 19f, true))
        if (programme != null) {
            val range = time.format(Date(programme.start)) + "\u2013" + time.format(Date(programme.stop))
            val status = if (archived == null) tr("\u0421\u0435\u0439\u0447\u0430\u0441", "Jetzt", "Now") else tr("\u0410\u0440\u0445\u0438\u0432", "Archiv", "Archive")
            panel.addView(label("$status \u00b7 $range   ${programme.title}", 17f, true))
        } else {
            panel.addView(label(tr("\u041d\u0435\u0442 \u0442\u0435\u043b\u0435\u043f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u044b \u0434\u043b\u044f \u044d\u0442\u043e\u0433\u043e \u043a\u0430\u043d\u0430\u043b\u0430", "Keine Programmdaten f\u00fcr diesen Sender", "No guide data for this channel"), 15f))
        }
        if (archived == null && next != null) {
            panel.addView(label(tr("\u0414\u0430\u043b\u0435\u0435", "Danach", "Next") + " \u00b7 " +
                time.format(Date(next.start)) + "   " + next.title, 15f))
        }
        frame.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(20), 0, dp(20), dp(16))
        })
        panel.postDelayed({ frame.removeView(panel) }, 6500L)
    }

    private fun guideEntries(entry: Entry): List<Programme> {
        return epgSchedule[guideChannelId(entry)].orEmpty()
    }

    private fun normalizedGuideName(value: String) =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun guideChannelId(entry: Entry): String? =
        entry.epgId.takeIf { it in epgSchedule || it in epgIcons }
            ?: sequenceOf(entry.epgId, entry.title, entry.title.trim().replace(qualitySuffix, ""))
                .mapNotNull { epgAliases[normalizedGuideName(it)] }.firstOrNull()

    private fun webUrl(base: String, value: String): String? {
        if (value.isBlank()) return null
        return runCatching { URL(URL(base), value.trim()).toString() }.getOrNull()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun channelLogo(entry: Entry): String =
        entry.logo.ifBlank { if (entry.type == "tv") epgIcons[guideChannelId(entry)].orEmpty() else "" }

    private fun archiveUrl(entry: Entry, programme: Programme, playbackTime: Long): String? {
        val durationMinutes = ((programme.stop - programme.start + 59999) / 60000)
        if (programme.stop <= playbackTime && durationMinutes > 0 &&
            entry.archiveDays > 0 && programme.start >= playbackTime - entry.archiveDays * 86400000L) {
            val template = entry.catchupSource
            val start = programme.start / 1000L
            val stop = programme.stop / 1000L
            return when {
                template.isNotBlank() -> runCatching {
                    val resolved = template.removePrefix("@append:").replace("{utc}", start.toString())
                        .replace("${'$'}{start}", start.toString())
                        .replace("${'$'}{end}", stop.toString())
                        .replace("${'$'}{duration}", (stop - start).toString())
                        .replace("{utcend}", stop.toString())
                        .replace("{start}", start.toString())
                        .replace("{end}", stop.toString())
                        .replace("{duration}", (stop - start).toString())
                    val address = if (template.startsWith("@append:")) {
                        if ('?' in entry.url && resolved.startsWith("?")) entry.url + "&" + resolved.drop(1)
                        else entry.url + resolved
                    } else URL(URL(entry.url), resolved).toString()
                    address.takeIf {
                        !it.contains('{') && (it.startsWith("https://") || it.startsWith("http://"))
                    }
                }.getOrNull()
                entry.archiveBase.isNotBlank() -> {
                    val timestamp = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.ROOT)
                        .format(Date(programme.start))
                    "${entry.archiveBase}/$durationMinutes/$timestamp/${entry.archiveStreamId}.ts"
                }
                else -> null
            }

        }
        return null
    }

    private fun showSideGuide() {
        val entry = currentEntry ?: return
        openPanel(true, tr("Телепрограмма", "TV-Programm", "TV guide"), overVideo = true) { body ->
            fillChannelGuide(entry, body, overVideo = true)
        }
    }

    private fun showGuideDialog(entry: Entry) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(body) }
        val dialog = AlertDialog.Builder(this).setView(scroll)
            .setNegativeButton(tr("Закрыть", "Schließen", "Close"), null).create()
        fillChannelGuide(entry, body) { dialog.dismiss() }
        dialog.show()
    }

    private fun fillChannelGuide(entry: Entry, body: LinearLayout, overVideo: Boolean = false, beforeReplay: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        body.addView(label(entry.title, 19f, true).apply {
            if (overVideo) setShadowLayer(5f, 0f, 1f, Color.BLACK)
        })
        val schedule = guideEntries(entry)
        if (schedule.isEmpty()) {
            body.addView(label(tr("Нет EPG для этого канала. Добавь XMLTV в настройках.",
                "Kein EPG für diesen Sender. XMLTV in Einstellungen hinzufügen.",
                "No EPG for this channel. Add XMLTV in settings.")))
        }
        val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        schedule.filter { it.stop > now - 7L * 86400000 && it.start < now + 7L * 86400000 }
            .take(400).forEach { programme ->
                val past = programme.stop <= now
                val replay = archiveUrl(entry, programme, now)
                val text = format.format(Date(programme.start)) + "  " + programme.title +
                    when {
                        replay != null -> "  ◷"
                        now in programme.start until programme.stop -> tr("  · Сейчас", "  · Jetzt", "  · Now")
                        else -> ""
                    }
                val action: () -> Unit = action@{
                    if (replay != null) {
                        beforeReplay()
                        play(entry, replay, archived = programme)
                        return@action
                    }
                    AlertDialog.Builder(this)
                        .setTitle(programme.title)
                        .setMessage(format.format(Date(programme.start)) + " – " +
                            format.format(Date(programme.stop)) + "\n\n" + programme.description +
                            if (past) "\n\n" + tr(
                                "Для воспроизведения записи требуется Catch-up провайдера.",
                                "Zur Wiedergabe ist Anbieter-Catch-up erforderlich.",
                                "Playback requires provider Catch-up support.") else "")
                        .setPositiveButton("OK", null).show()
                }
                val row = if (overVideo) videoOverlayButton(text, action) else button(text, action)
                body.addView(row)
                if (overVideo && now in programme.start until programme.stop) row.post { row.requestFocus() }
            }
    }

    private fun vodPlaylistUrls(): List<String> {
        val stored = prefs.getString("vod_playlist_urls", null)
        if (stored == null) return listOfNotNull(prefs.getString("vod_url", null)?.takeIf { it.isNotBlank() })
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun saveVodPlaylistUrls(addresses: List<String>) {
        prefs.edit().putString("vod_playlist_urls", JSONArray(addresses.distinct()).toString()).apply()
        loadVodPlaylist()
    }

    private fun loadVodPlaylist() {
        val addresses = vodPlaylistUrls()
        val generation = ++vodLoadGeneration
        vodPlaylistItems.keys.retainAll(addresses.toSet())
        if (addresses.isEmpty()) {
            if (current == "movies") showCatalog("movies")
            return
        }
        thread {
            val loaded = linkedMapOf<String, List<Entry>>()
            addresses.forEach { address ->
                runCatching {
                    val conn = URL(address).openConnection() as HttpURLConnection
                    try {
                        conn.connectTimeout = 15000
                        conn.readTimeout = 30000
                        val raw = conn.inputStream.bufferedReader().use { it.readText() }
                        require(raw.trimStart().trimStart('\uFEFF').startsWith("#EXTM3U", true))
                        parseM3u(raw, address).map { it.copy(type = "movies") }
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()?.let { loaded[address] = it }
            }
            runOnUiThread {
                if (isDestroyed || generation != vodLoadGeneration) return@runOnUiThread
                vodPlaylistItems.putAll(loaded)
                if (current == "movies" && sidePanel == null) showCatalog("movies")
                val failures = addresses.size - loaded.size
                if (failures > 0) toast(tr("Источники видеотеки недоступны", "Mediathek-Quellen nicht erreichbar",
                    "Video library sources unavailable") + ": $failures")
            }
        }
    }

    private data class VideoPortal(val endpoint: URL, val key: String)
    private var portalBack: (() -> Unit)? = null
    private var portalPlaybackBack: (() -> Unit)? = null
    private var portalGeneration = 0

    private fun parseVideoPortal(value: String): VideoPortal? {
        val match = Regex("""portal::\[key:([^\]\s]+)\](https?://\S+)""", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        val endpoint = runCatching { URL(match.groupValues[2]) }.getOrNull() ?: return null
        if (endpoint.host.isBlank() || endpoint.userInfo != null || endpoint.ref != null) return null
        return VideoPortal(endpoint, match.groupValues[1])
    }

    private fun editVideoPortal() {
        val field = EditText(this).apply {
            hint = "portal::[key:…]https://…/api/v1/"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("video_portal_link", ""))
        }
        val dialog = AlertDialog.Builder(this).setTitle("VPortal").setView(field)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save"), null)
            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = field.text.toString().trim()
                if (value.isNotEmpty() && parseVideoPortal(value) == null) {
                    field.error = tr("Вставь полную ссылку из кабинета", "Vollständigen Portallink einfügen", "Paste the full portal link")
                    return@setOnClickListener
                }
                prefs.edit().putString("video_portal_link", value).apply()
                dialog.dismiss()
                if (value.isNotEmpty()) openVideoPortal()
            }
        }
        dialog.show()
    }

    private fun requestVideoPortal(portal: VideoPortal, params: JSONObject): JSONObject {
        val body = JSONObject(params.toString()).apply {
            put("app", "ott-play")
            put("key", portal.key)
        }.toString().toByteArray(Charsets.UTF_8)
        val connection = portal.endpoint.openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 25000
        connection.instanceFollowRedirects = false
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("Accept", "application/json")
        try {
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) throw java.io.IOException()
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                var count = input.read(buffer)
                while (count != -1) {
                    if (result.length + count > 8 * 1024 * 1024) throw java.io.IOException()
                    result.append(buffer, 0, count)
                    count = input.read(buffer)
                }
                JSONObject(result.toString())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openVideoPortal() {
        val configuredLink = prefs.getString("video_portal_link", "").orEmpty()
        if (configuredLink != arrivalsProvider) {
            portalSeenPages.clear()
            portalNewEntries.clear()
            arrivalsProvider = configuredLink
        }
        val portal = parseVideoPortal(configuredLink)
        if (portal == null) editVideoPortal()
        else showPortalPage(portal, JSONObject(), "VPortal") { showCatalog("movies") }
    }

    private var arrivalsProvider = ""
    private val portalSeenPages = mutableMapOf<String, Set<String>>()
    private val portalNewEntries = linkedMapOf<String, Entry>()

    private fun newArrivalsTitle() =
        tr("Новые поступления", "Neu hinzugefügt", "New arrivals")

    private fun isNewArrivalsCategory(title: String): Boolean {
        val name = title.lowercase(Locale.ROOT)
        return listOf("новые поступления", "новинки", "недавно добавлен", "neu hinzugefügt",
            "recently added", "new arrivals").any { name.contains(it) }
    }

    private fun showPortalArrivals(back: () -> Unit) {
        scaffold("vportal")
        portalBack = back
        portalGeneration++
        sectionTitle(newArrivalsTitle())
        if (portalNewEntries.isEmpty()) content.addView(label(tr(
            "После обновления открытых разделов здесь появятся новые позиции.",
            "Neue Titel erscheinen hier nach dem erneuten Laden besuchter Bereiche.",
            "New titles appear here after previously opened sections are reloaded.")))
        val posters = PosterGrid(content)
        portalNewEntries.values.toList().asReversed().forEach { entry ->
            posters.add(entry) {
                showPosterDetails(entry) { openSavedVideo(entry) { showPortalArrivals(back) } }
            }
        }
    }

    private inner class PortalCategoryGrid(private val target: LinearLayout) {
        private val columns = if (isPhone()) 2 else 5
        private var row: LinearLayout? = null
        private var count = 0

        fun breakRow() {
            row = null
            count = 0
        }

        fun add(title: String, icon: Int, tint: Int, action: () -> Unit) {
            val line = row ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                target.addView(this, LinearLayout.LayoutParams(-1, -2))
                row = this
            }
            val size = ((resources.displayMetrics.widthPixels - dp(32)) / columns - dp(12))
                .coerceIn(dp(76), dp(140))
            val card = tile(title, "", icon, tint, action).apply {
                gravity = Gravity.CENTER
                getChildAt(childCount - 1).visibility = View.GONE
                (getChildAt(1) as TextView).apply {
                    textSize = 15f
                    maxLines = 2
                    gravity = Gravity.CENTER
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            }
            line.addView(card, LinearLayout.LayoutParams(size, size).apply {
                setMargins(dp(4), dp(4), dp(4), dp(8))
            })
            if (++count == columns) breakRow()
        }
    }

    private fun showPortalPage(portal: VideoPortal, request: JSONObject, heading: String, page: Int = 0, back: () -> Unit) {
        scaffold("vportal")
        portalBack = back
        val generation = ++portalGeneration
        sectionTitle(heading)
        val status = label(tr("Загрузка каталога…", "Katalog wird geladen…", "Loading catalog…"))
        content.addView(status)
        fun streamUrl(value: String): String? = value.takeIf {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        }
        fun failure() {
            status.text = tr("Не удалось открыть VPortal. Проверь ссылку и подписку.",
                "VPortal nicht erreichbar. Link und Abo prüfen.",
                "Could not open VPortal. Check the link and subscription.")
        }
        thread {
            val response = runCatching { requestVideoPortal(portal, request) }.getOrNull()
            runOnUiThread {
                if (current != "vportal" || generation != portalGeneration || isDestroyed) return@runOnUiThread
                val rows = response?.optJSONArray("items")
                if (rows == null) {
                    failure()
                    return@runOnUiThread
                }
                status.visibility = View.GONE
                val reload = { showPortalPage(portal, request, heading, page, back) }
                val posters = PosterGrid(content)
                val categories = PortalCategoryGrid(content)
                val pageEntries = linkedMapOf<String, Entry>()
                val rootPage = request.length() == 0 && page == 0
                val providerArrivals = (0 until rows.length()).asSequence()
                    .mapNotNull { rows.optJSONObject(it) }
                    .firstOrNull { it.optString("type") == "category" &&
                        isNewArrivalsCategory(it.optString("title")) }
                    ?.optJSONObject("request")
                if (rootPage) categories.add(newArrivalsTitle(), android.R.drawable.ic_menu_recent_history,
                    Color.rgb(255, 199, 114)) {
                    if (providerArrivals != null) showPortalPage(portal, providerArrivals, newArrivalsTitle(), back = reload)
                    else showPortalArrivals(reload)
                }
                val pageSize = 40
                if (page > 0) content.addView(button(tr("Предыдущая страница", "Vorherige Seite", "Previous page")) {
                    showPortalPage(portal, request, heading, page - 1, back)
                })
                val firstRow = page * pageSize
                val endRow = (firstRow + pageSize).coerceAtMost(rows.length())
                for (index in firstRow until endRow) {
                    val row = rows.optJSONObject(index) ?: continue
                    val name = row.optString("title").ifBlank { "VPortal" }
                    val operation = row.optJSONObject("request")
                    val type = row.optString("type")
                    if (type == "category" && operation != null) {
                        if (rootPage && isNewArrivalsCategory(name)) continue
                        posters.breakRow()
                        val nameKey = name.lowercase(Locale.ROOT)
                        val series = listOf("сериал", "series", "serien", "телепередач").any { nameKey.contains(it) }
                        val icon = if (series) R.drawable.ic_live_television else R.drawable.ic_cinema
                        val tint = if (series) Color.rgb(104, 198, 255) else Color.rgb(217, 155, 251)
                        categories.add(name, icon, tint) { showPortalPage(portal, operation, name, back = reload) }
                    } else if (type == "next" && operation != null) {
                        posters.breakRow()
                        categories.breakRow()
                        content.addView(button(name) { showPortalPage(portal, operation, heading, back = reload) })
                    } else if (type == "stream" || type == "multistream") {
                        categories.breakRow()
                        val poster = sequenceOf("imglr", "img", "poster", "cover")
                            .mapNotNull { key -> row.optString(key).takeUnless { it == "null" } }
                            .mapNotNull { webUrl(portal.endpoint.toString(), it) }.firstOrNull().orEmpty()
                        val description = row.optString("description").takeUnless { it == "null" }.orEmpty()
                        val collection = type == "multistream" && operation != null
                        val direct = if (collection) null else streamUrl(row.optString("url"))
                        val metadata = listOf(row.optString("year"), row.optString("genre"))
                            .map(String::trim).filter { it.isNotEmpty() && it != "null" }.joinToString(" · ")
                        val entry = Entry(name, direct.orEmpty(), heading, logo = poster, type = "movies",
                            description = description, portalRequest = if (direct == null) operation?.toString().orEmpty() else "",
                            portalCollection = collection, videoMetadata = metadata)
                        val canPlay = direct != null || operation != null
                        if (canPlay) pageEntries[videoKey(entry)] = entry
                        posters.add(entry) {
                            showPosterDetails(entry, canPlay) { openSavedVideo(entry, reload) }
                        }
                    }
                }
                if (pageEntries.isNotEmpty()) {
                    val pageKey = portal.endpoint.toString() + request.toString() + "#" + page
                    val previous = portalSeenPages.put(pageKey, pageEntries.keys.toSet())
                    if (previous != null) {
                        pageEntries.forEach { (key, entry) ->
                            if (key !in previous) portalNewEntries[key] = entry
                        }
                        while (portalNewEntries.size > 100) portalNewEntries.remove(portalNewEntries.keys.first())
                    }
                }
                if (endRow < rows.length()) content.addView(button(tr(
                    "Следующая страница", "Nächste Seite", "Next page")) {
                    showPortalPage(portal, request, heading, page + 1, back)
                })
                if (content.childCount <= 2) {
                    status.visibility = View.VISIBLE
                    status.text = tr("Каталог пуст", "Katalog ist leer", "The catalog is empty")
                } else content.getChildAt(2)?.requestFocus()
            }
        }
    }

    private fun manageVodPlaylists() {
        val addresses = vodPlaylistUrls()
        val titles = addresses.mapIndexed { index, url ->
            importedPlaylistName(url) ?: "${index + 1}. M3U"
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Плейлисты видеотеки", "Mediathek-Playlisten", "Video library playlists"))
            .setItems(titles.toTypedArray()) { _, index -> editVodUrl(addresses[index]) }
            .setPositiveButton(tr("Добавить M3U", "M3U hinzufügen", "Add M3U")) { _, _ -> editVodUrl() }
            .setNegativeButton(tr("Закрыть", "Schließen", "Close"), null)
            .show()
    }

    private fun editVodUrl(original: String? = null) {
        val privateTitle = original?.let(::importedPlaylistName)
        if (original != null && privateTitle != null) {
            AlertDialog.Builder(this).setTitle(privateTitle)
                .setNeutralButton(tr("Удалить", "Entfernen", "Remove")) { _, _ ->
                    saveVodPlaylistUrls(vodPlaylistUrls().filterNot { it == original })
                }
                .setNegativeButton(tr("Закрыть", "Schließen", "Close"), null).show()
            return
        }
        val field = EditText(this).apply {
            hint = "https://..."
            setSingleLine(true)
            setText(original.orEmpty())
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(tr("Ссылка видеотеки M3U", "Mediathek-M3U-URL", "Video library M3U URL"))
            .setView(field)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save"), null)
            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null)
        if (original != null) {
            builder.setNeutralButton(tr("Удалить", "Entfernen", "Remove")) { _, _ ->
                saveVodPlaylistUrls(vodPlaylistUrls().filterNot { it == original })
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = field.text.toString().trim()
                val valid = runCatching { URL(url).let { it.protocol in setOf("http", "https") &&
                    it.host.isNotBlank() } }.getOrDefault(false)
                if (!valid) {
                    field.error = tr("Нужен адрес HTTP(S)", "HTTP(S)-Adresse erforderlich", "HTTP(S) address required")
                } else {
                    val currentUrls = vodPlaylistUrls()
                    saveVodPlaylistUrls(if (original == null) currentUrls + url
                        else currentUrls.map { if (it == original) url else it })
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private var lastSettingsItem: String? = null
    private fun showSettings(section: String? = null) {
        val sections = linkedMapOf(
            "tv_playlists" to tr("Плейлисты телевидения", "TV-Playlisten", "TV playlists"),
            "vod_playlists" to tr("Плейлисты видеотеки", "Mediathek-Playlisten", "Video library playlists"),
            "epg" to tr("Телепрограмма (EPG)", "TV-Programm (EPG)", "TV guide (EPG)"),
            "colors" to tr("Цветовые темы", "Farbschemata", "Color themes"),
            "player" to tr("Плеер", "Wiedergabe", "Player"),
            "tv" to tr("Телеканалы", "Fernsehsender", "TV channels"),
            "appearance" to tr("Оформление", "Darstellung", "Appearance"),
            "language" to tr("Язык", "Sprache", "Language"),
            "remote" to tr("Телефон и QR-код", "Handy und QR-Code", "Phone and QR code")
        )
        val host = findViewById<FrameLayout>(android.R.id.content)
        val heading = sections[section] ?: tr("\u041d\u0430\u0441\u0442\u0440\u043e\u0439\u043a\u0438", "Einstellungen", "Settings")
        openPanel(false, heading, host) { content ->
        when (section) {
            "tv_playlists" -> {
                content.addView(button(tr("Управление ТВ-плейлистами M3U", "TV-Playlisten verwalten (M3U)", "Manage TV playlists (M3U)")) {
                    showPlaylists()
                })
                content.addView(button("Xtream Codes") { editXtream() })
                content.addView(button(tr("Обновить телеканалы", "Sender aktualisieren", "Refresh channels")) {
                    if (activeList in lists.indices) loadPlaylist(activeList)
                    loadXtream()
                    loadEpg()
                })
            }
            "vod_playlists" -> {
                content.addView(button(tr("VPortal с телефона · QR", "VPortal per Handy · QR", "VPortal from phone · QR")) {
                    showRemote("vportal")
                })
                content.addView(button("VPortal") { editVideoPortal() })
                content.addView(button(tr("Добавить или изменить ссылку M3U", "M3U-Link hinzufügen oder ändern", "Add or edit M3U URL")) {
                    manageVodPlaylists()
                })
                content.addView(button(tr("Обновить видеотеку", "Mediathek aktualisieren", "Refresh video library")) {
                    loadVodPlaylist()
                    loadXtream()
                })
            }
            "epg" -> {
                content.addView(button(tr("Настроить адрес XMLTV", "XMLTV-Adresse einstellen", "Set XMLTV URL")) { editEpg() })
                content.addView(button(tr("Обновить телепрограмму", "TV-Programm aktualisieren", "Refresh TV guide")) { loadEpg() })
                val status = label(epgStatusText, 14f)
                epgStatusView = status
                content.addView(status)
            }
            "player" -> {
            content.addView(
                button(tr("Масштаб видео: оригинал / заполнить / растянуть", "Videogröße: Original / Zoom / Strecken", "Video scale: Fit / Zoom / Stretch")) {
                    val currentScale = prefs.getString("video_scale", "fit") ?: "fit"
                    val next = when (currentScale) { "fit" -> "zoom"; "zoom" -> "fill"; else -> "fit" }
                    prefs.edit().putString("video_scale", next).apply()
                    toast(next)
                }
            )
            }
            "tv" -> {
            content.addView(button(tr("Вид каналов", "Senderansicht", "Channel view")) {
                val labels = arrayOf(
                    tr("Обычный список", "Normale Liste", "Standard list"),
                    tr("Список с крупными логотипами", "Liste mit großen Logos", "List with large logos"),
                    tr("Список с мелкими логотипами", "Liste mit kleinen Logos", "List with small logos"),
                    tr("Сетка каналов", "Senderraster", "Channel grid"),
                    tr("Крупная сетка: только логотипы", "Großes Raster: nur Logos", "Large grid: logos only")
                )
                AlertDialog.Builder(this)
                    .setTitle(tr("Вид каналов", "Senderansicht", "Channel view"))
                    .setSingleChoiceItems(labels, prefs.getInt("channel_layout", 3)) { dialog, index ->
                        prefs.edit().putInt("channel_layout", index).apply()
                        dialog.dismiss()
                        toast(tr("Вид сохранён", "Ansicht gespeichert", "Layout saved"))
                    }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
            })
            val programmeEnabled = prefs.getBoolean("channel_show_programme", true)
            val programmeLabel = tr("Показывать текущую передачу", "Aktuelle Sendung anzeigen", "Show current programme")
            content.addView(button((if (programmeEnabled) "✓ " else "") + programmeLabel) {
                prefs.edit().putBoolean("channel_show_programme", !programmeEnabled).apply()
                showSettings("tv")
            })
            content.addView(button(tr("Сортировка каналов: плейлист / по алфавиту",
                "Sender sortieren: Playlist / Alphabet",
                "Channel sorting: playlist / alphabetical")) {
                val alphabetical = !prefs.getBoolean("sort_channels_alpha", false)
                prefs.edit().putBoolean("sort_channels_alpha", alphabetical).apply()
                toast(if (alphabetical) tr("По алфавиту", "Alphabetisch", "Alphabetical")
                    else tr("Порядок плейлиста", "Playlist-Reihenfolge", "Playlist order"))
            })
            }
            "colors" -> {
                val themes = listOf(
                    "blue" to tr("Синяя", "Blau", "Blue"),
                    "red" to tr("Красная", "Rot", "Red"),
                    "green" to tr("Зелёная", "Grün", "Green"),
                    "purple" to tr("Фиолетовая", "Lila", "Purple"),
                    "gold" to tr("Золотая", "Gold", "Gold"),
                    "sky" to tr("Голубая", "Hellblau", "Sky blue")
                )
                val selected = prefs.getString("theme", "blue")
                themes.forEach { (id, title) ->
                    content.addView(button((if (selected == id) "✓ " else "") + title) {
                        prefs.edit().putString("theme", id).apply()
                        showSettings(section)
                    })
                }
            }
            "appearance" -> {
            content.addView(
                button(tr("Логотипы и постеры: вкл. / выкл.", "Logos und Poster: ein / aus", "Logos and posters: on / off")) {
                    val enabled = prefs.getBoolean("show_artwork", true)
                    prefs.edit().putBoolean("show_artwork", !enabled).apply()
                    showSettings(section)
                }
            )
            content.addView(
                button(tr("Часы: включить / выключить", "Uhr ein / aus", "Clock on / off")) {
                    val enabled = prefs.getBoolean("show_clock", true)
                    prefs.edit().putBoolean("show_clock", !enabled).apply()
                    showSettings(section)
                }
            )
            content.addView(button(tr("\u0414\u0430\u0442\u0430 \u0438 \u0434\u0435\u043d\u044c \u043d\u0435\u0434\u0435\u043b\u0438", "Datum und Wochentag", "Date and weekday") +
                (if (prefs.getBoolean("show_date", true)) " \u2713" else "")) {
                prefs.edit().putBoolean("show_date", !prefs.getBoolean("show_date", true)).apply()
                showSettings(section)
            })

            }
            "language" -> {
            val languages = listOf(
                "Русский" to "ru",
                "Deutsch" to "de",
                "English" to "en"
            )

            languages.forEach { (name, code) ->
                content.addView(
                    button(
                        (if (lang == code) "✓ " else "") + name
                    ) {
                        lang = code
                        prefs.edit()
                            .putString("language", code)
                            .apply()
                        showSettings(section)
                    }
                )
            }

            }
            else -> sections.forEach { (id, title) ->
                content.addView(button(title) {
                    lastSettingsItem = id
                    if (id == "remote") showRemote() else showSettings(id)
                })
            }
        }
        val selected = if (section == null) sections.keys.toList().indexOf(lastSettingsItem.orEmpty()).coerceAtLeast(0) else 0
            content.getChildAt(selected)?.let { item -> item.post { item.requestFocus() } }
        }
        if (section != null) sidePanelBack = { showSettings() }
    }

    private fun showSources() {
        val sources = listOf(
            tr("ТВ-плейлисты M3U", "TV-Playlisten (M3U)", "TV playlists (M3U)") to { showPlaylists() },
            "Xtream Codes" to { editXtream() },
            tr("Телепрограмма XMLTV", "TV-Programm (XMLTV)", "TV guide (XMLTV)") to { editEpg() },
            tr("Плейлисты видеотеки", "Mediathek-Playlisten", "Video library playlists") to { manageVodPlaylists() },
            "VPortal" to { editVideoPortal() },
            tr("Настройка через интернет", "Über Internet einrichten", "Set up over internet") to { showInternetPairing() }
        )
        AlertDialog.Builder(this)
            .setTitle(tr("Источники", "Quellen", "Sources"))
            .setItems(sources.map { it.first }.toTypedArray()) { _, index -> sources[index].second() }
            .setNeutralButton(tr("С телефона · QR", "Per Handy · QR", "From phone · QR")) { _, _ -> showRemote() }
            .setNegativeButton(tr("Закрыть", "Schließen", "Close"), null)
            .show()
    }

    private fun editXtream() {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        fun field(hint: String, key: String, secret: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            setText(prefs.getString(key, ""))
            if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            wrapper.addView(this)
        }
        val server = field(tr("Сервер https://...", "Server https://...", "Server https://..."), "xtream_server")
        val user = field(tr("Логин", "Benutzername", "Username"), "xtream_user")
        val pass = field(tr("Пароль", "Passwort", "Password"), "xtream_pass", true)
        AlertDialog.Builder(this).setTitle("Xtream Codes").setView(wrapper)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                val host = server.text.toString().trim().trimEnd('/')
                if (host.isNotBlank() && !host.startsWith("http://") && !host.startsWith("https://")) {
                    toast(tr("Нужен адрес HTTP(S)", "HTTP(S)-Adresse erforderlich", "HTTP(S) address required"))
                } else {
                    prefs.edit().putString("xtream_server", host).putString("xtream_user", user.text.toString().trim())
                        .putString("xtream_pass", pass.text.toString()).apply()
                    if (host.isNotBlank()) loadXtream()
                }
            }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
    }

    private fun loadXtream() {
        val server = prefs.getString("xtream_server", "").orEmpty().trimEnd('/')
        val user = prefs.getString("xtream_user", "").orEmpty()
        val pass = prefs.getString("xtream_pass", "").orEmpty()
        if (server.isBlank() || user.isBlank() || pass.isBlank()) return
        thread {
            try {
                fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
                val query = "username=${encode(user)}&password=${encode(pass)}"
                fun fetch(action: String): JSONArray {
                    val url = URL("$server/player_api.php?$query&action=$action")
                    val c = url.openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    try { return JSONArray(c.inputStream.bufferedReader().use { it.readText() }) }
                    finally { c.disconnect() }
                }
                val liveGroups = mutableMapOf<String, String>()
                val vodGroups = mutableMapOf<String, String>()
                fun categories(action: String, target: MutableMap<String, String>) {
                    val array = fetch(action)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        target[obj.optString("category_id")] = obj.optString("category_name", "Other")
                    }
                }
                categories("get_live_categories", liveGroups)
                categories("get_vod_categories", vodGroups)
                val live = mutableListOf<Entry>()
                val streams = fetch("get_live_streams")
                for (i in 0 until streams.length()) {
                    val o = streams.getJSONObject(i)
                    val id = o.optString("stream_id")
                    if (id.isNotBlank()) live.add(Entry(o.optString("name", "TV"),
                        "$server/live/${encode(user)}/${encode(pass)}/$id.ts",
                        liveGroups[o.optString("category_id")] ?: "Other", o.optString("stream_icon"), "tv",
                        o.optString("epg_channel_id"),
                        if (o.optInt("tv_archive") == 1) o.optInt("tv_archive_duration") else 0,
                        "$server/timeshift/${encode(user)}/${encode(pass)}", id))
                }
                val films = mutableListOf<Entry>()
                val vod = fetch("get_vod_streams")
                for (i in 0 until vod.length()) {
                    val o = vod.getJSONObject(i)
                    val id = o.optString("stream_id")
                    if (id.isNotBlank()) films.add(Entry(o.optString("name", "Movie"),
                        "$server/movie/${encode(user)}/${encode(pass)}/$id.${o.optString("container_extension", "mp4")}",
                        vodGroups[o.optString("category_id")] ?: "Other", o.optString("stream_icon"), "movies",
                        description = o.optString("plot").takeUnless { it.isBlank() || it == "null" }
                            ?: o.optString("description").takeUnless { it == "null" }.orEmpty()))
                }
                runOnUiThread {
                    // Xtream is an additional source; do not silently overwrite M3U content.
                    items.removeAll { it.url.startsWith("$server/live/") }
                    vodItems.removeAll { it.url.startsWith("$server/movie/") }
                    items.addAll(live); vodItems.addAll(films)
                    loadEpg()
                    toast("Xtream: ${live.size} TV / ${films.size} VOD")
                    if (current == "home") showHome()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Xtream: ${e.message ?: "Connection error"}") }
            }
        }
    }

    private fun editEpg() {
        val field = EditText(this).apply {
            hint = "https://.../epg.xml"; setSingleLine(true)
            setText(prefs.getString("epg_url", ""))
        }
        AlertDialog.Builder(this).setTitle("XMLTV EPG").setView(field)
            .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                val address = field.text.toString().trim()
                if (address.isEmpty() || address.startsWith("http://") || address.startsWith("https://")) {
                    prefs.edit().putString("epg_url", address).apply(); loadEpg()
                } else toast("HTTP(S) URL required")
            }.setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
    }

    private var playlistGuideUrls = emptyList<String>()

    private val epgRefreshIntervalMs = 24L * 60L * 60L * 1000L
    private var lastEpgRequestAt: Long? = null
    private val dailyEpgRefresh = Runnable { scheduleEpgRefresh() }

    private fun scheduleEpgRefresh() {
        clockHandler.removeCallbacks(dailyEpgRefresh)
        val lastRequest = lastEpgRequestAt
        val elapsed = if (lastRequest == null) epgRefreshIntervalMs
            else android.os.SystemClock.elapsedRealtime() - lastRequest
        if (elapsed >= epgRefreshIntervalMs) loadEpg()
        val sinceRequest = android.os.SystemClock.elapsedRealtime() - (lastEpgRequestAt ?: 0L)
        clockHandler.postDelayed(dailyEpgRefresh, (epgRefreshIntervalMs - sinceRequest).coerceAtLeast(1L))
    }

    private data class CachedGuide(
        val programmes: Map<String, MutableList<Programme>>,
        val aliases: Map<String, String>,
        val icons: Map<String, String>
    )

    private fun guideCacheFile() = android.util.AtomicFile(java.io.File(filesDir, "xmltv-cache-v1"))

    private fun readGuideCache(key: String, now: Long): CachedGuide? = synchronized(epgCacheLock) {
        runCatching {
            java.io.DataInputStream(guideCacheFile().openRead().buffered()).use { input ->
                if (input.readUTF() != key) return@use null
                val savedAt = input.readLong()
                if (savedAt > now || now - savedAt > 3L * 86400000L) return@use null
                fun count(max: Int) = input.readInt().also { require(it in 0..max) }
                fun strings(): Map<String, String> = buildMap {
                    repeat(count(100_000)) { put(input.readUTF(), input.readUTF()) }
                }
                val aliases = strings()
                val icons = strings()
                val schedule = linkedMapOf<String, MutableList<Programme>>()
                repeat(count(20_000)) {
                    val id = input.readUTF()
                    val entries = mutableListOf<Programme>()
                    repeat(count(2_000)) {
                        val start = input.readLong()
                        val stop = input.readLong()
                        val title = input.readUTF()
                        val description = input.readUTF()
                        if (stop > now - 7L * 86400000L) entries.add(Programme(id, start, stop, title, description))
                    }
                    if (entries.isNotEmpty()) schedule[id] = entries
                }
                CachedGuide(schedule, aliases, icons)
            }
        }.getOrNull()
    }

    private fun writeGuideCache(key: String, now: Long, guide: CachedGuide, generation: Int) {
        synchronized(epgCacheLock) {
            if (generation != epgLoadGeneration) return
            val file = guideCacheFile()
            val stream = file.startWrite()
            try {
                val output = java.io.DataOutputStream(stream.buffered())
                output.writeUTF(key)
                output.writeLong(now)
                fun strings(values: Map<String, String>) {
                    output.writeInt(values.size)
                    values.forEach { (k, v) -> output.writeUTF(k); output.writeUTF(v) }
                }
                strings(guide.aliases)
                strings(guide.icons)
                output.writeInt(guide.programmes.size)
                guide.programmes.forEach { (id, programmes) ->
                    output.writeUTF(id)
                    val entries = programmes.take(2_000)
                    output.writeInt(entries.size)
                    entries.forEach {
                        output.writeLong(it.start)
                        output.writeLong(it.stop)
                        output.writeUTF(it.title)
                        output.writeUTF(it.description)
                    }
                }
                output.flush()
                file.finishWrite(stream)
            } catch (failure: Exception) {
                file.failWrite(stream)
                throw failure
            }
        }
    }

    private fun applyGuide(guide: CachedGuide) {
        epgSchedule.clear()
        epgSchedule.putAll(guide.programmes)
        epgAliases = guide.aliases
        epgIcons = guide.icons
        if (current in setOf("tv", "favorites", "guide") && sidePanel == null) showCatalog(current, catalogGroup)
    }

    private fun showEpgStatus(value: String) {
        epgStatusText = value
        epgStatusView?.text = value
    }

    private fun loadEpg() {
        val channels = items.filter { it.type == "tv" }
        if (channels.isEmpty()) {
            showEpgStatus(tr("Сначала загрузи каналы", "Zuerst Sender laden", "Load channels first"))
            lastEpgRequestAt = android.os.SystemClock.elapsedRealtime()
            return
        }
        val wanted = channels.flatMap { listOf(it.epgId, it.title) }
            .flatMap { listOf(normalizedGuideName(it), normalizedGuideName(it.trim().replace(qualitySuffix, ""))) }
            .filter(String::isNotEmpty).toHashSet()
        lastEpgRequestAt = android.os.SystemClock.elapsedRealtime()
        val generation = ++epgLoadGeneration
        val manual = prefs.getString("epg_url", "").orEmpty().trim()
        val server = prefs.getString("xtream_server", "").orEmpty().trimEnd('/')
        val user = prefs.getString("xtream_user", "").orEmpty()
        val pass = prefs.getString("xtream_pass", "").orEmpty()
        val provider = if (server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
            "$server/xmltv.php?username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}"
        } else ""
        val addresses = (listOf(manual, provider) + playlistGuideUrls.take(4) + listOf(
            "https://epg.uspeh.my/epg.xml.gz",
            "https://free-epg.de/api/epg/de.xml.gz",
            "https://free-epg.de/api/epg/ru.xml.gz"
        )).filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
        val now = System.currentTimeMillis()
        val cacheKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest((addresses.joinToString("\n") + "\n" + wanted.sorted().joinToString("\n"))
                .toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        thread {
            val cached = readGuideCache(cacheKey, now)
            if (cached != null && cached.programmes.isNotEmpty()) runOnUiThread {
                if (generation == epgLoadGeneration && !isDestroyed) applyGuide(cached)
            }
            val schedule = mutableMapOf<String, MutableList<Programme>>()
            val names = mutableMapOf<String, MutableSet<String>>()
            val icons = mutableMapOf<String, String>()
            fun publish(finished: Boolean) {
                val aliases = mutableMapOf<String, MutableSet<String>>()
                (schedule.keys + icons.keys).forEach { id ->
                    (names[id].orEmpty() + id).forEach { name ->
                        listOf(name, name.trim().replace(qualitySuffix, "")).forEach { candidate ->
                            val key = normalizedGuideName(candidate)
                            if (key.isNotEmpty()) aliases.getOrPut(key) { mutableSetOf() }.add(id)
                        }
                    }
                }
                val uniqueAliases = aliases.mapNotNull { (key, ids) ->
                    ids.singleOrNull()?.let { key to it }
                }.toMap()
                val sorted = schedule.mapValues { (_, entries) ->
                    entries.distinctBy { it.start }.sortedBy { it.start }.toMutableList()
                }
                val snapshot = CachedGuide(sorted, uniqueAliases, icons.toMap())
                if (finished && sorted.isNotEmpty()) {
                    runCatching { writeGuideCache(cacheKey, now, snapshot, generation) }
                }
                runOnUiThread {
                    if (generation != epgLoadGeneration || isDestroyed) return@runOnUiThread
                    if (sorted.isNotEmpty() || (epgSchedule.isEmpty() && snapshot.icons.isNotEmpty())) {
                        applyGuide(snapshot)
                    }
                    if (finished) {
                        if (sorted.isEmpty()) toast(tr("Не удалось обновить телегид", "Programm konnte nicht aktualisiert werden",
                            "Could not refresh TV guide"))
                        val matched = channels.count { guideEntries(it).isNotEmpty() }
                        toast(tr("Телегид", "TV-Programm", "TV guide") + ": $matched / ${channels.size}")
                    }
                }
            }
            val results = mutableListOf<String>()
            addresses.forEachIndexed { index, address ->
                if (generation != epgLoadGeneration) return@thread
                val host = "XMLTV ${index + 1}"
                runOnUiThread {
                    if (generation == epgLoadGeneration && !isDestroyed) {
                        showEpgStatus(tr("Загрузка", "Lädt", "Loading") +
                            " ${index + 1}/${addresses.size}: $host")
                    }
                }
                val result = runCatching { readGuide(address, now, wanted) }
                val feed = result.getOrNull()
                results.add("$host: " + (feed?.let { "${it.programmes.size}" }
                    ?: result.exceptionOrNull()?.let {
                        if (it is java.io.IOException && it.message.orEmpty().matches(Regex("HTTP [0-9]{3}")))
                            it.message.orEmpty() else it.javaClass.simpleName
                    }.orEmpty()))
                if (feed != null) {
                    feed.programmes.forEach { (id, entries) -> schedule.getOrPut(id) { mutableListOf() }.addAll(entries) }
                    feed.names.forEach { (id, aliases) -> names.getOrPut(id) { mutableSetOf() }.addAll(aliases) }
                    feed.icons.forEach { (id, icon) -> icons.putIfAbsent(id, icon) }
                }
                if (feed != null || index == addresses.lastIndex) publish(index == addresses.lastIndex)
            }
            val report = results.joinToString("\n")
            runOnUiThread {
                if (generation == epgLoadGeneration && !isDestroyed) {
                    val matched = channels.count { guideEntries(it).isNotEmpty() }
                    showEpgStatus(tr("Каналы с телегидом", "Sender mit Programm", "Channels with guide") +
                        ": $matched / ${channels.size}\n$report")
                }
            }
        }
    }

    private fun readGuide(address: String, now: Long, wanted: Set<String>): GuideFeed {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 30000
        val schedule = mutableMapOf<String, MutableList<Programme>>()
        val names = mutableMapOf<String, MutableSet<String>>()
        val icons = mutableMapOf<String, String>()
        val timeFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { isLenient = false }
        fun time(value: String?): Long {
            val parts = value.orEmpty().trim().split(Regex("\\s+"), limit = 2)
            val stamp = parts[0]
            if (stamp.length !in listOf(8, 10, 12, 14) || !stamp.all(Char::isDigit)) return 0L
            val zone = parts.getOrNull(1)?.replace(":", "") ?: "+0000"
            return runCatching { timeFormat.parse(stamp.padEnd(14, '0') + " " + zone)?.time ?: 0L }.getOrDefault(0L)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw java.io.IOException("HTTP $status")
            connection.inputStream.buffered(64 * 1024).use { raw ->
                raw.mark(2)
                val gzip = raw.read() == 0x1f && raw.read() == 0x8b
                raw.reset()
                val data = if (gzip) java.util.zip.GZIPInputStream(raw, 64 * 1024) else raw
                data.use { input ->
                    val xml = android.util.Xml.newPullParser()
                    xml.setInput(input, null)
                    require(xml.nextTag() == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "tv")
                    fun requested(value: String): Boolean =
                        normalizedGuideName(value) in wanted ||
                            normalizedGuideName(value.trim().replace(qualitySuffix, "")) in wanted
                    val wantedIds = HashSet<String>()
                    var wantedProgramme = false
                    var id = ""
                    var channel = ""
                    var title = ""
                    var description = ""
                    var start = 0L
                    var stop = 0L
                    while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        when (xml.eventType) {
                            org.xmlpull.v1.XmlPullParser.START_TAG -> when (xml.name) {
                                "channel" -> id = xml.getAttributeValue(null, "id").orEmpty()
                                "display-name" -> {
                                    val name = xml.nextText().trim()
                                    if (id.isNotBlank() && name.isNotBlank()) names.getOrPut(id) { mutableSetOf() }.add(name)
                                }
                                "icon" -> if (id.isNotBlank()) {
                                    webUrl(connection.url.toString(), xml.getAttributeValue(null, "src").orEmpty())
                                        ?.let { icons.putIfAbsent(id, it) }
                                    Unit
                                } else Unit
                                "programme" -> {
                                    channel = xml.getAttributeValue(null, "channel").orEmpty()
                                    wantedProgramme = channel in wantedIds || requested(channel)
                                    start = if (wantedProgramme) time(xml.getAttributeValue(null, "start")) else 0L
                                    stop = if (wantedProgramme) time(xml.getAttributeValue(null, "stop")) else 0L
                                    title = ""
                                    description = ""
                                }
                                "title" -> if (wantedProgramme) { title = xml.nextText().trim().take(240) } else Unit
                                "desc" -> if (wantedProgramme) { description = xml.nextText().trim().take(500) } else Unit
                                else -> Unit
                            }
                            org.xmlpull.v1.XmlPullParser.END_TAG -> when (xml.name) {
                                "channel" -> {
                                    if (requested(id) || names[id].orEmpty().any(::requested)) wantedIds.add(id)
                                    else {
                                        names.remove(id)
                                        icons.remove(id)
                                    }
                                    id = ""
                                }
                                "programme" -> if (channel.isNotBlank() && title.isNotBlank() && start > 0L &&
                                    stop > start && stop > now - 7L * 86400000 && start < now + 3L * 86400000) {
                                    val programmes = schedule.getOrPut(channel) { mutableListOf() }
                                    if (programmes.size < 1200) programmes.add(Programme(channel, start, stop, title, description))
                                } else Unit
                                else -> Unit
                            }
                            else -> Unit
                        }
                        xml.next()
                    }
                }
            }
        } finally { connection.disconnect() }
        return GuideFeed(schedule, names, icons)
    }

    private fun localIp(): String? {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        manager.activeNetwork?.let { manager.getLinkProperties(it) }?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress?.let { return it }
        return try {
            val interfaces =
                NetworkInterface.getNetworkInterfaces()

            var ip: String? = null

            while (interfaces.hasMoreElements() && ip == null) {
                val network = interfaces.nextElement()

                if (!network.isLoopback && network.isUp) {
                    val addresses = network.inetAddresses

                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()

                        if (
                            address is Inet4Address &&
                            !address.isLoopbackAddress &&
                            !address.isLinkLocalAddress
                        ) {
                            ip = address.hostAddress
                            break
                        }
                    }
                }
            }

            ip
        } catch (_: Exception) {
            null
        }
    }

    private fun remoteUrl(): String? =
        remoteServer?.takeUnless { it.isClosed }?.localPort?.let { port ->
            localIp()?.let { ip -> "http://$ip:$port/?key=$remoteToken" }
        }

    private var remoteReturnPage = "home"

    private var remoteSource = ""

    private fun showRemote(source: String = if (current == "remote") remoteSource else "") {
        if (current != "remote") remoteReturnPage = current
        remoteSource = source
        startRemoteServer()
        scaffold("remote")

        sectionTitle(
            tr(
                "Управление с телефона",
                "Steuerung mit Handy",
                "Phone control"
            )
        )

        val url = remoteUrl()?.let { if (source == "vportal") "$it&source=vportal" else it }

        if (url == null || !remoteRunning) {
            content.addView(button(tr("Повторить", "Erneut versuchen", "Try again")) {
                startRemoteServer()
                showRemote()
            })
            content.addView(
                label(
                    tr(
                        "Подключи телефон и ТВ к одному Wi-Fi",
                        "TV und Handy mit demselben WLAN verbinden",
                        "Connect TV and phone to the same Wi-Fi"
                    )
                )
            )
            return
        }

        content.addView(
            label(
                tr(
                    "Сканируй QR-код телефоном",
                    "QR-Code mit Handy scannen",
                    "Scan QR with phone"
                )
            )
        )

        try {
            val matrix = MultiFormatWriter().encode(
                url,
                BarcodeFormat.QR_CODE,
                240,
                240
            )

            val bitmap = Bitmap.createBitmap(
                240,
                240,
                Bitmap.Config.ARGB_8888
            )

            for (y in 0 until 240) {
                for (x in 0 until 240) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (matrix.get(x, y))
                            Color.BLACK else Color.WHITE
                    )
                }
            }

            val image = ImageView(this)
            image.setImageBitmap(bitmap)

            content.addView(
                image,
                LinearLayout.LayoutParams(dp(240), dp(240)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            )

        } catch (e: Exception) {
            content.addView(
                label("QR error: ${e.message}")
            )
        }

        content.addView(label(url, 14f))
    }

    @Volatile private var internetPairGeneration = 0

    private fun relayCall(base: URL, method: String, key: String? = null): JSONObject {
        val connection = URL(base, "/api/pair").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            if (key != null) connection.setRequestProperty("Authorization", "Bearer $key")
            val status = connection.responseCode
            if (status == 410) return JSONObject().put("expired", true)
            check(status in 200..299)
            return connection.inputStream.bufferedReader().use { input ->
                val payload = CharArray(16_384)
                var length = 0
                while (length < payload.size) {
                    val count = input.read(payload, length, payload.size - length)
                    if (count < 0) return@use JSONObject(String(payload, 0, length))
                    length += count
                }
                throw java.io.IOException("Response too large")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun showInternetPairing() {
        scaffold("remote_internet")
        sectionTitle(tr("Настройка через интернет", "Über Internet einrichten", "Set up over internet"))
        content.addView(button(tr("Адрес сервера подключения", "Adresse des Verbindungsservers", "Pairing server address")) {
            val field = EditText(this).apply {
                hint = "https://…"
                setSingleLine(true)
                setText(prefs.getString("pairing_server", ""))
            }
            AlertDialog.Builder(this).setView(field).setTitle(tr("Сервер подключения", "Verbindungsserver", "Pairing server"))
                .setPositiveButton(tr("Сохранить", "Speichern", "Save")) { _, _ ->
                    val address = runCatching { URL(field.text.toString().trim()) }.getOrNull()
                    if (address == null || address.protocol != "https" || address.host.isBlank() ||
                        address.userInfo != null || address.query != null || address.ref != null ||
                        address.path !in listOf("", "/")) {
                        toast(tr("Нужен HTTPS-адрес сервера", "HTTPS-Serveradresse erforderlich", "HTTPS server address required"))
                    } else {
                        prefs.edit().putString("pairing_server", address.toString()).apply()
                        showInternetPairing()
                    }
                }
                .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel"), null).show()
        })
        val address = prefs.getString("pairing_server", "").orEmpty()
        if (address.isBlank()) {
            content.addView(label(tr("Укажи HTTPS-адрес своего сервера подключения.",
                "Gib die HTTPS-Adresse deines Verbindungsservers ein.", "Enter your HTTPS pairing server address.")))
            return
        }
        val ticket = internetPairGeneration
        val status = label(tr("Создаём код…", "Code wird erstellt…", "Creating code…"))
        content.addView(status)
        thread {
            try {
                val server = URL(address)
                val session = relayCall(server, "POST")
                val key = session.getString("key")
                val link = URL(session.getString("link"))
                check(link.protocol == "https" && link.host == server.host &&
                    link.port == server.port && link.userInfo == null)
                val matrix = MultiFormatWriter().encode(link.toString(), BarcodeFormat.QR_CODE, 300, 300)
                val pixels = IntArray(300 * 300) { i ->
                    if (matrix.get(i % 300, i / 300)) Color.BLACK else Color.WHITE
                }
                val bitmap = Bitmap.createBitmap(pixels, 300, 300, Bitmap.Config.ARGB_8888)
                runOnUiThread {
                    if (ticket != internetPairGeneration || isDestroyed) return@runOnUiThread
                    status.text = tr("Отправь фото QR. Код действует 10 минут.",
                        "Sende ein Foto des QR-Codes. Er gilt 10 Minuten.", "Share a photo of this QR. It lasts 10 minutes.")
                    content.addView(ImageView(this).apply {
                        setImageBitmap(bitmap)
                        contentDescription = "QR"
                    }, LinearLayout.LayoutParams(dp(230), dp(230)))
                }
                val deadline = android.os.SystemClock.elapsedRealtime() + 600_000L
                while (ticket == internetPairGeneration && android.os.SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(3_000L)
                    if (ticket != internetPairGeneration) return@thread
                    val received = relayCall(server, "GET", key)
                    if (received.optBoolean("expired")) break
                    if (received.optBoolean("pending")) continue
                    val url = URL(received.getString("url"))
                    val kind = received.getString("kind")
                    check(url.protocol in setOf("http", "https") && url.host.isNotBlank() && kind in setOf("tv", "vod"))
                    val name = received.optString("name").trim().take(80)
                        .takeUnless { it.contains("://") }.orEmpty().ifBlank {
                            tr("Новый плейлист", "Neue Playlist", "New playlist")
                        }
                    runOnUiThread {
                        if (ticket != internetPairGeneration || isDestroyed) return@runOnUiThread
                        status.text = tr("Плейлист получен", "Playlist empfangen", "Playlist received")
                        AlertDialog.Builder(this).setTitle(tr("Добавить плейлист?", "Playlist hinzufügen?", "Add playlist?"))
                            .setMessage(name)
                            .setPositiveButton(tr("Добавить", "Hinzufügen", "Add")) { _, _ ->
                                storeImportedPlaylistName(url.toString(), name)
                                if (kind == "vod") saveVodPlaylistUrls(vodPlaylistUrls() + url.toString())
                                else {
                                    val existing = lists.indexOfFirst { it.second == url.toString() }
                                    if (existing < 0) lists.add(name to url.toString())
                                    else lists[existing] = name to url.toString()
                                    activeList = if (existing < 0) lists.lastIndex else existing
                                    saveLists()
                                    loadPlaylist(activeList)
                                }
                                showHome()
                            }
                            .setNegativeButton(tr("Отмена", "Abbrechen", "Cancel")) { _, _ -> showHome() }
                            .show()
                    }
                    return@thread
                }
                runOnUiThread {
                    if (ticket == internetPairGeneration && !isDestroyed) status.text =
                        tr("Код истёк. Открой этот раздел снова.", "Code abgelaufen. Bereich erneut öffnen.", "Code expired. Reopen this section.")
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (ticket == internetPairGeneration && !isDestroyed) status.text =
                        tr("Сервер недоступен", "Server nicht erreichbar", "Pairing server unavailable")
                }
            }
        }
    }

    private fun startRemoteServer() {
        if (remoteRunning || remoteStarting) return
        remoteStarting = true

        thread(name = "VladiMirRemote") {
            try {
                val server = ServerSocket(0)
                remoteServer = server
                remoteRunning = true
                remoteStarting = false
                runOnUiThread { if (!isDestroyed && current == "remote") showRemote() }

                while (!server.isClosed) {
                    val client = server.accept()
                    thread {
                        handleRemote(client)
                    }
                }
            } catch (_: Exception) {
                remoteRunning = false
                remoteStarting = false
            }
        }
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun remoteSourceForm(source: String, title: String, fields: String = "") = """
        <h4>$title</h4>
        <form method="post" action="/add?key=$remoteToken">
          <input type="hidden" name="source" value="$source">
          <input name="url" type="${if (source == "vportal") "password" else "url"}"
                 placeholder="${if (source == "vportal") "portal::[key:…]https://…/api/v1/" else "https://…"}"
                 autocomplete="off" required>
          $fields
          <button>Сохранить / Speichern / Save</button>
        </form>
    """.trimIndent()

    private fun handleRemote(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 5000

                val input = client.getInputStream()
                    .bufferedReader()

                val first = input.readLine() ?: return
                val parts = first.split(' ')

                if (parts.size < 2) return

                val uri = Uri.parse(parts[1])
                val path = uri.path ?: "/"
                val key = uri.getQueryParameter("key")

                var body = ""
                var status = "200 OK"

                if (key != remoteToken) {
                    status = "403 Forbidden"
                    body = "Forbidden"

                } else if (path == "/play") {
                    val index = uri.getQueryParameter("id")
                        ?.toIntOrNull()

                    if (index != null) {
                        runOnUiThread {
                            items.getOrNull(index)?.let {
                                play(it)
                            }
                        }
                    }

                    body = """
                        <p>Sent to TV</p>
                        <a href="/?key=$remoteToken">Back</a>
                    """.trimIndent()

                } else if (path == "/add" && parts[0] == "POST") {
                    var contentLength = 0
                    while (true) {
                        val header = input.readLine() ?: return
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = header.substringAfter(':').trim().toIntOrNull() ?: return
                        }
                    }
                    if (contentLength !in 1..24000) return
                    val formData = CharArray(contentLength)
                    for (i in formData.indices) {
                        val value = input.read()
                        if (value < 0) return
                        formData[i] = value.toChar()
                    }
                    val form = Uri.parse("http://localhost/?" + String(formData))
                    fun field(name: String) = form.getQueryParameter(name).orEmpty()
                    val source = field("source")
                    val name = form.getQueryParameter("name")
                        ?.take(100)?.trim().orEmpty()

                    val url = form.getQueryParameter("url")
                        ?.take(4096)?.trim().orEmpty()

                    val user = field("user").trim()
                    val password = field("password")
                    val validUrl = if (source == "vportal") parseVideoPortal(url) != null
                        else url.startsWith("https://") || url.startsWith("http://")
                    if (source !in setOf("tv", "vod", "epg", "xtream", "vportal") ||
                        !validUrl ||
                        (source == "xtream" && (user.isBlank() || password.isBlank()))) {
                        status = "400 Bad Request"
                        body = "Invalid source details"
                    } else {
                        runOnUiThread {
                            when (source) {
                                "vportal" -> {
                                    prefs.edit().putString("video_portal_link", url).apply()
                                    if (current == "remote") showSettings("vod_playlists")
                                    toast(tr("VPortal сохранён", "VPortal gespeichert", "VPortal saved"))
                                }
                                "tv" -> {
                                    lists.add(name.ifBlank { "Playlist ${lists.size + 1}" } to url)
                                    activeList = lists.lastIndex
                                    saveLists()
                                    loadPlaylist(activeList)
                                }
                                "vod" -> {
                                    saveVodPlaylistUrls(vodPlaylistUrls() + url)
                                }
                                "epg" -> {
                                    prefs.edit().putString("epg_url", url).apply()
                                    loadEpg()
                                }
                                "xtream" -> {
                                    prefs.edit().putString("xtream_server", url.trimEnd('/'))
                                        .putString("xtream_user", user).putString("xtream_pass", password).apply()
                                    loadXtream()
                                    loadEpg()
                                }
                            }
                        }

                        body = """
                            <meta charset="utf-8">
                            <p>Источник передан / Quelle gesendet / Source submitted</p>
                            <a href="/?key=$remoteToken">Back</a>
                        """.trimIndent()
                    }

                } else if (path == "/" && uri.getQueryParameter("source") == "vportal") {
                    body = """
                        <meta charset="utf-8">
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        ${remoteSourceForm("vportal", "VPortal · Медиатека")}
                    """.trimIndent()
                } else if (path == "/" || path == "/search") {
                    val query =
                        uri.getQueryParameter("q") ?: ""

                    val matches = items.toList()
                        .withIndex()
                        .filter {
                            query.isBlank() ||
                            it.value.title.contains(query, true) ||
                            it.value.group.contains(query, true)
                        }
                        .take(80)

                    val links = matches.joinToString("<hr>") {
                        (index, entry) ->
                        """
                        <a href="/play?key=$remoteToken&id=$index">
                        ▶ ${escapeHtml(entry.title)}
                        </a>
                        <small>${escapeHtml(entry.group)}</small>
                        """.trimIndent()
                    }

                    body = """
                    <!doctype html>
                    <html lang="ru">
                    <head>
                      <meta charset="utf-8">
                      <meta name="viewport"
                            content="width=device-width,initial-scale=1">
                      <title>VladiMir-TV Remote</title>
                      <style>
                        body {
                          background:#0c111d;
                          color:white;
                          font:18px sans-serif;
                          padding:18px;
                        }
                        input,button {
                          font:20px sans-serif;
                          padding:12px;
                          max-width:95%;
                        }
                        a {
                          color:#9ac6ff;
                          display:inline-block;
                          padding:8px;
                        }
                      </style>
                    </head>
                    <body>
                      <h2>VladiMir-TV</h2>
                      <p>Поиск / Suche / Search</p>
                      <form action="/search">
                        <input type="hidden"
                               name="key"
                               value="$remoteToken">
                        <input name="q"
                               value="${escapeHtml(query)}"
                               placeholder="Канал / Film / Channel">
                        <button>Поиск / Search</button>
                      </form>
                      <p>${matches.size} результатов</p>
                      $links
                      <hr>
                      <h3>Источники / Quellen / Sources</h3>
                      ${remoteSourceForm("tv", "ТВ M3U / TV M3U",
                          "<input name='name' placeholder='Название / Name'>")}
                      ${remoteSourceForm("vod", "Видеотека M3U / Mediathek / Video library")}
                      ${remoteSourceForm("vportal", "Видеотека VPortal / Mediathek / Video library")}
                      ${remoteSourceForm("epg", "Телепрограмма / TV guide XMLTV")}
                      ${remoteSourceForm("xtream", "Xtream Codes",
                          "<input name='user' placeholder='Login' required>" +
                          "<input name='password' type='password' placeholder='Password' required>")}
                    </body>
                    </html>
                    """.trimIndent()

                } else {
                    status = "404 Not Found"
                    body = "Not found"
                }

                val bytes = body.toByteArray(Charsets.UTF_8)

                val headers = """
                    HTTP/1.1 $status
                    Content-Type: text/html; charset=utf-8
                    Content-Length: ${bytes.size}
                    Cache-Control: no-store
                    Connection: close

                """.trimIndent().replace("\n", "\r\n") + "\r\n"

                client.getOutputStream().write(
                    headers.toByteArray(Charsets.UTF_8)
                )

                client.getOutputStream().write(bytes)
                client.getOutputStream().flush()

            } catch (_: Exception) {
            }
        }
    }

    private fun toast(value: String) {
        Toast.makeText(
            this,
            value,
            Toast.LENGTH_LONG
        ).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sidePanel != null) {
            sidePanelBack?.invoke() ?: closeSidePanel()
            return
        }
        if (current == "home") {
            finish()
            return
        }
        if (current == "player") {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            resumePlaybackOnStart = false
            rememberVideoProgress()
            clockHandler.removeCallbacks(saveVideoProgress)
            playingVideo = null
            player?.stop()
            clearPlaybackError()
            playerView?.player = null
            playerView = null
            playerFrame = null
            val returnToPortal = portalPlaybackBack
            portalPlaybackBack = null
            if (returnToPortal != null) returnToPortal()
            else if (currentEntry?.type == "movies") showCatalog("movies") else showTvMenu()
        } else if (current == "vportal") {
            portalGeneration++
            portalBack?.invoke() ?: showCatalog("movies")
        } else if (current == "remote") {
            when (remoteReturnPage) {
                "settings_section" -> showSettings(if (remoteSource == "vportal") "vod_playlists" else null)
                "settings" -> showSettings()
                "tv_menu" -> showTvMenu()
                else -> showHome()
            }
        } else if (current == "video_history") {
            showCatalog("movies")
        } else if (current == "tv_history") {
            showTvMenu()
        } else if (current == "settings_section") {
            showSettings()
        } else if (current == "tv" || current == "favorites") {
            showTvMenu()
        } else {
            showHome()
        }
    }

    override fun onStart() {
        super.onStart()
        scheduleEpgRefresh()
        if (current == "player" && playingVideo != null) {
            clockHandler.removeCallbacks(saveVideoProgress)
            clockHandler.postDelayed(saveVideoProgress, 15000L)
        }
        if (resumePlaybackOnStart && current == "player") {
            player?.play()
            if (player?.playbackState == Player.STATE_BUFFERING) clockHandler.postDelayed(bufferTimeout, 25_000L)
        }
        resumePlaybackOnStart = false
    }

    override fun onStop() {
        clockHandler.removeCallbacks(bufferTimeout)
        clockHandler.removeCallbacks(saveVideoProgress)
        clockHandler.removeCallbacks(dailyEpgRefresh)
        resumePlaybackOnStart = current == "player" && player?.playWhenReady == true &&
            player?.playerError == null
        rememberVideoProgress()
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        internetPairGeneration++
        clockHandler.removeCallbacksAndMessages(null)
        remoteServer?.close()
        remoteRunning = false
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
