package com.tententen.invoicehelper

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class JobScopeMissingException : Exception("Gmail read / Calendar permission not granted")

/** Bump when the parser changes so every device re-reads its MEC emails. */
private const val PARSER_VERSION = 2

/** Look back this far on the first scan (confirmations arrive up to a year+ before the gig). */
private const val FIRST_SCAN_DAYS = 430L

/** Jobs older than this aren't (re)written to the calendar. */
private const val KEEP_PAST_DAYS = 31L

const val JOBS_CALENDAR = "DJ Jobs"

/** Status of a placeholder you add yourself. */
const val UNCONFIRMED = "UNCONFIRMED"

/** An all-day "MEC unconfirmed" placeholder (one per date). */
fun unconfirmedEvent(date: LocalDate, note: String): JSONObject {
    val desc = "<b>❔ MEC unconfirmed</b><br>You added this in Invoice Helper while waiting on MEC to confirm." +
        (if (note.isNotBlank()) "<br><br>" + note.replace("&", "&amp;").replace("<", "&lt;") else "") +
        "<br><br><i>Replaced automatically when MEC confirms a gig on this date.</i>"
    return JSONObject()
        .put("id", "iu" + date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE))
        .put("summary", "❔ MEC unconfirmed" + if (note.isNotBlank()) " · $note" else "")
        .put("description", desc)
        .put("status", "confirmed")
        .put("colorId", "8")
        .put("start", JSONObject().put("date", date.toString()))
        .put("end", JSONObject().put("date", date.plusDays(1).toString()))
        .put(
            "extendedProperties",
            JSONObject().put("private", JSONObject().put("ih", "1").put("status", UNCONFIRMED).put(F.NOTES, note)),
        )
}

/**
 * What a sync does to the calendar. Removal lock: it only adds and updates. Taking a date out (released hold,
 * moved booking, MEC cancellation) becomes a [Removal] for you to approve. The only automatic removal is your own
 * "MEC unconfirmed" placeholder once MEC confirms a gig on that date.
 */
data class CalendarPlan(val writes: List<Job>, val ask: List<Removal>, val replacedUnconfirmed: List<String>) {
    companion object {
        fun make(
            jobs: List<Job>,
            existing: Map<String, CalEvent>,
            kept: Set<String>,
            pendingBefore: List<Removal>,
            today: LocalDate,
        ): CalendarPlan {
            val pending = pendingBefore.associateBy { it.eventId }.toMutableMap()
            val writes = mutableListOf<Job>()
            for (job in jobs) {
                val ex = existing[job.eventId]
                if (job.status == JobStatus.CANCELLED) {
                    // Never cancel by itself: ask, if the event is in your calendar as a live booking.
                    if (ex != null && !ex.deleted && !ex.cancelled && job.eventId !in kept) {
                        pending[job.eventId] = Removal(
                            job.eventId, job.date, ex.title, Removal.CANCEL,
                            "MEC says this booking was cancelled" + (job[F.CANCEL_PAY]?.let { " (50% pay $it)" } ?: "") +
                                ". Approve to mark it CANCELLED.",
                        )
                    }
                    continue
                }
                pending.remove(job.eventId)
                if (ex != null && ex.deleted) continue // you deleted it in Google Calendar: don't bring it back
                writes += job
            }
            val wanted = jobs.map { it.eventId }.toSet()
            val gigs = jobs.filter { it.status == JobStatus.CONFIRMED }.groupBy { it.date }
            val replaced = mutableListOf<String>()
            for (ev in existing.values) {
                if (!ev.isJob || ev.deleted || ev.date.isBefore(today)) continue
                if (ev.unconfirmed && ev.id.startsWith("iu")) {
                    // Your placeholder is replaced by the confirmed gig (the date stays in the calendar).
                    if (gigs.containsKey(ev.date)) replaced += ev.id
                    continue
                }
                if (ev.id in wanted || ev.id in kept || ev.cancelled) continue
                val gig = gigs[ev.date]?.firstOrNull()
                pending[ev.id] = Removal(
                    ev.id, ev.date, ev.title, Removal.DELETE,
                    when {
                        ev.onHold && gig != null -> "Gig confirmed that night: ${gig[F.NAME] ?: "MEC job"}."
                        ev.onHold -> "No longer on hold in MEC's emails or latest monthly report."
                        else -> "No longer matches MEC's emails (the booking may have moved or been merged)."
                    },
                )
            }
            // Drop requests for events that are already gone.
            pending.keys.retainAll { id -> existing[id]?.let { !it.deleted } ?: false }
            return CalendarPlan(writes, pending.values.sortedBy { it.date }, replaced)
        }
    }
}

/** A removal waiting for your OK. */
data class Removal(val eventId: String, val date: LocalDate, val title: String, val action: String, val reason: String) {
    fun toJson(): JSONObject = JSONObject().put("id", eventId).put("d", date.toString()).put("t", title)
        .put("a", action).put("r", reason)

    companion object {
        const val DELETE = "delete"
        const val CANCEL = "cancel"
        fun fromJson(o: JSONObject) = Removal(
            o.getString("id"), LocalDate.parse(o.getString("d")), o.optString("t"), o.optString("a", DELETE), o.optString("r"),
        )
    }
}

/** Local state for MEC job import (this device only; Gmail + Calendar are the shared truth). */
class JobStore(context: Context) {
    private val prefs = context.getSharedPreferences("jobs", Context.MODE_PRIVATE)

    var facts: List<MailFact>
        get() = prefs.getString("facts", null)?.let { s ->
            val a = JSONArray(s)
            (0 until a.length()).map { MailFact.fromJson(a.getJSONObject(it)) }
        } ?: emptyList()
        set(v) = prefs.edit().putString("facts", JSONArray(v.map { it.toJson() }).toString()).apply()

    var seen: Set<String>
        get() = prefs.getStringSet("seen", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("seen", v).apply()

    var lastScanSec: Long
        get() = prefs.getLong("lastScan", 0)
        set(v) = prefs.edit().putLong("lastScan", v).apply()

    var parserVersion: Int
        get() = prefs.getInt("parserVersion", 0)
        set(v) = prefs.edit().putInt("parserVersion", v).apply()

    var calendarId: String?
        get() = prefs.getString("calendarId", null)
        set(v) = prefs.edit().putString("calendarId", v).apply()

    var report: ReportInfo?
        get() = prefs.getString("report", null)?.let { ReportInfo.fromJson(JSONObject(it)) }
        set(v) = prefs.edit().putString("report", v?.toJson()?.toString()).apply()

    /** eventId -> hash of what we last wrote, so unchanged events aren't rewritten. */
    var pushed: Map<String, String>
        get() = prefs.getString("pushed", null)?.let { s -> JSONObject(s).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }
            ?: emptyMap()
        set(v) = prefs.edit().putString("pushed", JSONObject(v).toString()).apply()

    /** "messageId/fileName" -> Drive copy. */
    var files: Map<String, DriveFileInfo>
        get() = prefs.getString("files", null)?.let { s ->
            JSONObject(s).let { o -> o.keys().asSequence().associateWith { DriveFileInfo.fromJson(o.getJSONObject(it)) } }
        } ?: emptyMap()
        set(v) = prefs.edit().putString("files", JSONObject(v.mapValues { it.value.toJson() }).toString()).apply()

    /** Raw Calendar API event items, for instant display before the next refresh. */
    var eventsJson: String?
        get() = prefs.getString("events", null)
        set(v) = prefs.edit().putString("events", v).apply()

    /** The DJ Jobs calendar's colour in Google Calendar ("#rrggbb"). */
    var calendarColor: String?
        get() = prefs.getString("calendarColor", null)
        set(v) = prefs.edit().putString("calendarColor", v).apply()

    var lastSyncAt: Long
        get() = prefs.getLong("lastSyncAt", 0)
        set(v) = prefs.edit().putLong("lastSyncAt", v).apply()

    /** Removals waiting for your OK (the app never removes a date by itself). */
    var pendingRemovals: List<Removal>
        get() = prefs.getString("pendingRemovals", null)?.let { s ->
            JSONArray(s).let { a -> (0 until a.length()).map { Removal.fromJson(a.getJSONObject(it)) } }
        } ?: emptyList()
        set(v) = prefs.edit().putString("pendingRemovals", JSONArray(v.map { it.toJson() }).toString()).apply()

    /** Event ids you chose to keep: not suggested for removal again. */
    var kept: Set<String>
        get() = prefs.getStringSet("kept", emptySet())!!.toSet()
        set(v) = prefs.edit().putStringSet("kept", v).apply()

    fun reset() {
        prefs.edit().remove("facts").remove("seen").remove("lastScan").remove("report").remove("pushed").apply()
    }
}

/** Read-only Gmail access (gmail.readonly). Call off the main thread. */
object GmailReader {
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"

    data class Meta(val id: String, val subject: String, val from: String, val sentAt: Long)

    data class Full(val meta: Meta, val html: String?, val text: String?, val attachments: List<AttachmentRef>)

    private fun get(token: String, url: String) = JSONObject(Http.request(url, token, "GET", null, null, "Gmail"))

    fun listIds(token: String, query: String): List<String> {
        val ids = mutableListOf<String>()
        var page: String? = null
        do {
            val url = "$BASE/messages?maxResults=500&q=" + URLEncoder.encode(query, "UTF-8") +
                (page?.let { "&pageToken=$it" } ?: "")
            val o = get(token, url)
            o.optJSONArray("messages")?.let { a -> for (i in 0 until a.length()) ids += a.getJSONObject(i).getString("id") }
            page = o.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null)
        return ids
    }

    private fun meta(o: JSONObject): Meta {
        val headers = o.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
        var subject = ""
        var from = ""
        for (i in 0 until headers.length()) {
            val h = headers.getJSONObject(i)
            when (h.optString("name").lowercase()) {
                "subject" -> subject = h.optString("value")
                "from" -> from = h.optString("value")
            }
        }
        return Meta(o.getString("id"), subject, from, o.optString("internalDate").toLongOrNull() ?: 0L)
    }

    fun metadata(token: String, id: String): Meta =
        meta(get(token, "$BASE/messages/$id?format=metadata&metadataHeaders=Subject&metadataHeaders=From"))

    fun full(token: String, id: String): Full {
        val o = get(token, "$BASE/messages/$id?format=full")
        val html = StringBuilder()
        val text = StringBuilder()
        val atts = mutableListOf<AttachmentRef>()
        fun walk(p: JSONObject) {
            val mime = p.optString("mimeType")
            val body = p.optJSONObject("body") ?: JSONObject()
            val name = p.optString("filename")
            if (name.isNotBlank() && body.has("attachmentId")) {
                atts += AttachmentRef(id, body.getString("attachmentId"), name, mime, body.optInt("size"))
            } else if (body.has("data")) {
                val s = String(decode(body.getString("data")), Charsets.UTF_8)
                if (mime == "text/html") html.append(s) else if (mime == "text/plain") text.append(s)
            }
            p.optJSONArray("parts")?.let { a -> for (i in 0 until a.length()) walk(a.getJSONObject(i)) }
        }
        walk(o.getJSONObject("payload"))
        return Full(meta(o), html.toString().ifBlank { null }, text.toString().ifBlank { null }, atts)
    }

    fun attachment(token: String, messageId: String, attachmentId: String): ByteArray =
        decode(get(token, "$BASE/messages/$messageId/attachments/$attachmentId").getString("data"))

    private fun decode(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

/** An event as shown in the app. [props] holds the job details written by [JobEvent]. */
data class CalEvent(
    val id: String,
    val title: String,
    val date: LocalDate,
    val start: ZonedDateTime?,
    val end: ZonedDateTime?,
    val location: String,
    val description: String,
    val htmlLink: String,
    val files: List<Pair<String, String>>,
    val props: Map<String, String>,
    val status: String = "confirmed",
    /** Google Calendar event colour ("1"-"11"), or null for the calendar's colour. */
    val colorId: String? = null,
) {
    val isJob get() = props["ih"] == "1"
    val cancelled get() = props["status"] == JobStatus.CANCELLED.name
    val onHold get() = props["status"] == JobStatus.HOLD.name
    val unconfirmed get() = props["status"] == UNCONFIRMED

    /** Deleted in Google Calendar (only seen when listing with showDeleted). */
    val deleted get() = status == "cancelled"
    operator fun get(key: String): String? = props[key]?.takeIf { it.isNotBlank() }

    companion object {
        fun fromJson(o: JSONObject): CalEvent? {
            val zone = ZoneId.of(MEL_TZ)
            val s = o.optJSONObject("start") ?: return null
            val e = o.optJSONObject("end") ?: JSONObject()
            val start = s.optString("dateTime").takeIf { it.isNotBlank() }?.let { ZonedDateTime.parse(it).withZoneSameInstant(zone) }
            val end = e.optString("dateTime").takeIf { it.isNotBlank() }?.let { ZonedDateTime.parse(it).withZoneSameInstant(zone) }
            val date = start?.toLocalDate() ?: s.optString("date").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) } ?: return null
            val p = o.optJSONObject("extendedProperties")?.optJSONObject("private") ?: JSONObject()
            val att = o.optJSONArray("attachments") ?: JSONArray()
            return CalEvent(
                id = o.optString("id"),
                title = o.optString("summary"),
                date = date,
                start = start,
                end = end,
                location = o.optString("location"),
                description = o.optString("description"),
                htmlLink = o.optString("htmlLink"),
                files = (0 until att.length()).map { att.getJSONObject(it).let { a -> a.optString("title") to a.optString("fileUrl") } },
                props = p.keys().asSequence().associateWith { p.getString(it) },
                status = o.optString("status", "confirmed"),
                colorId = o.optString("colorId").takeIf { it.isNotBlank() },
            )
        }

        fun listFromJson(raw: String?): List<CalEvent> = raw?.let { s ->
            runCatching { JSONArray(s).let { a -> (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it)) } } }.getOrNull()
        } ?: emptyList()
    }
}

/** Google Calendar REST (calendar scope). Call off the main thread. */
object CalendarApi {
    private const val BASE = "https://www.googleapis.com/calendar/v3"

    /** Finds the "DJ Jobs" calendar (or creates it) and returns its id. */
    fun ensureCalendar(token: String, known: String?): String {
        if (known != null) {
            val ok = runCatching {
                Http.request("$BASE/users/me/calendarList/${enc(known)}", token, "GET", null, null, "Google Calendar")
            }
            if (ok.isSuccess) return known
            val err = ok.exceptionOrNull()
            if (err !is ApiHttpException || err.code != 404) throw err!!
        }
        var page: String? = null
        do {
            val o = JSONObject(
                Http.request(
                    "$BASE/users/me/calendarList?maxResults=250" + (page?.let { "&pageToken=$it" } ?: ""),
                    token, "GET", null, null, "Google Calendar",
                )
            )
            val items = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val c = items.getJSONObject(i)
                if (c.optString("summary") == JOBS_CALENDAR && c.optString("accessRole") == "owner") return c.getString("id")
            }
            page = o.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null)
        val created = JSONObject(
            Http.request(
                "$BASE/calendars", token, "POST",
                JSONObject().put("summary", JOBS_CALENDAR).put("timeZone", MEL_TZ)
                    .put("description", "Gigs imported from Melbourne Entertainment Co. emails by Invoice Helper.")
                    .toString().toByteArray(),
                "application/json; charset=UTF-8", "Google Calendar",
            )
        ).getString("id")
        // Grape-coloured so jobs stand out (best effort).
        runCatching {
            Http.request(
                "$BASE/users/me/calendarList/${enc(created)}?colorRgbFormat=false", token, "PUT",
                JSONObject().put("id", created).put("colorId", "3").toString().toByteArray(),
                "application/json; charset=UTF-8", "Google Calendar",
            )
        }
        return created
    }

    /** The calendar's colour as shown in Google Calendar. */
    fun color(token: String, calendarId: String): String? = JSONObject(
        Http.request("$BASE/users/me/calendarList/${enc(calendarId)}", token, "GET", null, null, "Google Calendar")
    ).optString("backgroundColor").takeIf { it.isNotBlank() }

    /** Deletes an event (already-deleted is fine). */
    fun delete(token: String, calendarId: String, eventId: String) {
        try {
            Http.request(
                "$BASE/calendars/${enc(calendarId)}/events/${enc(eventId)}", token, "DELETE", null, null, "Google Calendar",
            )
        } catch (e: ApiHttpException) {
            if (e.code != 404 && e.code != 410) throw e
        }
    }

    /** Updates the event with this id, or creates it. */
    fun upsert(token: String, calendarId: String, event: JSONObject) {
        val id = event.getString("id")
        val body = event.toString().toByteArray()
        val type = "application/json; charset=UTF-8"
        val base = "$BASE/calendars/${enc(calendarId)}/events"
        try {
            Http.request("$base/${enc(id)}?supportsAttachments=true", token, "PUT", body, type, "Google Calendar")
        } catch (e: ApiHttpException) {
            if (e.code != 404) throw e
            Http.request("$base?supportsAttachments=true", token, "POST", body, type, "Google Calendar")
        }
    }

    /** Raw event items between the two instants, in start order. */
    fun list(token: String, calendarId: String, from: Instant, to: Instant, showDeleted: Boolean = false): JSONArray {
        val out = JSONArray()
        var page: String? = null
        do {
            val o = JSONObject(
                Http.request(
                    "$BASE/calendars/${enc(calendarId)}/events?singleEvents=true&orderBy=startTime&maxResults=250" +
                        (if (showDeleted) "&showDeleted=true" else "") +
                        "&timeMin=${enc(from.toString())}&timeMax=${enc(to.toString())}" +
                        (page?.let { "&pageToken=$it" } ?: ""),
                    token, "GET", null, null, "Google Calendar",
                )
            )
            val items = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) out.put(items.getJSONObject(i))
            page = o.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null)
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** PDF reading with PdfBox (text, links, and word positions for the Zoho table). */
object PdfText {
    @Volatile private var ready = false

    fun init(ctx: Context) {
        if (!ready) {
            PDFBoxResourceLoader.init(ctx.applicationContext)
            ready = true
        }
    }

    fun textAndLinks(bytes: ByteArray): String = runCatching {
        PDDocument.load(bytes).use { doc ->
            val sb = StringBuilder(PDFTextStripper().getText(doc))
            for (page in doc.pages) {
                for (a in page.annotations) {
                    val uri = ((a as? PDAnnotationLink)?.action as? PDActionURI)?.uri
                    if (uri != null) sb.append('\n').append(uri)
                }
            }
            sb.toString()
        }
    }.getOrDefault("")

    fun words(bytes: ByteArray): List<PdfWord> = PDDocument.load(bytes).use { doc ->
        val out = mutableListOf<PdfWord>()
        var pageNo = 0
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                val cur = StringBuilder()
                var x = 0f
                var y = 0f
                var lastEnd = -1f
                fun flush() {
                    if (cur.isNotBlank()) out += PdfWord(pageNo, x, y, cur.toString().trim())
                    cur.clear()
                }
                for (tp in positions) {
                    val u = tp.unicode ?: continue
                    val gap = if (lastEnd >= 0) tp.xDirAdj - lastEnd else 0f
                    if (u.isBlank() || gap > tp.widthDirAdj * 1.5f + 1f) flush()
                    if (u.isNotBlank()) {
                        if (cur.isEmpty()) {
                            x = tp.xDirAdj
                            y = tp.yDirAdj
                        }
                        cur.append(u)
                    }
                    lastEnd = tp.xDirAdj + tp.widthDirAdj
                }
                flush()
            }
        }
        stripper.sortByPosition = true
        for (p in 1..doc.numberOfPages) {
            pageNo = p
            stripper.startPage = p
            stripper.endPage = p
            stripper.getText(doc)
        }
        out
    }
}

/**
 * Reads MEC emails from Gmail, keeps the "DJ Jobs" Google Calendar in step, and loads the calendar for the
 * Jobs screen. Job-sheet files go to Drive under "DJ Jobs/<date> <name>/" and are attached to the event.
 */
class JobSyncManager(
    private val context: Context,
    private val state: AppState,
    private val auth: GoogleAuth,
) {
    private val store = JobStore(context)

    var syncing by mutableStateOf(false)
        private set
    var progress by mutableStateOf<String?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var lastSyncAt by mutableStateOf(store.lastSyncAt)
        private set
    var events by mutableStateOf(CalEvent.listFromJson(store.eventsJson))
        private set
    var pendingRemovals by mutableStateOf(store.pendingRemovals)
        private set
    var calendarColor by mutableStateOf(store.calendarColor)
        private set

    private val mutex = Mutex()

    /** The emails behind an event, newest first, with subject and date when this device has read them. */
    fun emailsFor(e: CalEvent): List<JobEmail> {
        val refs = e["emails"]?.lines()?.mapNotNull { line ->
            val kind = runCatching { MailKind.valueOf(line.substringBefore(':')) }.getOrNull() ?: return@mapNotNull null
            kind to line.substringAfter(':')
        } ?: e["jobSheetUrl"]?.let { listOf(MailKind.JOBSHEET to it.substringAfterLast('/')) }.orEmpty()
        if (refs.isEmpty()) return emptyList()
        val facts = store.facts.associateBy { it.messageId.substringBefore('#') }
        return refs.map { (kind, id) ->
            val f = facts[id]
            JobEmail(id, kind, f?.subject?.takeIf { kind != MailKind.ZOHO }?.let { JobParser.cleanSubject(it) }, f?.sentAt)
        }
    }

    /** Reads one email from Gmail to show in the app. */
    suspend fun loadEmail(messageId: String): Result<GmailReader.Full> = runCatching {
        val token = auth.token(state.profile.googleAccount, interactive = true)
        withContext(Dispatchers.IO) { GmailReader.full(token, messageId) }
    }

    /** Downloads an email attachment and opens it in a viewer app. Returns an error to show, or null. */
    suspend fun openAttachment(ctx: Context, a: AttachmentRef): String? = runCatching {
        val token = auth.token(state.profile.googleAccount, interactive = true)
        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "mail/${a.messageId}").apply { mkdirs() }
            File(dir, a.fileName.replace(Regex("""[\\/:*?"<>|]"""), "_")).also { f ->
                if (!f.exists() || f.length() == 0L) f.writeBytes(GmailReader.attachment(token, a.messageId, a.attachmentId))
            }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        // Go by the file extension first, like a file manager: senders often label .docx files
        // "application/zip", which offers archive apps instead of document viewers.
        val mime = attachmentMime(file.name, a.mimeType)
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(view)
            null
        } catch (e: android.content.ActivityNotFoundException) {
            "No app on this phone opens ${file.extension.uppercase()} files."
        }
    }.getOrElse { Mailer.friendlyError(it) }

    /** Returns null on success, or a friendly error. */
    suspend fun sync(interactive: Boolean): String? = mutex.withLock {
        val account = state.profile.googleAccount
        if (account.isBlank()) return null
        syncing = true
        val r = runCatching {
            val token = auth.token(account, interactive)
            if (!auth.hasJobScopes) throw JobScopeMissingException()
            withContext(Dispatchers.IO) {
                PdfText.init(context)
                scan(token)
                writeCalendar(token, account)
                loadEvents(token)
            }
        }
        syncing = false
        progress = null
        r.fold(
            onSuccess = {
                lastSyncAt = System.currentTimeMillis().also { store.lastSyncAt = it }
                lastError = null
                null
            },
            onFailure = { Mailer.friendlyError(it).also { msg -> lastError = msg } },
        )
    }

    private fun today() = LocalDate.now(ZoneId.of(MEL_TZ))

    // ------------------------------------------------------------------ Gmail -> facts

    private fun scan(token: String) {
        if (store.parserVersion != PARSER_VERSION) {
            store.reset()
            store.parserVersion = PARSER_VERSION
        }
        val now = System.currentTimeMillis() / 1000
        val after = if (store.lastScanSec == 0L) now - FIRST_SCAN_DAYS * 86_400 else store.lastScanSec - 3 * 86_400
        progress = "Checking Gmail for MEC emails…"
        val ids = GmailReader.listIds(token, "from:(melbentco.com.au OR systemgenerated@zohocrm.com) after:$after")
        val seen = store.seen.toMutableSet()
        val facts = store.facts.toMutableList()
        val todo = ids.filter { it !in seen }
        val cutoff = today().minusDays(KEEP_PAST_DAYS)
        todo.forEachIndexed { i, id ->
            progress = "Reading MEC emails ${i + 1}/${todo.size}…"
            val meta = GmailReader.metadata(token, id)
            val kind = JobParser.kind(meta.subject, meta.from)
            val subjectDate = JobParser.parseDate(JobParser.cleanSubject(meta.subject))
            val wanted = JobParser.isUseful(kind) && (subjectDate == null || !subjectDate.isBefore(cutoff))
            if (wanted) {
                if (kind == MailKind.ZOHO) {
                    readReport(token, meta, facts)
                } else {
                    val full = GmailReader.full(token, id)
                    // A badly formed email shouldn't stop the rest from being read.
                    var fact = runCatching {
                        JobParser.parse(id, meta.sentAt, meta.subject, meta.from, full.html, full.text, full.attachments)
                    }.getOrNull()
                    if (fact != null && (fact.date == null || !fact.date!!.isBefore(cutoff))) {
                        fact = withLinksFromFiles(token, fact)
                    }
                    if (fact != null) {
                        facts.removeAll { it.messageId == id }
                        facts += fact
                    }
                }
            }
            seen += id
            // Save as we go so a long first scan isn't lost if interrupted.
            if (i % 20 == 19) {
                store.facts = facts
                store.seen = seen
            }
        }
        store.facts = facts
        store.seen = seen
        store.lastScanSec = now
    }

    /** Adds Spotify / Drive links found inside attached Word and PDF files (song forms, run sheets). */
    private fun withLinksFromFiles(token: String, fact: MailFact): MailFact {
        var spotify = fact.spotify
        var links = fact.links
        for (a in fact.attachments) {
            val ext = a.fileName.substringAfterLast('.', "").lowercase()
            if (ext != "pdf" && ext != "docx") continue
            if (a.size > 15_000_000) continue
            val bytes = runCatching { cachedAttachment(token, a) }.getOrNull() ?: continue
            val text = if (ext == "pdf") PdfText.textAndLinks(bytes) else JobParser.docxText(bytes)
            spotify = (spotify + JobParser.spotifyLinks(text)).distinctBy { it.substringBefore('?').lowercase() }
            links = (links + JobParser.docLinks(text)).distinctBy { it.substringBefore('?').lowercase() }
        }
        return fact.copy(spotify = spotify, links = links)
    }

    private fun cachedAttachment(token: String, a: AttachmentRef): ByteArray {
        val f = File(context.cacheDir, "mec/${a.messageId}/${a.fileName.replace('/', '_')}")
        if (f.exists() && f.length() > 0) return f.readBytes()
        val bytes = GmailReader.attachment(token, a.messageId, a.attachmentId)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return bytes
    }

    private fun readReport(token: String, meta: GmailReader.Meta, facts: MutableList<MailFact>) {
        val current = store.report
        if (current != null && current.sentAt >= meta.sentAt) return // only the newest report matters
        val full = GmailReader.full(token, meta.id)
        val pdf = full.attachments.firstOrNull { it.fileName.endsWith(".pdf", true) } ?: return
        val bytes = GmailReader.attachment(token, meta.id, pdf.attachmentId)
        val (generated, rows) = runCatching { ZohoReport.parse(PdfText.words(bytes)) }.getOrNull() ?: return
        facts.removeAll { JobBook.isReportFact(it) }
        facts += JobBook.reportFacts(meta.id, meta.sentAt, rows)
        store.report = ReportInfo(meta.id, meta.sentAt, generated)
    }

    // ------------------------------------------------------------------ facts -> calendar

    private fun calendarWindow(): Pair<Instant, Instant> {
        val zone = ZoneId.of(MEL_TZ)
        return today().minusMonths(2).withDayOfMonth(1).atStartOfDay(zone).toInstant() to
            today().plusMonths(18).atStartOfDay(zone).toInstant()
    }

    private fun currentJobs() = JobBook.build(store.facts, store.report)
        .filter { it.status != JobStatus.PENDING && !it.date.isBefore(today().minusDays(KEEP_PAST_DAYS)) }

    /**
     * Adds and updates events. Removal lock: this never deletes or cancels an event. Anything that would take a
     * date out of the calendar goes to [JobStore.pendingRemovals] for you to approve, except "MEC unconfirmed"
     * placeholders, which are replaced once MEC confirms a gig that night.
     */
    private fun writeCalendar(token: String, account: String) {
        progress = "Updating your calendar…"
        val calId = CalendarApi.ensureCalendar(token, store.calendarId)
        if (calId != store.calendarId) {
            store.calendarId = calId
            store.pushed = emptyMap()
        }
        val (from, to) = calendarWindow()
        val existing = CalEvent.listFromJson(CalendarApi.list(token, calId, from, to, showDeleted = true).toString())
            .associateBy { it.id }
        val plan = CalendarPlan.make(currentJobs(), existing, store.kept, store.pendingRemovals, today())
        val pushed = store.pushed.toMutableMap()
        for (job in plan.writes) {
            val files = if (!job.date.isBefore(today().minusDays(1))) driveCopies(token, job) else emptyList()
            val ev = JobEvent.toJson(job, account, files)
            val hash = ev.toString().hashCode().toString()
            if (pushed[job.eventId] == hash && existing.containsKey(job.eventId)) continue
            progress = "Adding ${job.date.format(SHORT_DATE)} ${job[F.NAME] ?: ""}…"
            CalendarApi.upsert(token, calId, ev)
            pushed[job.eventId] = hash
            store.pushed = pushed
        }
        for (id in plan.replacedUnconfirmed) {
            progress = "Replacing MEC unconfirmed with the confirmed gig…"
            CalendarApi.delete(token, calId, id)
        }
        store.pendingRemovals = plan.ask
        pendingRemovals = plan.ask
    }

    /** Copies the job's attachments to Drive (once) so they can be attached to the event. */
    private fun driveCopies(token: String, job: Job): List<DriveFileInfo> {
        if (job.attachments.isEmpty() || !auth.hasDriveFileScope) return emptyList()
        val known = store.files.toMutableMap()
        val folder = "${job.date} ${(job[F.NAME] ?: "Job").take(60)}"
        val out = mutableListOf<DriveFileInfo>()
        for (a in job.attachments) {
            val key = "${a.messageId}/${a.fileName}"
            val existing = known[key]
            if (existing != null) {
                out += existing
                continue
            }
            progress = "Saving ${a.fileName} to Drive…"
            val info = runCatching {
                val bytes = cachedAttachment(token, a)
                DriveFiles.saveJobFile(token, folder, a.fileName, a.mimeType.ifBlank { "application/octet-stream" }, bytes)
            }.getOrNull() ?: continue
            known[key] = info
            store.files = known
            out += info
        }
        return out
    }

    // ------------------------------------------------------------------ calendar -> screen

    private fun loadEvents(token: String) {
        val calId = store.calendarId ?: return
        runCatching { CalendarApi.color(token, calId) }.getOrNull()?.let {
            store.calendarColor = it
            calendarColor = it
        }
        val (from, to) = calendarWindow()
        val items = CalendarApi.list(token, calId, from, to)
        store.eventsJson = items.toString()
        events = CalEvent.listFromJson(items.toString())
    }

    /** Runs [block] with a Google token and the calendar id, then refreshes the list. Returns an error or null. */
    private suspend fun act(block: (token: String, calId: String) -> Unit): String? = mutex.withLock {
        val account = state.profile.googleAccount
        if (account.isBlank()) return "Sign in with Google on the Me tab first."
        syncing = true
        val r = runCatching {
            val token = auth.token(account, interactive = true)
            if (!auth.hasJobScopes) throw JobScopeMissingException()
            withContext(Dispatchers.IO) {
                val calId = CalendarApi.ensureCalendar(token, store.calendarId).also { store.calendarId = it }
                block(token, calId)
                loadEvents(token)
            }
        }
        syncing = false
        progress = null
        r.exceptionOrNull()?.let { Mailer.friendlyError(it) }
    }

    /** You approved a removal: delete the event, or mark a cancelled booking CANCELLED. */
    suspend fun approveRemoval(r: Removal): String? = act { token, calId ->
        if (r.action == Removal.CANCEL) {
            val job = currentJobs().firstOrNull { it.eventId == r.eventId && it.status == JobStatus.CANCELLED }
            if (job != null) CalendarApi.upsert(token, calId, JobEvent.toJson(job, state.profile.googleAccount, emptyList()))
        } else {
            CalendarApi.delete(token, calId, r.eventId)
        }
        store.pendingRemovals = store.pendingRemovals.filter { it.eventId != r.eventId }
        pendingRemovals = store.pendingRemovals
    }

    /** You chose to keep it: leave the event alone and don't ask again. */
    fun keep(r: Removal) {
        store.kept = store.kept + r.eventId
        store.pendingRemovals = store.pendingRemovals.filter { it.eventId != r.eventId }
        pendingRemovals = store.pendingRemovals
    }

    /** Adds (or updates) your "MEC unconfirmed" placeholder for [date]. */
    suspend fun addUnconfirmed(date: LocalDate, note: String): String? {
        if (events.any { it.date == date && it.isJob && !it.cancelled && !it.onHold && !it.unconfirmed }) {
            return "You already have a confirmed MEC gig on ${date.format(SHORT_DATE)}."
        }
        return act { token, calId -> CalendarApi.upsert(token, calId, unconfirmedEvent(date, note.trim())) }
    }

    /** You removed your own placeholder. */
    suspend fun removeUnconfirmed(e: CalEvent): String? = act { token, calId -> CalendarApi.delete(token, calId, e.id) }

    /** Forget what was read and rebuild everything on the next sync. */
    fun rescan() {
        store.reset()
    }
}
