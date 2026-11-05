package com.tententen.invoicehelper

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Developer check against a local dump of real MEC emails (never committed). Skipped unless IH_MAIL_DIR
 * points at folders of meta.json + body.html/body.txt + att_* files. Prints the jobs it would create.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealMailCheck {
    @Test
    fun parseRealMail() {
        val dirs = System.getenv("IH_MAIL_DIR")?.split(';')?.map(::File)?.filter { it.isDirectory }.orEmpty()
        assumeTrue(dirs.isNotEmpty())
        PdfText.init(RuntimeEnvironment.getApplication())
        val facts = mutableListOf<MailFact>()
        var report: ReportInfo? = null
        val kinds = mutableMapOf<MailKind, Int>()
        for (dir in dirs) for (d in dir.listFiles()!!.filter { File(it, "meta.json").exists() }) {
            val m = JSONObject(File(d, "meta.json").readText())
            val subject = m.optString("subject")
            val from = m.optString("from_")
            val sentAt = runCatching {
                ZonedDateTime.parse(m.optString("date").replace(Regex("\\s*\\(.*\\)$"), "").trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli()
            }.getOrDefault(0L)
            val kind = JobParser.kind(subject, from)
            kinds[kind] = (kinds[kind] ?: 0) + 1
            val atts = d.listFiles()!!.filter { it.name.startsWith("att_") }
            if (kind == MailKind.ZOHO) {
                if (report != null && report.sentAt >= sentAt) continue
                val pdf = atts.firstOrNull { it.name.endsWith(".pdf") } ?: continue
                val (generated, rows) = ZohoReport.parse(PdfText.words(pdf.readBytes()))
                facts.removeAll { JobBook.isReportFact(it) }
                facts += JobBook.reportFacts(d.name, sentAt, rows)
                report = ReportInfo(d.name, sentAt, generated)
                println("REPORT ${d.name} generated=$generated rows=${rows.size}: " + rows.joinToString { "${it.date} ${it.name} ${it.start}-${it.end}" })
                continue
            }
            val html = File(d, "body.html").takeIf { it.exists() }?.readText()
            val text = File(d, "body.txt").takeIf { it.exists() }?.readText()
            val refs = atts.map { AttachmentRef(d.name, it.name, it.name.removePrefix("att_"), "", it.length().toInt()) }
            var fact = JobParser.parse(d.name, sentAt, subject, from, html, text, refs) ?: continue
            var extra = ""
            for (a in atts) {
                when {
                    a.name.endsWith(".docx", true) -> extra += JobParser.docxText(a.readBytes())
                    a.name.endsWith(".pdf", true) && a.length() < 15_000_000 -> extra += PdfText.textAndLinks(a.readBytes())
                }
            }
            fact = fact.copy(spotify = (fact.spotify + JobParser.spotifyLinks(extra)).distinct())
            facts += fact
        }
        println("KINDS $kinds")
        val jobs = JobBook.build(facts, report)
        val from = LocalDate.of(2026, 10, 1)
        for (j in jobs.filter { !it.date.isBefore(from) }) {
            println(
                "JOB ${j.date} ${j.status} ${j.eventId} | ${j[F.NAME]} | arrive=${j[F.ARRIVE]} ${j[F.START]}-${j[F.END]} | " +
                    "${JobEvent.location(j)} | client=${j[F.CLIENT_PHONE]} venue=${j[F.VENUE_PHONE]} | pay=${j[F.PAY]} | " +
                    "spotify=${j.spotify.size} files=${j.attachments.map { it.fileName }} | notInReport=${j.notInReport} | " +
                    "sources=${j.sources.map { it.first }}"
            )
        }
        val missingTimes = jobs.filter { it.status == JobStatus.CONFIRMED && it[F.START] == null }
        println("CONFIRMED WITHOUT TIMES: " + missingTimes.map { "${it.date} ${it[F.NAME]}" })
    }
}
