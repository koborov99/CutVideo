package ru.videocut.noads

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val pickRequest = 101
    private var videoUri: Uri? = null
    private var videoDurationMs = 0L
    private var player: ExoPlayer? = null
    private var transformer: Transformer? = null
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var preview: PlayerView
    private lateinit var status: TextView
    private lateinit var startSeek: SeekBar
    private lateinit var endSeek: SeekBar
    private lateinit var startValue: TextView
    private lateinit var endValue: TextView
    private lateinit var chooseButton: Button
    private lateinit var exportButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var qualitySpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(17, 19, 27)
        window.navigationBarColor = Color.rgb(17, 19, 27)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(17, 19, 27))
        }

        val title = TextView(this).apply {
            text = "VideoCut"
            textSize = 30f
            setTextColor(Color.WHITE)
        }
        val subtitle = TextView(this).apply {
            text = "Видеоредактор без рекламы"
            textSize = 15f
            setTextColor(Color.rgb(174, 181, 197))
            setPadding(0, dp(3), 0, dp(16))
        }

        preview = PlayerView(this).apply {
            useController = true
            setBackgroundColor(Color.BLACK)
        }
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(220)
        ))

        chooseButton = button("＋  Выбрать видео")
        chooseButton.setOnClickListener { openPicker() }

        status = TextView(this).apply {
            text = "Выбери видео, чтобы начать."
            textSize = 14f
            setTextColor(Color.rgb(211, 216, 228))
            setPadding(0, dp(12), 0, dp(8))
        }

        startValue = label("Начало: 0:00")
        startSeek = SeekBar(this).apply { max = 1000; isEnabled = false }
        endValue = label("Конец: 0:00")
        endSeek = SeekBar(this).apply { max = 1000; isEnabled = false }

        val qualityLabel = label("Качество экспорта")
        qualitySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Оригинальное качество (быстрее)", "Экспорт совместимого MP4")
            )
        }

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }

        exportButton = button("✂  Обрезать и сохранить").apply {
            isEnabled = false
            setOnClickListener { exportTrim() }
        }

        val hint = TextView(this).apply {
            text = "Файл сохраняется в Movies/VideoCut. Для точной обрезки приложение перекодирует выбранный фрагмент; время экспорта зависит от длительности и мощности телефона."
            textSize = 12f
            setTextColor(Color.rgb(145, 153, 172))
            setPadding(0, dp(12), 0, 0)
        }

        listOf(title, subtitle).forEach { root.addView(it) }
        root.addView(chooseButton, matchWrap())
        root.addView(status)
        root.addView(startValue)
        root.addView(startSeek, matchWrap())
        root.addView(endValue)
        root.addView(endSeek, matchWrap())
        root.addView(qualityLabel)
        root.addView(qualitySpinner, matchWrap())
        root.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        })
        root.addView(exportButton, matchWrap())
        root.addView(hint)
        setContentView(ScrollView(this).apply { addView(root) })

        startSeek.setOnSeekBarChangeListener(seekListener(true))
        endSeek.setOnSeekBarChangeListener(seekListener(false))
    }

    private fun seekListener(isStart: Boolean) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progressValue: Int, fromUser: Boolean) {
            if (videoDurationMs <= 0) return
            var startMs = videoDurationMs * startSeek.progress / 1000
            var endMs = videoDurationMs * endSeek.progress / 1000
            if (isStart && startMs >= endMs) {
                endMs = (startMs + 1000).coerceAtMost(videoDurationMs)
                endSeek.progress = (endMs * 1000 / videoDurationMs).toInt()
            } else if (!isStart && endMs <= startMs) {
                startMs = (endMs - 1000).coerceAtLeast(0)
                startSeek.progress = (startMs * 1000 / videoDurationMs).toInt()
            }
            startValue.text = "Начало: ${formatTime(videoDurationMs * startSeek.progress / 1000)}"
            endValue.text = "Конец: ${formatTime(videoDurationMs * endSeek.progress / 1000)}"
            if (fromUser && isStart) player?.seekTo(startMs)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(intent, pickRequest)
    }

    @Deprecated("Kept for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        videoUri = uri
        try {
            val newPlayer = ExoPlayer.Builder(this).build()
            player?.release()
            player = newPlayer
            preview.player = newPlayer
            newPlayer.setMediaItem(MediaItem.fromUri(uri))
            newPlayer.prepare()
            newPlayer.playWhenReady = false
            newPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        videoDurationMs = newPlayer.duration.coerceAtLeast(0)
                        if (videoDurationMs > 0) {
                            startSeek.isEnabled = true
                            endSeek.isEnabled = true
                            startSeek.progress = 0
                            endSeek.progress = 1000
                            startValue.text = "Начало: 0:00"
                            endValue.text = "Конец: ${formatTime(videoDurationMs)}"
                            exportButton.isEnabled = true
                            status.text = "Видео загружено · ${formatTime(videoDurationMs)}"
                        } else {
                            status.text = "Не удалось определить длительность видео."
                        }
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    status.text = "Предпросмотр недоступен для этого файла."
                }
            })
        } catch (e: Exception) {
            status.text = "Не удалось открыть видео: ${e.message ?: "ошибка"}"
        }
    }

    private fun exportTrim() {
        val uri = videoUri ?: return
        val duration = videoDurationMs
        val startMs = duration * startSeek.progress / 1000
        val endMs = duration * endSeek.progress / 1000
        if (endMs <= startMs || endMs - startMs < 500) {
            toast("Выбери фрагмент длиной хотя бы 0,5 секунды.")
            return
        }

        chooseButton.isEnabled = false
        exportButton.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = "Подготовка экспорта…"

        executor.execute {
            try {
                val filename = "VideoCut_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
                val output = File(cacheDir, filename)
                output.delete()

                val item = MediaItem.Builder()
                    .setUri(uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .setEndPositionMs(endMs)
                            .build()
                    )
                    .build()
                val edited = EditedMediaItem.Builder(item).build()

                runOnUiThread { status.text = "Обрезка видео… Не закрывай приложение." }

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult) {
                        executor.execute {
                            try {
                                saveToGallery(output, filename)
                                output.delete()
                                runOnUiThread {
                                    status.text = "Готово! Файл сохранён в Movies/VideoCut."
                                    progress.visibility = View.GONE
                                    chooseButton.isEnabled = true
                                    exportButton.isEnabled = true
                                    toast("Видео сохранено в галерею.")
                                }
                            } catch (e: Exception) {
                                fail("Не удалось сохранить: ${e.message}")
                            }
                        }
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult,
                        exportException: androidx.media3.transformer.ExportException
                    ) {
                        output.delete()
                        fail("Ошибка обработки: ${exportException.message ?: "формат не поддерживается"}")
                    }
                }
                runOnUiThread {
                    transformer = Transformer.Builder(this@MainActivity)
                        .addListener(listener)
                        .build()
                    transformer?.start(edited, output.absolutePath)
                }
            } catch (e: Exception) {
                fail("Ошибка экспорта: ${e.message ?: "неизвестная ошибка"}")
            }
        }
    }

    private fun saveToGallery(file: File, name: String) {
        if (!file.exists() || file.length() == 0L) throw IllegalStateException("Пустой файл экспорта")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoCut")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать файл в галерее")
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Не удалось записать файл")
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun fail(message: String) {
        runOnUiThread {
            status.text = message
            progress.visibility = View.GONE
            chooseButton.isEnabled = true
            exportButton.isEnabled = videoUri != null
            toast(message)
        }
    }

    private fun button(textValue: String) = Button(this).apply {
        text = textValue
        isAllCaps = false
        textSize = 15f
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.rgb(225, 229, 239))
        setPadding(0, dp(8), 0, 0)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(5) }

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        transformer?.cancel()
        player?.release()
        executor.shutdown()
        super.onDestroy()
    }
}
