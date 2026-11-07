package com.tententen.invoicehelper

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class JobStatus { CONFIRMED, CANCELLED, PENDING, HOLD }

/** One gig, merged from every MEC email about it. */
data class Job(
    val eventId: String,
    val date: LocalDate,
    val status: JobStatus,
    val fields: Map<String, String>,
    val spotify: List<String>,
    val links: List<String>,
    val attachments: List<AttachmentRef>,
    /** Gmail message ids of the job sheet / confirmation emails, newest first. */
    val sources: List<Pair<MailKind, String>>,
    /** Confirmed, but missing from MEC's latest monthly report: worth double-checking. */
    val notInReport: Boolean,
) {
    operator fun get(key: String): String? = fields[key]
}

/** The latest Zoho monthly report: when it was emailed, its "generated" date, and its booking rows. */
data class ReportInfo(val messageId: String, val sentAt: Long, val generated: LocalDate?) {
    fun toJson(): JSONObject = JSONObject().put("id", messageId).put("at", sentAt).put("g", generated?.toString() ?: "")

    companion object {
        fun fromJson(o: JSONObject) = ReportInfo(
            o.optString("id"), o.optLong("at"),
            o.optString("g").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
        )
    }
}

/**
 * Groups [MailFact]s into [Job]s. A fact joins a job on the same date that shares a name word
 * (e.g. "Jaden Christensen | Jaden + Kiara" and "Jaden + Kiara"), so two gigs on one day stay separate.
 * Detail fields come from the best source: job sheet, then confirmation, then e-introduction, then the
 * monthly report; newer emails win within a kind.
 */
object JobBook {
    private val STOP = setOf(
        "the", "and", "wedding", "party", "birthday", "corporate", "event", "events", "christmas", "xmas",
        "night", "awards", "award", "school", "engagement", "job", "sheet", "confirmed", "for", "with", "mec",
        "day", "dinner", "function", "celebration", "solo", "residency", "reception", "ceremony", "annual",
        "end", "year", "ladies", "formal", "new", "booking", "song", "list", "saturday", "sunday", "monday",
        "tuesday", "wednesday", "thursday", "friday", "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december", "first", "dance", "music", "form",
        "due", "confirmation", "re", "fwd", "updated", "introduction", "pty", "ltd", "group",
    )

    fun tokens(s: String?): List<String> = Regex("""[\p{L}\p{N}']+""").findAll((s ?: "").lowercase())
        .map { it.value.trim('\'') }
        .filter { it.length >= 3 && it !in STOP && !it.all(Char::isDigit) && !Regex("""\d+(st|nd|rd|th)""").matches(it) }
        .distinct().toList()

    private fun rank(k: MailKind) = when (k) {
        MailKind.JOBSHEET -> 6
        MailKind.CONFIRMED -> 5
        MailKind.INTRO -> 4
        MailKind.ZOHO -> 3
        MailKind.CANCELLED -> 2
        MailKind.INVOICED -> 1
        else -> 0
    }

    private val CONFIRMING = setOf(MailKind.JOBSHEET, MailKind.CONFIRMED, MailKind.INTRO, MailKind.ZOHO)

    private class Group(val date: LocalDate, val firstToken: String) {
        val tokens = LinkedHashSet<String>()
        val facts = mutableListOf<MailFact>()
    }

    fun eventId(date: LocalDate, token: String): String {
        val hash = MessageDigest.getInstance("SHA-1").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)
        // Google Calendar ids allow 0-9 and a-v only.
        return "ih" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + hash
    }

    fun build(facts: List<MailFact>, report: ReportInfo? = null): List<Job> {
        val groups = mutableListOf<Group>()
        val loose = mutableListOf<MailFact>()
        for (f in facts.sortedWith(compareBy({ it.sentAt }, { it.messageId }))) {
            if (f.kind == MailKind.HOLD || f.kind == MailKind.OTHER) continue
            val date = f.date
            if (date == null) {
                if (f.kind == MailKind.RELATED) loose += f
                continue
            }
            val toks = tokens(f.fields[F.NAME] + " " + f.fields[F.CLIENT].orEmpty())
            val sameDay = groups.filter { it.date == date }
            var g = sameDay.firstOrNull { gr -> toks.any { it in gr.tokens } }
            if (g == null && f.kind == MailKind.RELATED && sameDay.size == 1) g = sameDay[0]
            if (g == null) {
                if (f.kind == MailKind.RELATED) {
                    loose += f
                    continue
                }
                g = Group(date, toks.firstOrNull() ?: "job").also { groups += it }
            }
            g.tokens += toks
            g.facts += f
        }
        // Undated extras (e.g. "SONG LIST FOR SATURDAY - <name>") go to the next job with a matching name.
        for (f in loose) {
            val toks = tokens(f.subject)
            val sent = java.time.Instant.ofEpochMilli(f.sentAt).atZone(ZoneId.of(MEL_TZ)).toLocalDate()
            groups.filter { g -> !g.date.isBefore(sent.minusDays(2)) && toks.any { it in g.tokens } }
                .minByOrNull { it.date }?.facts?.add(f)
        }
        val jobs = groups.map { toJob(it, report) }
        return (jobs + holds(facts, jobs, report)).sortedBy { it.date }
    }

    /**
     * On-hold nights, from "ON HOLD CONFIRMATION" emails and the monthly report's ON HOLD rows.
     * A hold shows while it's in the latest report or its email is newer than that report, and goes away
     * once a gig is confirmed that night.
     */
    private fun holds(facts: List<MailFact>, jobs: List<Job>, report: ReportInfo?): List<Job> {
        val booked = jobs.filter { it.status == JobStatus.CONFIRMED }.map { it.date }.toSet()
        return facts.filter { it.kind == MailKind.HOLD && it.date != null }
            .groupBy { it.date!! }
            .filterKeys { it !in booked }
            .mapNotNull { (date, hs) ->
                val current = hs.filter { h ->
                    if (h.messageId.contains('#')) report != null && h.messageId.startsWith(report.messageId + "#")
                    else report == null || h.sentAt > report.sentAt ||
                        hs.any { it.messageId.startsWith(report.messageId + "#") }
                }
                if (current.isEmpty()) return@mapNotNull null
                val fields = linkedMapOf(F.NAME to "ON HOLD", F.TYPE to "On hold")
                hs.filter { !it.messageId.contains('#') }.maxByOrNull { it.sentAt }?.fields?.get(F.PAY)?.let { fields[F.PAY] = it }
                // "Has Been INVOICED" for that night: a likely gig, waiting on the client's payment.
                jobs.firstOrNull { it.date == date && it.status == JobStatus.PENDING }?.let { p ->
                    p[F.NAME]?.let { fields[F.INVOICED] = it }
                    p[F.VENUE]?.let { fields[F.INVOICED_VENUE] = it }
                }
                Job(
                    eventId = eventId(date, "on-hold"),
                    date = date,
                    status = JobStatus.HOLD,
                    fields = fields,
                    spotify = emptyList(),
                    links = emptyList(),
                    attachments = emptyList(),
                    sources = emailSources(hs.sortedByDescending { it.sentAt }),
                    notInReport = false,
                )
            }
    }

    /** All rows of a monthly report as facts (bookings as [MailKind.ZOHO], holds as [MailKind.HOLD]). */
    fun reportFacts(reportId: String, sentAt: Long, rows: List<ZohoRow>): List<MailFact> = rows.mapIndexed { i, r ->
        if (r.onHold) MailFact("$reportId#$i", sentAt, MailKind.HOLD, "Monthly bookings report", r.date, mapOf(F.NAME to "ON HOLD"))
        else zohoFact(reportId, sentAt, i, r)
    }

    /** The Gmail messages behind some facts, newest first; report rows ("id#3") become the report email. */
    fun emailSources(newestFirst: List<MailFact>): List<Pair<MailKind, String>> = newestFirst
        .map { (if (isReportFact(it)) MailKind.ZOHO else it.kind) to it.messageId.substringBefore('#') }
        .distinctBy { it.second }

    /** Facts that came from a monthly report (replaced when a newer report arrives). */
    fun isReportFact(f: MailFact) = f.kind == MailKind.ZOHO || (f.kind == MailKind.HOLD && f.messageId.contains('#'))

    private fun toJob(g: Group, report: ReportInfo?): Job {
        val byQuality = g.facts.sortedWith(compareByDescending<MailFact> { rank(it.kind) }.thenByDescending { it.sentAt })
        val fields = LinkedHashMap<String, String>()
        for (fact in byQuality) for ((k, v) in fact.fields) if (v.isNotBlank()) fields.putIfAbsent(k, v)
        // Times travel together: don't mix one email's start with another's finish.
        byQuality.firstOrNull { it.fields[F.START] != null }?.let { best ->
            fields[F.START] = best.fields[F.START]!!
            fields[F.END] = best.fields[F.END]!!
            best.fields[F.TIME_TEXT]?.let { fields[F.TIME_TEXT] = it }
        }
        val confirming = g.facts.filter { it.kind in CONFIRMING }
        val cancel = g.facts.filter { it.kind == MailKind.CANCELLED }.maxByOrNull { it.sentAt }
        val lastConfirm = confirming.maxOfOrNull { it.sentAt } ?: 0L
        val status = when {
            cancel != null && cancel.sentAt > lastConfirm -> JobStatus.CANCELLED
            confirming.isNotEmpty() -> JobStatus.CONFIRMED
            else -> JobStatus.PENDING
        }
        val newest = g.facts.sortedByDescending { it.sentAt }
        val notInReport = report?.generated != null && status == JobStatus.CONFIRMED &&
            g.date.isAfter(report.generated) &&
            confirming.filter { it.kind != MailKind.ZOHO }.maxOfOrNull { it.sentAt }.let { it != null && it < report.sentAt } &&
            confirming.none { it.kind == MailKind.ZOHO && it.messageId.startsWith(report.messageId + "#") }
        return Job(
            eventId = eventId(g.date, g.firstToken),
            date = g.date,
            status = status,
            fields = fields,
            spotify = newest.flatMap { it.spotify }.distinctBy { it.substringBefore('?').lowercase() },
            links = newest.flatMap { it.links }.distinctBy { it.substringBefore('?').lowercase() },
            attachments = newest.flatMap { it.attachments }.distinctBy { it.fileName.lowercase() to it.size },
            sources = emailSources(newest),
            notInReport = notInReport,
        )
    }

    /** A Zoho report row as a fact. */
    fun zohoFact(reportId: String, sentAt: Long, index: Int, r: ZohoRow): MailFact {
        val f = linkedMapOf(F.NAME to r.name, F.TYPE to r.type, F.VENUE to r.venue, F.SUBURB to r.suburb)
        val s = JobParser.oneTime(r.start)
        var e = JobParser.oneTime(r.end)
        if (s != null && e != null && (e - s).mod(1440) > 14 * 60) e = (e + 720) % 1440 // "7pm - 12:45pm" typo
        if (s != null && e != null) {
            f[F.START] = JobParser.hm(s)!!
            f[F.END] = JobParser.hm(e)!!
            f[F.TIME_TEXT] = "${r.start} - ${r.end}"
        }
        if (r.producer.isNotBlank()) f[F.MEC_CONTACT] = r.producer
        return MailFact("$reportId#$index", sentAt, MailKind.ZOHO, "Monthly bookings report", r.date,
            f.filterValues { it.isNotBlank() })
    }
}

const val MEL_TZ = "Australia/Melbourne"

/** Turns a [Job] into a Google Calendar event (with structured copies of the details for the app). */
object JobEvent {
    private val TIME_FMT = DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH)
    val LONG_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)

    fun clock(hhmm: String?): String? = hhmm?.let {
        runCatching { LocalTime.parse(it).format(TIME_FMT).lowercase().replace(":00", "") }.getOrNull()
    }

    fun title(job: Job): String {
        val name = job[F.NAME] ?: job[F.CLIENT] ?: "MEC job"
        val type = job[F.TYPE]?.takeIf { t -> !name.contains(t, true) }?.let { " ($it)" }.orEmpty()
        return when (job.status) {
            JobStatus.CANCELLED -> "CANCELLED · $name$type"
            JobStatus.HOLD -> "⏸ ON HOLD · MEC" + (job[F.INVOICED]?.let { " (invoiced: $it)" } ?: "")
            else -> "🎧 $name$type"
        }
    }

    fun location(job: Job): String {
        val parts = mutableListOf<String>()
        for (p in listOfNotNull(job[F.VENUE], job[F.ADDRESS], job[F.SUBURB])) {
            val t = p.trim().trimEnd(',')
            if (t.isNotBlank() && parts.none { it.contains(t, true) }) parts += t
        }
        return parts.joinToString(", ")
    }

    fun mapsUrl(location: String): String? = location.takeIf { it.isNotBlank() }?.let {
        val q = if (Regex("""\b(VIC|NSW|QLD|SA|WA|TAS|ACT|NT)\b""").containsMatchIn(it)) it else "$it VIC"
        "https://www.google.com/maps/search/?api=1&query=" + URLEncoder.encode(q, "UTF-8")
    }

    fun telUri(phone: String): String? {
        val digits = phone.filter { it.isDigit() || it == '+' }
        return if (digits.count(Char::isDigit) >= 6) "tel:$digits" else null
    }

    fun gmailUrl(account: String, messageId: String) =
        "https://mail.google.com/mail/?authuser=" + URLEncoder.encode(account, "UTF-8") + "#all/" + messageId

    /** Event start/end: from arrival (or start) to finish; finishing after midnight rolls to the next day. */
    fun span(job: Job): Pair<ZonedDateTime, ZonedDateTime>? {
        val zone = ZoneId.of(MEL_TZ)
        val start = job[F.START]?.let { LocalTime.parse(it) }
        val end = job[F.END]?.let { LocalTime.parse(it) }
        val arrive = job[F.ARRIVE]?.let { LocalTime.parse(it) }
        val from = when {
            arrive != null && (start == null || !arrive.isAfter(start)) -> arrive
            start != null -> start.minusHours(1)
            else -> return null
        }
        val s = ZonedDateTime.of(job.date, from, zone)
        val endTime = end ?: (start ?: from).plusHours(5)
        var e = ZonedDateTime.of(job.date, endTime, zone)
        if (!e.isAfter(s)) e = e.plusDays(1)
        return s to e
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun multiline(s: String) = esc(s).replace("\n", "<br>")

    private fun phoneHtml(label: String, name: String?, phone: String?): String? {
        if (phone.isNullOrBlank() && name.isNullOrBlank()) return null
        val tel = phone?.let { telUri(it) }
        val num = when {
            phone.isNullOrBlank() -> ""
            tel != null -> "<a href=\"$tel\">${esc(phone)}</a>"
            else -> esc(phone)
        }
        val who = name?.takeIf { it.isNotBlank() }?.let { esc(it) + if (num.isNotEmpty()) " · " else "" }.orEmpty()
        return "<b>$label</b> $who$num"
    }

    fun description(job: Job, account: String, files: List<Pair<String, String>>): String {
        val out = mutableListOf<String>()
        val arrive = clock(job[F.ARRIVE])
        val start = clock(job[F.START])
        val end = clock(job[F.END])
        if (job.status == JobStatus.HOLD) {
            out += "<b>⏸ MEC has you ON HOLD for this night.</b>"
            out += "Keep the night free with your gear and outfit ready: MEC may assign you a gig or call on the day."
            job[F.PAY]?.let { out += "<b>Hold pay</b> ${esc(it)} if you're not used (invoice it the Sunday after)." }
            job[F.INVOICED]?.let { n ->
                out += ""
                out += "<b>Likely gig</b> ${esc(n)}" + (job[F.INVOICED_VENUE]?.let { " at ${esc(it)}" } ?: "") +
                    " has been invoiced and is waiting on the client's payment."
            }
        }
        if (job.status == JobStatus.CANCELLED) {
            out += "<b>❌ This booking was cancelled.</b>" + (job[F.CANCEL_PAY]?.let { " 50% cancellation pay: ${esc(it)}" } ?: "")
        }
        if (job.notInReport) out += "<b>⚠️ Not in MEC's latest monthly bookings report. Check with MEC.</b>"
        if (arrive != null) out += "<b>Arrive</b> $arrive (set-up / bump-in)"
        if (start != null) out += "<b>Performance</b> $start – ${end ?: "?"}"
        else job[F.TIME_TEXT]?.let { out += "<b>Time</b> ${esc(it)}" }
        out += ""
        val loc = location(job)
        if (loc.isNotBlank()) {
            out += "<b>Venue</b> ${esc(loc)}"
            mapsUrl(loc)?.let { out += "<a href=\"$it\">📍 Open in Google Maps</a>" }
        }
        job[F.PARKING]?.let { out += "<b>Parking &amp; bump-in</b> ${multiline(it)}" }
        job[F.VENUE_INFO]?.let { out += "<b>Venue info</b> ${multiline(it)}" }
        out += ""
        listOfNotNull(
            phoneHtml("Client", listOfNotNull(job[F.CLIENT], job[F.PARTNER]).joinToString(" & ").ifBlank { null }, job[F.CLIENT_PHONE]),
            job[F.SECOND_PHONE]?.let { phoneHtml("Second phone", null, it) },
            job[F.OTHER_PHONE]?.let { phoneHtml("Other phone", null, it) },
            phoneHtml("Venue", job[F.VENUE_CONTACT], job[F.VENUE_PHONE]),
            job[F.MEC_CONTACT]?.let { mec ->
                val phone = Regex("""[+\d][\d ()-]{7,}""").find(mec)?.value?.trim()
                phoneHtml("MEC", mec.substringBefore('|').trim().ifBlank { null }, phone)
            },
        ).forEach { out += it }
        out += ""
        listOfNotNull(
            job[F.TYPE]?.let { "<b>Type</b> ${esc(it)}" },
            job[F.GUESTS]?.let { "<b>Guests</b> ${esc(it)}" },
            job[F.DRESS]?.let { "<b>Dress</b> ${esc(it)}" },
        ).takeIf { it.isNotEmpty() }?.let { out += it.joinToString(" · ") }
        job[F.EXTRAS]?.let { out += "<b>Extras</b> ${multiline(it)}" }
        job[F.NOTES]?.let { out += "<b>Notes</b><br>${multiline(it.take(1500))}" }
        job[F.PAY]?.let { out += "<b>Pay</b> ${esc(it)}" + (job[F.PAY_INFO]?.let { i -> "<br>${multiline(i.take(400))}" } ?: "") }
        job[F.EQUIPMENT]?.let { out += "<b>Equipment</b> ${esc(it)}" }
        if (job.spotify.isNotEmpty()) {
            out += ""
            out += "<b>🎵 Spotify</b>"
            job.spotify.forEach { out += "<a href=\"${esc(it)}\">${esc(it.substringBefore('?'))}</a>" }
        }
        if (files.isNotEmpty() || job.links.isNotEmpty()) {
            out += ""
            out += "<b>📄 Files</b>"
            files.forEach { (name, url) -> out += "<a href=\"${esc(url)}\">${esc(name)}</a>" }
            job.links.forEach { out += "<a href=\"${esc(it)}\">${esc(it.substringBefore('?'))}</a>" }
        }
        val emails = job.sources.filter { it.first in setOf(MailKind.JOBSHEET, MailKind.CONFIRMED, MailKind.CANCELLED) }
            .distinctBy { it.first }
        if (emails.isNotEmpty() && account.isNotBlank()) {
            out += ""
            out += emails.joinToString(" · ") { (k, id) ->
                val label = when (k) {
                    MailKind.JOBSHEET -> "Job sheet email"
                    MailKind.CONFIRMED -> "Confirmation email"
                    else -> "Cancellation email"
                }
                "<a href=\"${gmailUrl(account, id)}\">$label</a>"
            }
        }
        out += ""
        out += "<i>Added by Invoice Helper from MEC emails.</i>"
        return out.joinToString("<br>").replace(Regex("(<br>){3,}"), "<br><br>").removePrefix("<br>")
    }

    /** Private extended properties the app reads back to show the job (each value ≤ 1024 chars). */
    fun props(job: Job, account: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["ih"] = "1"
        m["status"] = job.status.name
        for ((k, v) in job.fields) m[k] = v.take(1000)
        if (job.spotify.isNotEmpty()) m["spotify"] = job.spotify.joinToString("\n").take(1000)
        if (job.links.isNotEmpty()) m["links"] = job.links.joinToString("\n").take(1000)
        if (job.notInReport) m["notInReport"] = "1"
        mapsUrl(location(job))?.let { m["maps"] = it }
        location(job).takeIf { it.isNotBlank() }?.let { m["location"] = it }
        job.sources.firstOrNull { it.first == MailKind.JOBSHEET }?.let { m["jobSheetUrl"] = gmailUrl(account, it.second) }
        if (job.sources.isNotEmpty()) m["emails"] = job.sources.take(35).joinToString("\n") { "${it.first.name}:${it.second}" }
        return m
    }

    /** files: (title, Drive webViewLink, Drive file id, mime type). */
    fun toJson(job: Job, account: String, files: List<DriveFileInfo>): JSONObject {
        val ev = JSONObject()
            .put("id", job.eventId)
            .put("summary", title(job))
            .put("description", description(job, account, files.map { it.name to it.link }))
            .put("location", location(job))
            .put("status", "confirmed")
            .put("transparency", if (job.status == JobStatus.CANCELLED) "transparent" else "opaque")

            .put("extendedProperties", JSONObject().put("private", JSONObject(props(job, account))))
            .put("attachments", JSONArray(files.take(25).map {
                JSONObject().put("fileUrl", it.link).put("fileId", it.id).put("title", it.name).put("mimeType", it.mimeType)
            }))
        if (job.status == JobStatus.HOLD) ev.put("colorId", "5") // yellow, so holds stand out from gigs
        val span = if (job.status == JobStatus.HOLD) null else span(job)
        if (span == null) {
            ev.put("start", JSONObject().put("date", job.date.toString()))
            ev.put("end", JSONObject().put("date", job.date.plusDays(1).toString()))
        } else {
            val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
            ev.put("start", JSONObject().put("dateTime", span.first.format(fmt)).put("timeZone", MEL_TZ))
            ev.put("end", JSONObject().put("dateTime", span.second.format(fmt)).put("timeZone", MEL_TZ))
        }
        return ev
    }
}

data class DriveFileInfo(val name: String, val id: String, val link: String, val mimeType: String) {
    fun toJson(): JSONObject = JSONObject().put("n", name).put("i", id).put("l", link).put("t", mimeType)

    companion object {
        fun fromJson(o: JSONObject) = DriveFileInfo(o.optString("n"), o.optString("i"), o.optString("l"), o.optString("t"))
    }
}
