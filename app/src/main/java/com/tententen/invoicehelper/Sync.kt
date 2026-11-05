package com.tententen.invoicehelper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

const val MAX_HISTORY = 100

/** Everything shared between devices through Google Drive. */
data class SyncData(
    val companies: List<Company>,
    val deleted: Map<String, Long>,
    val profile: Profile,
    val history: List<SentRecord>,
) {
    fun toJson(): String = JSONObject()
        .put("app", "InvoiceHelper")
        .put("type", "sync")
        .put("version", 1)
        .put("savedAt", System.currentTimeMillis())
        .put("companies", JSONArray(companies.map { it.toJson() }))
        .put("deleted", JSONObject(deleted))
        .put("profile", profile.copy(googleAccount = "").toJson())
        .put("history", JSONArray(history.map { it.toJson() }))
        .toString()

    companion object {
        fun fromJson(text: String): SyncData {
            val o = JSONObject(text)
            val comps = o.optJSONArray("companies") ?: JSONArray()
            val del = o.optJSONObject("deleted") ?: JSONObject()
            val hist = o.optJSONArray("history") ?: JSONArray()
            return SyncData(
                companies = (0 until comps.length()).map { Company.fromJson(comps.getJSONObject(it)) },
                deleted = del.keys().asSequence().associateWith { del.getLong(it) },
                profile = Profile.fromJson(o.optJSONObject("profile") ?: JSONObject()),
                history = (0 until hist.length()).map { SentRecord.fromJson(hist.getJSONObject(it)) },
            )
        }
    }
}

/**
 * Merge rules (the same result whichever device runs them):
 * - Companies match by id, then short code, then name. The more recently edited copy wins its details,
 *   the next invoice number is the higher of the two, and the matched pair settles on the smaller id.
 * - A deletion removes a company unless it was edited after being deleted.
 * - Your details: the most recently edited copy wins.
 * - Sent history: union of both, newest first.
 */
object SyncMerge {
    fun merge(local: SyncData, remote: SyncData): SyncData {
        val deleted = (local.deleted.keys + remote.deleted.keys).associateWith {
            maxOf(local.deleted[it] ?: 0L, remote.deleted[it] ?: 0L)
        }

        val result = local.companies.toMutableList()
        for (r in remote.companies) {
            val idx = result.indexOfFirst { it.id == r.id }.takeIf { it >= 0 }
                ?: result.indexOfFirst { r.code.isNotBlank() && it.code.equals(r.code, ignoreCase = true) }
                    .takeIf { it >= 0 }
                ?: result.indexOfFirst { it.name.trim().equals(r.name.trim(), ignoreCase = true) }
                    .takeIf { it >= 0 }
            if (idx == null) {
                result += r
            } else {
                val l = result[idx]
                // Ties go to the cloud copy so a fresh device adopts it.
                val newer = if (r.updatedAt >= l.updatedAt) r else l
                result[idx] = newer.copy(
                    id = minOf(l.id, r.id),
                    nextInvoiceNo = maxOf(l.nextInvoiceNo, r.nextInvoiceNo),
                    updatedAt = maxOf(l.updatedAt, r.updatedAt),
                )
            }
        }
        val companies = result.filter { c -> deleted[c.id].let { it == null || it < c.updatedAt } }

        val profile = if (remote.profile.updatedAt >= local.profile.updatedAt) remote.profile else local.profile

        val history = (local.history + remote.history)
            .groupBy { it.key }
            .map { (_, recs) -> recs.maxBy { it.sentAt } }
            .sortedByDescending { it.sentAt }
            .take(MAX_HISTORY)

        return SyncData(companies, deleted, profile.copy(googleAccount = ""), history)
    }
}

/** Reads/writes the single sync file in the account's hidden Drive app folder. Call off the main thread. */
object DriveSync {
    private const val FILE_NAME = "invoicehelper-sync.json"

    /** Returns the sync file's id and contents, or (null, null) if it doesn't exist yet. */
    fun pull(token: String): Pair<String?, SyncData?> {
        val q = URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8")
        val list = JSONObject(
            Http.request(
                "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$q" +
                    "&orderBy=modifiedTime%20desc&fields=files(id,modifiedTime)",
                token, "GET", null, null, "Google Drive",
            )
        ).optJSONArray("files") ?: JSONArray()
        if (list.length() == 0) return null to null
        val id = list.getJSONObject(0).getString("id")
        val body = Http.request(
            "https://www.googleapis.com/drive/v3/files/$id?alt=media", token, "GET", null, null, "Google Drive",
        )
        return id to SyncData.fromJson(body)
    }

    /** Creates the sync file, or overwrites it if [fileId] is known. */
    fun push(token: String, fileId: String?, data: SyncData) {
        val json = data.toJson()
        if (fileId != null) {
            // Drive v2 supports a plain PUT for media updates (HttpURLConnection has no PATCH).
            Http.request(
                "https://www.googleapis.com/upload/drive/v2/files/$fileId?uploadType=media",
                token, "PUT", json.toByteArray(), "application/json; charset=UTF-8", "Google Drive",
            )
        } else {
            val boundary = "invoicehelper-${System.nanoTime()}"
            val meta = JSONObject().put("name", FILE_NAME).put("parents", JSONArray().put("appDataFolder"))
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$json\r\n--$boundary--\r\n"
            Http.request(
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart",
                token, "POST", body.toByteArray(), "multipart/related; boundary=$boundary", "Google Drive",
            )
        }
    }
}

/**
 * Saves sent invoice PDFs to the visible Drive folder Invoices/<company name>/.
 * Uses the drive.file scope, so it only sees folders/files this app created. Call off the main thread.
 */
object DriveFiles {
    private const val ROOT_FOLDER = "Invoices"
    private const val FOLDER_MIME = "application/vnd.google-apps.folder"

    /** Uploads [pdf] and returns its Drive file id. */
    fun saveInvoice(token: String, companyName: String, pdf: java.io.File): String {
        val root = folder(token, ROOT_FOLDER, "root")
        val sub = folder(token, cleanName(companyName).ifBlank { "Other" }, root)
        return JSONObject(upload(token, pdf.name, "application/pdf", pdf.readBytes(), sub, "id")).getString("id")
    }

    /**
     * Saves a job file under "DJ Jobs/<folder>/" (reusing an earlier copy with the same name) and returns
     * its id and link for attaching to the calendar event.
     */
    fun saveJobFile(token: String, folderName: String, fileName: String, mime: String, bytes: ByteArray): DriveFileInfo {
        val root = folder(token, JOBS_CALENDAR, "root")
        val sub = folder(token, cleanName(folderName).ifBlank { "Job" }, root)
        val name = cleanName(fileName)
        val q = URLEncoder.encode("name = '${quote(name)}' and '$sub' in parents and trashed = false", "UTF-8")
        val found = JSONObject(
            Http.request(
                "https://www.googleapis.com/drive/v3/files?q=$q&spaces=drive&fields=files(id,webViewLink,mimeType)",
                token, "GET", null, null, "Google Drive",
            )
        ).optJSONArray("files")
        val o = if (found != null && found.length() > 0) {
            found.getJSONObject(0)
        } else {
            JSONObject(upload(token, name, mime, bytes, sub, "id,webViewLink,mimeType"))
        }
        return DriveFileInfo(name, o.getString("id"), o.getString("webViewLink"), o.optString("mimeType", mime))
    }

    private fun cleanName(s: String) = s.replace('/', '-').replace('\\', '-').trim()

    private fun quote(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    /** Finds (or creates) the folder [name] inside [parent]. */
    private fun folder(token: String, name: String, parent: String): String {
        val q = URLEncoder.encode(
            "mimeType = '$FOLDER_MIME' and name = '${quote(name)}' and '$parent' in parents and trashed = false",
            "UTF-8",
        )
        val found = JSONObject(
            Http.request(
                "https://www.googleapis.com/drive/v3/files?q=$q&spaces=drive&orderBy=createdTime&fields=files(id)",
                token, "GET", null, null, "Google Drive",
            )
        ).optJSONArray("files")
        if (found != null && found.length() > 0) return found.getJSONObject(0).getString("id")
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).put("parents", JSONArray().put(parent))
        return JSONObject(
            Http.request(
                "https://www.googleapis.com/drive/v3/files?fields=id", token, "POST",
                meta.toString().toByteArray(), "application/json; charset=UTF-8", "Google Drive",
            )
        ).getString("id")
    }

    /** Multipart upload; returns the response JSON (with the requested [fields]). */
    private fun upload(token: String, name: String, mime: String, bytes: ByteArray, parent: String, fields: String): String {
        val boundary = "invoicehelper-${System.nanoTime()}"
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parent))
        val body = java.io.ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n".toByteArray())
            write("--$boundary\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        return Http.request(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=$fields",
            token, "POST", body, "multipart/related; boundary=$boundary", "Google Drive",
        )
    }
}

/**
 * Coordinates syncing: on app start/resume, before each Send, after changes (debounced) and on demand.
 * Only interactive syncs (Send, "Sync now") may show Google's permission screen.
 */
class SyncManager(
    private val state: AppState,
    private val auth: GoogleAuth,
    private val scope: CoroutineScope,
) {
    var syncing by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var lastSyncAt by mutableStateOf(state.lastSyncAt)
        private set

    private val mutex = Mutex()
    private var pending: Job? = null

    init {
        state.onChanged = { requestSync() }
    }

    /** Debounced background sync after local edits. */
    fun requestSync(delayMs: Long = 3_000) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            sync(interactive = false)
        }
    }

    /** Pulls, merges and pushes. Returns null on success, or a friendly error message. */
    suspend fun sync(interactive: Boolean): String? = mutex.withLock {
        val account = state.profile.googleAccount
        if (account.isBlank()) return null
        syncing = true
        val r = runCatching {
            val token = auth.token(account, interactive)
            if (!auth.hasDriveScope) throw DriveScopeMissingException()
            val (fileId, remote) = withContext(Dispatchers.IO) { DriveSync.pull(token) }
            val local = state.snapshot()
            val merged = when {
                remote == null -> local
                state.isPristine() -> remote // new device: take the cloud copy as-is
                else -> SyncMerge.merge(local, remote)
            }
            state.applySync(merged)
            withContext(Dispatchers.IO) { DriveSync.push(token, fileId, merged) }
        }
        syncing = false
        r.fold(
            onSuccess = {
                val now = System.currentTimeMillis()
                state.markSynced(now)
                lastSyncAt = now
                lastError = null
                null
            },
            onFailure = {
                val msg = Mailer.friendlyError(it)
                lastError = msg
                msg
            },
        )
    }
}
