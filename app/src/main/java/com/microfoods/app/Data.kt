package com.microfoods.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.microfoods.core.AppData
import com.microfoods.core.Days
import com.microfoods.core.Food
import com.microfoods.core.FoodDb
import com.microfoods.core.Json
import com.microfoods.core.JMap
import com.microfoods.core.Settings
import com.microfoods.core.bool
import com.microfoods.core.obj
import com.microfoods.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Everything kept on the phone, in one JSON file. Every change goes through [update], which writes the file
 * before the screen sees it, so a confirmed meal is on disk before any network call is tried.
 */
object Repo {
    private val lock = Any()
    private val _state = MutableStateFlow(AppData())
    val state: StateFlow<AppData> get() = _state
    lateinit var db: FoodDb
        private set
    private var file: File? = null

    fun init(ctx: Context) = synchronized(lock) {
        if (file != null) return@synchronized
        val f = File(ctx.filesDir, "microfoods.json")
        val data = runCatching { AppData.parse(if (f.exists()) f.readText() else null) }.getOrElse { AppData() }
        db = FoodDb.parse(ctx.assets.open("foods.json").bufferedReader().use { it.readText() })
        data.customFoods.forEach(db::add)
        file = f
        _state.value = data
    }

    fun update(change: (AppData) -> AppData): AppData = synchronized(lock) {
        val next = change(_state.value)
        val f = file ?: error("Repo not ready")
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(next.toJson())
        if (!tmp.renameTo(f)) { f.writeText(next.toJson()); tmp.delete() }
        _state.value = next
        next
    }

    fun addFood(food: Food) = synchronized(lock) {
        db.add(food)
        update { d -> d.copy(customFoods = d.customFoods.filter { it.id != food.id } + food) }
    }
}

/** Talks to your Apps Script. Endpoint "demo" answers with sample data so the app can be tried without a Sheet. */
class Backend(private val s: Settings) {
    fun call(body: Map<String, Any?>): String {
        if (s.demo) return Demo.answer(body)
        if (!s.connected) error("Add your Sheet link and token in Settings first.")
        val payload = Json.write(body + ("token" to s.token)).toByteArray()
        var conn = (URL(s.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
            connectTimeout = 20_000; readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload) }
        var code = conn.responseCode
        var hops = 0
        // Apps Script answers a POST with a redirect to the result, which must be fetched with GET
        while (code in 301..308 && hops < 5) {
            val next = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = (URL(next).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 120_000
            }
            code = conn.responseCode
            hops++
        }
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) error("The Sheet answered $code.")
        if (!text.trimStart().startsWith("{")) error("The Sheet link did not answer with data. Check the web app is deployed with access 'Anyone'.")
        return text
    }

    fun callObj(body: Map<String, Any?>): JMap {
        val m = Json.parseObj(call(body))
        if (m.bool("ok") == false) error(m.str("error") ?: "The Sheet reported an error.")
        return m
    }
}

/** Sample answers for demo mode and the emulator tests. */
object Demo {
    fun answer(body: Map<String, Any?>): String = when (body["action"]) {
        "ping" -> """{"ok":true,"sheet":"Demo","model":"demo","hasKey":true}"""
        "append" -> {
            val rows = (body["rows"] as? List<*>)?.size ?: 0
            """{"ok":true,"added":$rows,"skipped":0,"photo_urls":{}}"""
        }
        "analyze" -> {
            val hint = (body["hint"] as? String).orEmpty().lowercase()
            val withHint = body["with_hint"] != false
            val ghee = withHint && "ghee" in hint
            buildString {
                append("""{"ok":true,"model":"demo","note":"Demo answer","items":[""")
                append("""{"food_id":"chapati","seen_as":"Chapati","qty":3,"unit":"piece","est_grams":${if (withHint) 105 else 90},"confidence":0.9},""")
                append("""{"food_id":"bhindi","seen_as":"Bhendi bhaji","qty":1,"unit":"katori","est_grams":${if (withHint) 130 else 150},"confidence":0.7},""")
                append("""{"food_id":"dal","seen_as":"Varan","qty":1,"unit":"katori","est_grams":${if (withHint) 160 else 140},"confidence":0.8}""")
                if (ghee) append(""",{"food_id":"ghee","seen_as":"Ghee on chapati","qty":3,"unit":"tsp","est_grams":13,"confidence":0.6,"addon_for":"Chapati"}""")
                append("]}")
            }
        }
        else -> """{"ok":false,"error":"unknown action"}"""
    }
}

object Photos {
    fun newFile(ctx: Context): File = File(ctx.filesDir, "photos").apply { mkdirs() }.let { File(it, "meal-${System.currentTimeMillis()}.jpg") }

    fun uriFor(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".photos", f)

    fun copyIn(ctx: Context, uri: Uri): File {
        val f = newFile(ctx)
        ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun decode(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        return if (scale < 1f) bmp.scale((bmp.width * scale).toInt(), (bmp.height * scale).toInt()) else bmp
    }

    /** Shrinks the photo in place to about 1280 px (roughly 150-250 KB), which is plenty to identify food. */
    fun shrink(path: String) {
        val bmp = decode(path, 1280) ?: return
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        File(path).writeBytes(out.toByteArray())
    }

    fun base64(path: String?): String? {
        if (path == null) return null
        val f = File(path)
        if (!f.exists()) return null
        return Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
    }

    fun thumb(path: String?, side: Int = 320): Bitmap? = path?.takeIf { File(it).exists() }?.let { decode(it, side) }
}

/** Sends every meal and day note not yet in the Sheet. Retries later if offline; the Sheet skips repeats. */
object Sync {
    private val mutex = Mutex()
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(Days.IST)

    suspend fun run(appVersion: String): Result<Int> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val data = Repo.state.value
                if (data.pendingMeals.isEmpty() && data.pendingDays.isEmpty()) return@runCatching 0
                val backend = Backend(data.settings)
                var sent = 0
                val batches = data.pendingMeals.chunked(4).ifEmpty { listOf(emptyList()) }
                batches.forEachIndexed { i, batch ->
                    val days = if (i == 0) data.pendingDays else emptyList()
                    val rows = batch.flatMap { it.sheetRows(Repo.db.version, appVersion, stamp.format(Instant.ofEpochMilli(it.loggedAt))) }
                    val photos = batch.filter { it.photoUrl == null }.mapNotNull { m ->
                        Photos.base64(m.photoFile)?.let { mapOf("meal_id" to m.mealId, "photo_b64" to it, "mime" to "image/jpeg") }
                    }
                    val res = backend.callObj(mapOf(
                        "action" to "append", "rows" to rows, "meals" to photos,
                        "days" to days.map { mapOf("day" to it.day, "day_type" to it.dayType, "weight_kg" to it.weightKg) },
                    ))
                    @Suppress("UNCHECKED_CAST")
                    val urls = (res.obj("photo_urls") ?: emptyMap()).mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
                    Repo.update { d -> d.markSynced(batch.map { it.mealId }, urls).markDaysSynced(days.map { it.day }) }
                    sent += batch.size
                }
                sent
            }
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Repo.init(applicationContext)
        return if (Sync.run(BuildConfig.VERSION_NAME).isSuccess) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync", ExistingWorkPolicy.KEEP, req)
        }
    }
}
