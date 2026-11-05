package com.tententen.invoicehelper

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.zip.ZipInputStream

/** What kind of Melbourne Entertainment Co. (MEC) email this is, judged from its subject and sender. */
enum class MailKind {
    /** "Job Sheet | …": the finalised details, sent the week before. */
    JOBSHEET,
    /** "Confirmed …": the booking is 100% confirmed. */
    CONFIRMED,
    /** "E-Introduction …": MEC introducing you to the client (times + venue). */
    INTRO,
    /** A row of the monthly Zoho "All Upcoming Confirmed Bookings" PDF report. */
    ZOHO,
    /** "… Has Been INVOICED!": client invoiced, not yet confirmed. */
    INVOICED,
    /** "ON HOLD CONFIRMATION": a hold, not a gig. */
    HOLD,
    /** "BOOKING CANCELLATION …". */
    CANCELLED,
    /** Song lists, first dance, music forms, replies to job sheets, venue confirmations: links/files only. */
    RELATED,
    /** Notices, pay, invoices, etc. */
    OTHER,
}

/** Keys for [MailFact.fields] and [Job.fields]. Times are "HH:mm" (24h). */
object F {
    const val NAME = "name"
    const val TYPE = "type"
    const val VENUE = "venue"
    const val ADDRESS = "address"
    const val SUBURB = "suburb"
    const val ARRIVE = "arrive"
    const val START = "start"
    const val END = "end"
    const val TIME_TEXT = "timeText"
    const val CLIENT = "client"
    const val PARTNER = "partner"
    const val CLIENT_PHONE = "clientPhone"
    const val SECOND_PHONE = "secondPhone"
    const val OTHER_PHONE = "otherPhone"
    const val VENUE_PHONE = "venuePhone"
    const val VENUE_CONTACT = "venueContact"
    const val MEC_CONTACT = "mecContact"
    const val DRESS = "dress"
    const val GUESTS = "guests"
    const val PARKING = "parking"
    const val VENUE_INFO = "venueInfo"
    const val EXTRAS = "extras"
    const val NOTES = "notes"
    const val PAY = "pay"
    const val PAY_INFO = "payInfo"
    const val EQUIPMENT = "equipment"
    const val CANCEL_PAY = "cancelPay"
    /** On a hold: the invoiced (not yet paid) booking for that night, if any. */
    const val INVOICED = "invoiced"
    const val INVOICED_VENUE = "invoicedVenue"
}

/** An email attachment we may copy to Drive and attach to the calendar event. */
data class AttachmentRef(
    val messageId: String,
    val attachmentId: String,
    val fileName: String,
    val mimeType: String,
    val size: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("m", messageId).put("a", attachmentId).put("n", fileName).put("t", mimeType).put("s", size)

    companion object {
        fun fromJson(o: JSONObject) = AttachmentRef(
            o.optString("m"), o.optString("a"), o.optString("n"), o.optString("t"), o.optInt("s"),
        )
    }
}

/** What one email tells us about one job. */
data class MailFact(
    val messageId: String,
    val sentAt: Long,
    val kind: MailKind,
    val subject: String,
    val date: LocalDate?,
    val fields: Map<String, String> = emptyMap(),
    val spotify: List<String> = emptyList(),
    val links: List<String> = emptyList(),
    val attachments: List<AttachmentRef> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", messageId).put("at", sentAt).put("k", kind.name).put("s", subject)
        .put("d", date?.toString() ?: "")
        .put("f", JSONObject(fields))
        .put("sp", JSONArray(spotify)).put("ln", JSONArray(links))
        .put("att", JSONArray(attachments.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): MailFact {
            val f = o.optJSONObject("f") ?: JSONObject()
            val att = o.optJSONArray("att") ?: JSONArray()
            return MailFact(
                messageId = o.optString("id"),
                sentAt = o.optLong("at"),
                kind = runCatching { MailKind.valueOf(o.optString("k")) }.getOrDefault(MailKind.OTHER),
                subject = o.optString("s"),
                date = o.optString("d").takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
                fields = f.keys().asSequence().associateWith { f.getString(it) },
                spotify = (o.optJSONArray("sp") ?: JSONArray()).strings(),
                links = (o.optJSONArray("ln") ?: JSONArray()).strings(),
                attachments = (0 until att.length()).map { AttachmentRef.fromJson(att.getJSONObject(it)) },
            )
        }
    }
}

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

/** Turns MEC emails into [MailFact]s. Pure functions, so they're unit-tested on the JVM. */
object JobParser {
    private val PREFIX = Regex("""^\s*((re|fwd?|fw)\s*:\s*|updated\s*\|\s*)+""", RegexOption.IGNORE_CASE)
    private val REPLY = Regex("""^\s*(re|fwd?|fw)\s*:""", RegexOption.IGNORE_CASE)
    private val RELATED_RE = Regex(
        "song list|first dance|music form|playlist|run ?sheet|running sheet|song request|booking confirmation",
        RegexOption.IGNORE_CASE,
    )

    fun cleanSubject(subject: String) = subject.replace(PREFIX, "").trim()

    fun kind(subject: String, from: String = ""): MailKind {
        if (from.contains("zohocrm", true) && subject.contains("Upcoming Confirmed Bookings", true)) {
            return MailKind.ZOHO
        }
        val s = cleanSubject(subject).lowercase()
        return when {
            s.startsWith("booking cancel") || s.contains("cancelled -") -> MailKind.CANCELLED
            s.startsWith("job sheet") -> if (REPLY.containsMatchIn(subject)) MailKind.RELATED else MailKind.JOBSHEET
            s.startsWith("confirmed ") -> MailKind.CONFIRMED
            s.contains("has been invoiced") -> MailKind.INVOICED
            s.contains("on hold") -> MailKind.HOLD
            s.contains("e-introduction") -> MailKind.INTRO
            RELATED_RE.containsMatchIn(s) -> MailKind.RELATED
            else -> MailKind.OTHER
        }
    }

    /** Kinds worth reading in full (the rest are skipped after looking at the subject). */
    fun isUseful(k: MailKind) = k != MailKind.OTHER

    // ------------------------------------------------------------------ text helpers

    /** HTML email -> one line per table cell / paragraph. */
    fun cells(html: String): List<String> {
        var t = html.replace(Regex("""(?is)<(style|script|head)\b.*?</\1>"""), "")
        t = t.replace(Regex("""(?i)<br\s*/?>"""), "\n")
        t = t.replace(Regex("""(?i)</(td|th|p|div|h\d|li|tr|table)>"""), "\n")
        t = t.replace(Regex("<[^>]+>"), "")
        t = unescape(t).replace(' ', ' ')
        return t.split('\n').map { it.replace(Regex("[ \\t\\r]+"), " ").trim() }.filter { it.isNotEmpty() }
    }

    fun unescape(s: String): String = s
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** "07/11/2026", "19.09.26", "Oct 3, 2026", "27th March 2026" -> date. */
    fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        Regex("""\b(\d{1,2})[/.](\d{1,2})[/.](\d{4}|\d{2})\b""").find(s)?.let { m ->
            val (d, mo, y) = m.destructured
            val year = y.toInt().let { if (it < 100) it + 2000 else it }
            return runCatching { LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull()
        }
        Regex("""\b([A-Za-z]{3})[a-z]*\.? (\d{1,2})(?:st|nd|rd|th)?,? (\d{4})""").find(s)?.let { m ->
            val (mon, d, y) = m.destructured
            val mi = MONTHS.indexOf(mon.lowercase())
            if (mi >= 0) return runCatching { LocalDate.of(y.toInt(), mi + 1, d.toInt()) }.getOrNull()
        }
        Regex("""\b(\d{1,2})(?:st|nd|rd|th)? ([A-Za-z]{3})[a-z]*,? (\d{4})""").find(s)?.let { m ->
            val (d, mon, y) = m.destructured
            val mi = MONTHS.indexOf(mon.lowercase())
            if (mi >= 0) return runCatching { LocalDate.of(y.toInt(), mi + 1, d.toInt()) }.getOrNull()
        }
        return null
    }

    private const val T = """(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?"""
    private val RANGE = Regex("""\b$T\s*(?:-|–|—|to|till|until)\s*$T(?!\d)""", RegexOption.IGNORE_CASE)
    private val ONE = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)\b""", RegexOption.IGNORE_CASE)

    private fun mins(h: String, m: String?, ap: String?): Int? {
        var hh = h.toIntOrNull() ?: return null
        val mm = m?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        if (hh > 23 || mm > 59) return null
        if (!ap.isNullOrEmpty()) hh = hh % 12 + if (ap[0].lowercaseChar() == 'p') 12 else 0
        return hh * 60 + mm
    }

    /** First "5pm" / "4:30 pm" in [s], as minutes since midnight. */
    fun oneTime(s: String?): Int? = s?.let { ONE.find(it) }?.let { m ->
        mins(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    /** "6-11pm", "5:30-9:30pm", "12-7pm", "6:00 PM -11:00 PM", "7pm - 12am", "6pm - 12" -> (start, end) minutes. */
    fun times(s: String?): Pair<Int, Int>? {
        val m = s?.let { RANGE.find(it) } ?: return null
        val g = m.groupValues
        val (h1, m1, a1, h2, m2) = listOf(g[1], g[2], g[3], g[4], g[5])
        var a2 = g[6]
        if (a1.isNotEmpty() && a2.isEmpty()) {
            // '6pm - 12': the end is the first matching clock time after the start.
            val start = mins(h1, m1, a1) ?: return null
            val end = listOfNotNull(mins(h2, m2, "am"), mins(h2, m2, "pm"))
                .minByOrNull { (it - start).mod(1440).let { d -> if (d == 0) 9999 else d } } ?: return null
            return start to end
        }
        if (a1.isEmpty() && a2.isEmpty()) a2 = "pm"
        val end = mins(h2, m2, a2) ?: return null
        val start = if (a1.isNotEmpty()) {
            mins(h1, m1, a1)
        } else {
            // Pick am/pm for the start so the gig runs forward and is as short as possible.
            listOfNotNull(mins(h1, m1, "am"), mins(h1, m1, "pm"))
                .minByOrNull { (end - it).mod(1440).let { d -> if (d == 0) 9999 else d } }
        } ?: return null
        return start to end
    }

    fun hm(minutes: Int?): String? = minutes?.let { "%02d:%02d".format(it / 60, it % 60) }

    private val SPOTIFY = Regex("""https?://(?:open\.|play\.)?spotify\.(?:com|link)/[^\s"'<>()\[\]]+""", RegexOption.IGNORE_CASE)
    private val DOCS = Regex("""https?://(?:docs|drive)\.google\.com/[^\s"'<>()\[\]]+""", RegexOption.IGNORE_CASE)

    private fun findLinks(text: String, re: Regex): List<String> {
        val seen = LinkedHashMap<String, String>()
        for (m in re.findAll(unescape(text))) {
            val url = m.value.trimEnd('.', ',', ';', ':', '!', '*')
            seen.putIfAbsent(url.substringBefore('?').lowercase(), url)
        }
        return seen.values.toList()
    }

    fun spotifyLinks(text: String): List<String> = findLinks(text, SPOTIFY)
    fun docLinks(text: String): List<String> = findLinks(text, DOCS)

    /** Text plus hyperlink targets of a .docx (MEC's song-request forms carry Spotify links). */
    fun docxText(bytes: ByteArray): String {
        val sb = StringBuilder()
        runCatching {
            ZipInputStream(bytes.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    when {
                        e.name == "word/document.xml" ->
                            sb.append(String(z.readBytes()).replace("</w:p>", "\n").replace(Regex("<[^>]+>"), "")).append('\n')
                        e.name.startsWith("word/_rels/") ->
                            Regex("""Target="(https?://[^"]+)"""").findAll(String(z.readBytes()))
                                .forEach { sb.append(it.groupValues[1]).append('\n') }
                    }
                }
            }
        }
        return unescape(sb.toString())
    }

    // ------------------------------------------------------------------ label/value tables

    /** Values that follow each label, up to the next label. Labels are matched as whole lines, ignoring case. */
    fun fields(lines: List<String>, labels: Collection<String>): Map<String, List<String>> {
        val set = labels.map { it.uppercase() }.toSet()
        val out = LinkedHashMap<String, MutableList<String>>()
        var cur: String? = null
        for (l in lines) {
            val u = l.uppercase()
            if (u in set) {
                cur = u
                out.getOrPut(u) { mutableListOf() }
            } else if (cur != null) {
                out[cur]!!.add(l)
            }
        }
        return out
    }

    private val JOBSHEET_LABELS = listOf(
        "DATE", "TYPE", "TOTAL EVENT TIME", "EVENT TIME", "PERFORMANCE TIME", "SETUP ARRIVAL", "EVENT INFO",
        "# GUESTS", "DRESS CODE", "EXTRAS NEEDED", "ANYTHING ELSE?", "CONTACT INFO", "CLIENT NAME",
        "2ND NAME/PARTNER", "CLIENT MOBILE", "SECOND PHONE", "OTHER PHONE", "VENUE PHONE", "VENUE CONTACT",
        "CALL THE CLIENT ON MONDAY PRIOR?", "MEC CONTACT", "SHOULD WE ASK FOR A REVIEW IF ALL GOES WELL?",
        "LOCATION INFO", "VENUE", "VENUE OR PRIVATE?", "VENUE ADDRESS", "PRIVATE ADDRESS", "VENUE SUBURB",
        "PRIVATE SUBURB", "PARKING", "& BUMP-IN", "VENUE INFO OTHER", "PARKING MAP", "ENTERTAINMENT INFO",
        "YOUR PERFORMANCE TIME", "SET-UP / ARRIVAL TIME", "TOTAL PAY", "YOUR PAY", "(INCL. SUPER WHERE APPLICABLE)",
        "PAY BREAKDOWN, OTHER INFO &", "EXACT REQUIRED TIMINGS", "EQUIPMENT REQUIRED",
        "OTHER ENTERTAINERS BOOKED WITH MEC:", "G I G C H E C K L I S T",
    )

    private val CONFIRMED_LABELS = listOf(
        "NAME", "EVENT TYPE", "DAY & DATE", "VENUE/SUBURB", "CURRENT TIME*", "CURRENT PERFORMANCE TIME*",
        "BUMP-IN TIME*", "CURRENT PAY**", "CURRENT TOTAL PAY**", "CURRENT TOTAL PAY**(INCL. SUPER WHERE APPLICABLE)",
        "(INCL. SUPER WHERE APPLICABLE)", "CURRENT INFO**", "BOOKED BY", "MEC MANAGER", "50% CANCELLATION PAY",
        "WHAT HAPPENS NOW",
    )

    private fun Map<String, List<String>>.one(vararg keys: String, sep: String = " "): String? =
        keys.firstNotNullOfOrNull { k -> this[k]?.takeIf { it.isNotEmpty() }?.joinToString(sep) }

    private fun money(s: String?): String? = s?.let { Regex("""\$\s*[\d,]+(?:\.\d{1,2})?""").find(it)?.value }

    private fun putTimes(out: MutableMap<String, String>, range: String?, arrive: String?) {
        times(range)?.let { (s, e) ->
            out[F.START] = hm(s)!!
            out[F.END] = hm(e)!!
        }
        if (!range.isNullOrBlank()) out[F.TIME_TEXT] = range
        oneTime(arrive)?.let { out[F.ARRIVE] = hm(it)!! }
    }

    /** Name part of "Job Sheet | <name…> | <type> | <date>" or "E-Introduction <name…> | <type> | <date>". */
    private fun subjectName(clean: String, prefix: Regex): String {
        val parts = clean.replace(prefix, "").split('|').map { it.trim() }.filter { it.isNotEmpty() }
        return when {
            parts.size >= 3 -> parts.dropLast(2).joinToString(" | ")
            else -> parts.firstOrNull().orEmpty()
        }
    }

    /**
     * Parses one email. [html] is preferred; [text] is used when there's no HTML part.
     * Returns null for emails that carry no job information.
     */
    fun parse(
        messageId: String,
        sentAt: Long,
        subject: String,
        from: String,
        html: String?,
        text: String?,
        attachments: List<AttachmentRef> = emptyList(),
    ): MailFact? {
        val kind = kind(subject, from)
        if (!isUseful(kind) || kind == MailKind.ZOHO) return null
        val clean = cleanSubject(subject)
        val lines = if (!html.isNullOrBlank()) cells(html) else text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val f = LinkedHashMap<String, String>()
        var date: LocalDate? = null

        when (kind) {
            MailKind.JOBSHEET -> {
                val v = fields(lines, JOBSHEET_LABELS)
                date = parseDate(v.one("DATE")) ?: parseDate(clean)
                f[F.NAME] = subjectName(clean, Regex("^job sheet", RegexOption.IGNORE_CASE))
                v.one("TYPE")?.let { f[F.TYPE] = it }
                val ent = v["SET-UP / ARRIVAL TIME"].orEmpty()
                val top = v["SETUP ARRIVAL"].orEmpty()
                val range = ent.firstOrNull { times(it) != null } ?: top.lastOrNull { times(it) != null }
                val arrive = ent.firstOrNull { it.startsWith("arrive", true) }
                    ?: top.lastOrNull { times(it) == null && oneTime(it) != null }
                putTimes(f, range, arrive)
                fun put(key: String, vararg labels: String, sep: String = " ") {
                    v.one(*labels, sep = sep)?.takeIf { s -> s.any { it.isLetterOrDigit() } }?.let { f[key] = it }
                }
                put(F.CLIENT, "CLIENT NAME")
                put(F.PARTNER, "2ND NAME/PARTNER")
                put(F.CLIENT_PHONE, "CLIENT MOBILE")
                put(F.SECOND_PHONE, "SECOND PHONE")
                put(F.OTHER_PHONE, "OTHER PHONE")
                put(F.VENUE_PHONE, "VENUE PHONE")
                put(F.VENUE_CONTACT, "VENUE CONTACT")
                put(F.MEC_CONTACT, "MEC CONTACT")
                put(F.VENUE, "VENUE")
                put(F.ADDRESS, "VENUE ADDRESS", "PRIVATE ADDRESS")
                put(F.SUBURB, "VENUE SUBURB", "PRIVATE SUBURB")
                put(F.PARKING, "& BUMP-IN", sep = "\n")
                put(F.VENUE_INFO, "VENUE INFO OTHER", sep = "\n")
                put(F.DRESS, "DRESS CODE")
                put(F.GUESTS, "# GUESTS")
                put(F.EXTRAS, "EXTRAS NEEDED", sep = "\n")
                put(F.NOTES, "ANYTHING ELSE?", sep = "\n")
                put(F.PAY_INFO, "EXACT REQUIRED TIMINGS", sep = "\n")
                put(F.EQUIPMENT, "EQUIPMENT REQUIRED")
                money(v.one("(INCL. SUPER WHERE APPLICABLE)", "YOUR PAY", "TOTAL PAY"))?.let { f[F.PAY] = it }
            }

            MailKind.CONFIRMED, MailKind.CANCELLED -> {
                val v = fields(lines, CONFIRMED_LABELS)
                date = parseDate(v.one("DAY & DATE")) ?: parseDate(clean)
                val m = Regex("""^(?:confirmed|booking cancellation for cancelled -)\s*(.*?),\s*([^,]*),\s*(?:\w+day|\d)""", RegexOption.IGNORE_CASE)
                    .find(clean)
                (v.one("NAME") ?: m?.groupValues?.get(1))?.let { f[F.NAME] = it }
                (v.one("EVENT TYPE") ?: m?.groupValues?.get(2))?.let { f[F.TYPE] = it }
                v.one("VENUE/SUBURB")?.let { f[F.VENUE] = it.replace(" / ", ", ") }
                putTimes(f, v.one("CURRENT PERFORMANCE TIME*", "CURRENT TIME*"), v.one("BUMP-IN TIME*"))
                money(
                    v.one(
                        "CURRENT TOTAL PAY**(INCL. SUPER WHERE APPLICABLE)", "(INCL. SUPER WHERE APPLICABLE)",
                        "CURRENT TOTAL PAY**", "CURRENT PAY**",
                    )
                )?.let { f[F.PAY] = it }
                v.one("CURRENT INFO**", sep = "\n")?.let { f[F.PAY_INFO] = it }
                v.one("MEC MANAGER")?.let { f[F.MEC_CONTACT] = it }
                money(v.one("50% CANCELLATION PAY"))?.let { f[F.CANCEL_PAY] = it }
            }

            MailKind.INTRO -> {
                date = parseDate(clean)
                f[F.NAME] = subjectName(clean, Regex("^(new\\s*-\\s*)?e-introduction", RegexOption.IGNORE_CASE))
                val v = fields(lines, listOf("DAY & DATE", "VENUE ADDRESS / SUBURB", "TALENT BOOKED", "SETUP/ BUMP-IN TIME"))
                v.one("VENUE ADDRESS / SUBURB", sep = ", ")?.let { f[F.VENUE] = it }
                val after = v["SETUP/ BUMP-IN TIME"].orEmpty()
                val range = after.firstOrNull { times(it) != null }
                val arrive = range?.let { r -> after.drop(after.indexOf(r) + 1).firstOrNull { oneTime(it) != null } }
                putTimes(f, range, arrive)
            }

            MailKind.INVOICED -> {
                date = parseDate(clean)
                val all = lines.joinToString("\n")
                Regex("""Booking Name:\s*(.+)""").find(all)?.let { f[F.NAME] = it.groupValues[1].trim() }
                Regex("""Venue:\s*(.+)""").find(all)?.let { f[F.VENUE] = it.groupValues[1].trim() }
                Regex("""Event Type:\s*(.+)""").find(all)?.let { f[F.TYPE] = it.groupValues[1].trim() }
            }

            MailKind.RELATED -> {
                date = parseDate(clean)
                f[F.NAME] = clean
            }

            MailKind.HOLD -> {
                // "ON HOLD CONFIRMATION | Friday, 27/11/2026" ... "ON HOLD DETAILS: Friday, 27/11/2026 $ 180.00"
                date = parseDate(clean) ?: return null
                f[F.NAME] = "ON HOLD"
                val all = lines.joinToString(" ")
                money(Regex("""ON HOLD DETAILS:?(.{0,80})""", RegexOption.IGNORE_CASE).find(all)?.groupValues?.get(1))
                    ?.let { f[F.PAY] = it }
            }

            else -> return null
        }

        val all = (html ?: "") + "\n" + (text ?: "")
        return MailFact(
            messageId = messageId,
            sentAt = sentAt,
            kind = kind,
            subject = clean,
            date = date,
            fields = f.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
            spotify = spotifyLinks(all),
            links = docLinks(all),
            attachments = attachments.filter { worthKeeping(it) },
        )
    }

    private val JUNK_NAME = Regex(
        """(?i)(logo|header|signature|banner|^image\d+|^outlook|^evienew|email header|qr code|\.gif$|\.ics$|\.mp4$)"""
    )
    private val KEEP_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "png", "jpg", "jpeg", "heic", "txt", "csv")

    /** Run sheets, song forms, maps; not logos, signatures or videos. */
    fun worthKeeping(a: AttachmentRef): Boolean {
        val ext = a.fileName.substringAfterLast('.', "").lowercase()
        if (ext !in KEEP_EXT || JUNK_NAME.containsMatchIn(a.fileName)) return false
        val image = ext in setOf("png", "jpg", "jpeg", "heic")
        return !(image && a.size in 1 until 40_000)
    }
}

// ---------------------------------------------------------------------- Zoho monthly report

/** A word on a PDF page; y grows downwards. */
data class PdfWord(val page: Int, val x: Float, val y: Float, val text: String)

data class ZohoRow(
    val date: LocalDate,
    val name: String,
    val type: String,
    val venue: String,
    val suburb: String,
    val start: String,
    val end: String,
    val producer: String,
) {
    val onHold get() = name.contains("ON HOLD", true) || type.contains("ON HOLD", true)
}

/**
 * Reads MEC's Zoho "All Upcoming Confirmed Bookings" report: a table whose columns are found from the
 * header row (Event Date, Week Day, Booking Name, Event Type, Venue, Event Suburb, Start Time, End Time,
 * Entertainment 1-3, Producer). Cells can wrap onto several lines; a row starts where Week Day has a day name.
 */
object ZohoReport {
    private val FIRST_WORDS = setOf("event", "week", "booking", "venue", "start", "end", "entertainment", "producer")
    private val DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val SECOND_WORDS = setOf("date", "day", "name", "type", "suburb", "time", "1", "2", "3")

    /** Narrow columns wrap mid-word ("Wedne"/"sday", "11:30p"/"m"): join their lines without spaces. */
    private val NO_SPACE = setOf("date", "day", "type", "start", "end")

    private fun isDayStart(s: String) = s.length >= 3 && DAYS.any { it.startsWith(s.lowercase()) }

    private fun key(label: String): String? {
        val l = label.lowercase()
        return when {
            l.startsWith("event date") -> "date"
            l.startsWith("week") -> "day"
            l.startsWith("booking") -> "name"
            l.startsWith("event type") -> "type"
            l == "venue" -> "venue"
            l.startsWith("event suburb") -> "suburb"
            l.startsWith("start") -> "start"
            l.startsWith("end") -> "end"
            l.startsWith("entertainment") -> "ent"
            l.startsWith("producer") -> "producer"
            else -> null
        }
    }

    /** Returns the report's "Generated … on dd/MM/yyyy" date and its rows (on-hold rows included). */
    fun parse(words: List<PdfWord>): Pair<LocalDate?, List<ZohoRow>> {
        val generated = lines(words).firstOrNull { l -> l.any { it.text.equals("Generated", true) } }
            ?.let { l -> JobParser.parseDate(l.joinToString(" ") { it.text }) }
        val rows = mutableListOf<ZohoRow>()
        var columns: List<Pair<Float, String?>>? = null
        var lastDate: LocalDate? = null
        for ((_, pageWords) in words.groupBy { it.page }.toSortedMap()) {
            val pageLines = lines(pageWords)
            val headerIdx = pageLines.indexOfFirst { l ->
                l.any { it.text.equals("Booking", true) } && l.any { it.text.equals("Venue", true) }
            }
            // Headers can wrap too ("Week" / "Day"): take a following line made only of header words.
            var bodyStart = headerIdx + 1
            if (headerIdx >= 0) {
                val second = pageLines.getOrNull(headerIdx + 1)
                    ?.takeIf { l -> l.all { it.text.lowercase() in SECOND_WORDS } }
                if (second != null) bodyStart++
                columns = header(pageLines[headerIdx], second.orEmpty())
            }
            val cols = columns ?: continue
            val body = if (headerIdx >= 0) pageLines.drop(bodyStart) else pageLines
            var cur: MutableMap<String, MutableList<String>>? = null
            fun flush() {
                val c = cur ?: return
                cur = null
                fun cell(k: String) = c[k].orEmpty().joinToString(if (k in NO_SPACE) "" else " ")
                    .replace(Regex("\\s+\\|"), " |").trim()
                val date = JobParser.parseDate(cell("date")) ?: lastDate ?: return
                lastDate = date
                rows += ZohoRow(
                    date = date,
                    name = cell("name").trimEnd('|', ' '),
                    type = cell("type"),
                    venue = cell("venue"),
                    suburb = cell("suburb"),
                    start = cell("start"),
                    end = cell("end"),
                    producer = cell("producer"),
                )
            }
            for (line in body) {
                val byCol = line.groupBy { w -> cols.last { it.first <= w.x + 2f }.second }
                val dateHere = JobParser.parseDate(byCol["date"].orEmpty().joinToString(" ") { it.text }) != null
                if (dateHere || byCol["day"].orEmpty().firstOrNull()?.let { isDayStart(it.text) } == true) {
                    flush()
                    cur = mutableMapOf()
                }
                val c = cur ?: continue
                for ((k, ws) in byCol) if (k != null) c.getOrPut(k) { mutableListOf() } += ws.joinToString(" ") { it.text }
            }
            flush()
        }
        return generated to rows
    }

    private fun header(line: List<PdfWord>, second: List<PdfWord>): List<Pair<Float, String?>> {
        val labels = mutableListOf<Pair<Float, StringBuilder>>()
        for (w in line.sortedBy { it.x }) {
            if (labels.isEmpty() || w.text.lowercase() in FIRST_WORDS) labels += w.x to StringBuilder(w.text)
            else labels.last().second.append(' ').append(w.text)
        }
        for (w in second) labels.minByOrNull { kotlin.math.abs(it.first - w.x) }?.second?.append(' ')?.append(w.text)
        // Anything left of the first column belongs to it.
        return labels.mapIndexed { i, (x, s) -> (if (i == 0) -1e6f else x) to key(s.toString()) }
    }

    private fun lines(words: List<PdfWord>): List<List<PdfWord>> {
        val out = mutableListOf<MutableList<PdfWord>>()
        for (w in words.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))) {
            val last = out.lastOrNull()
            if (last != null && last[0].page == w.page && kotlin.math.abs(last[0].y - w.y) < 2.5f) last += w
            else out += mutableListOf(w)
        }
        return out.map { l -> l.sortedBy { it.x } }
    }
}
