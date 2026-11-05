package com.tententen.invoicehelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** All names, numbers and venues here are made up; the layouts copy MEC's real emails. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JobParserTest {
    private fun table(vararg cells: String) =
        "<html><head><style>td{}</style></head><body><table><tr>" +
            cells.joinToString("") { "<td>${it.replace("&", "&amp;")}</td>" } + "</tr></table></body></html>"

    private val jobSheet2026 = table(
        "J O B - S H E E T", "Alex Sample | Sample Co Gala | Corporate | Saturday - 07/11/2026",
        "EVENT INFO", "DATE", "Saturday | 07/11/2026", "TYPE", "Corporate", "TOTAL EVENT TIME", "PERFORMANCE TIME",
        "SETUP ARRIVAL", "6.30pm to 11pm", "6.30pm - 11pm **UPDATED START TIME", "5pm",
        "EVENT INFO", "Alex Sample | Sample Co Gala", "# GUESTS", "80", "DRESS CODE", "Semi Formal", "EXTRAS NEEDED",
        "ANYTHING ELSE?", "Contact on the night: Jo Bloggs - 0400 000 111",
        "Playlist: https://open.spotify.com/playlist/AAA111?si=x.",
        "CONTACT INFO", "CLIENT NAME", "Alex Sample", "2ND NAME/PARTNER", "CLIENT MOBILE", "0400 000 222",
        "SECOND PHONE", "OTHER PHONE", "VENUE PHONE", "(03) 9000 0000", "VENUE CONTACT", "Sam Venue",
        "CALL THE CLIENT ON MONDAY PRIOR?", "YES", "MEC CONTACT", "Evie | 0400 000 333",
        "LOCATION INFO", "VENUE", "Example Hotel", "VENUE OR PRIVATE?", "VENUE ADDRESS", "1 Test St",
        "PRIVATE ADDRESS", "VENUE SUBURB", "Testville", "PRIVATE SUBURB", "PARKING", "& BUMP-IN",
        "Loading dock at rear", "VENUE INFO OTHER", ". |", "PARKING MAP",
        "ENTERTAINMENT INFO", "YOUR PERFORMANCE TIME", "SET-UP / ARRIVAL TIME",
        "6.30pm - 11pm **UPDATED START TIME", "Arrive: 5pm", "TOTAL PAY", "(incl. super where applicable)",
        "$ 432.50", "*This booking was confirmed on 12/10/2026", "PAY BREAKDOWN, OTHER INFO &",
        "EXACT REQUIRED TIMINGS", "DJ", "4.5 hours x $85", "EQUIPMENT REQUIRED", "DJ-CONSOLE; 2SP & STANDS",
        "OTHER ENTERTAINERS BOOKED WITH MEC:", "| |",
    )

    private val jobSheet2025 = table(
        "J O B - S H E E T", "Pat Example | Wedding | Friday - 08/08/2025", "EVENT INFO", "DATE", "Friday | 08/08/2025",
        "TYPE", "Wedding", "EVENT TIME", "PERFORMANCE TIME", "SETUP ARRIVAL", "5:30pm to 11:00pm", "5:30pm - 11:00pm",
        "4:30pm", "CLIENT NAME", "Pat Example", "2ND NAME/PARTNER", "Chris", "CLIENT MOBILE", "0400000444",
        "VENUE", "Example Estate", "VENUE ADDRESS", "2 Sample Rd", "VENUE SUBURB", "Sampleton",
        "ENTERTAINMENT INFO", "YOUR PAY", "$ 407.50", "*This booking was confirmed on 09/05/2025",
    )

    @Test
    fun classifiesSubjects() {
        val k = JobParser::kind
        assertEquals(MailKind.JOBSHEET, k("Job Sheet | A B | Wedding | 08/08/2026", ""))
        assertEquals(MailKind.JOBSHEET, k("UPDATED | Job Sheet | Party | A B | Party | 21/03/2026", ""))
        assertEquals(MailKind.RELATED, k("Re: Job Sheet | A B | Corporate | 05/08/2026", ""))
        assertEquals(MailKind.CONFIRMED, k("Confirmed A B | A + C, Wedding, Saturday, 30/01/2027", ""))
        assertEquals(MailKind.INVOICED, k("Friday, Nov 20, 2026 Has Been INVOICED!", ""))
        assertEquals(MailKind.HOLD, k("ON HOLD CONFIRMATION | Friday, 27/11/2026", ""))
        assertEquals(MailKind.INTRO, k("New - E-Introduction A B | Birthday | Saturday, 19/09/2026", ""))
        assertEquals(MailKind.CANCELLED, k("BOOKING CANCELLATION FOR CANCELLED - A B, Wedding, 12/04/2025", ""))
        assertEquals(MailKind.RELATED, k("SONG LIST FOR SATURDAY - A B | Job 1", ""))
        assertEquals(MailKind.RELATED, k("Fwd: MEC Booking Confirmation | Gala | A B | Corporate | Friday, 20/03/2026", ""))
        assertEquals(MailKind.OTHER, k("Payment Preview — Event Date(s): 03.10.26 | INV-73 (please review)", ""))
        assertEquals(MailKind.OTHER, k("Check your Monthly Confirmed Bookings", ""))
        assertEquals(
            MailKind.ZOHO,
            k("Zoho CRM - Report Scheduler : X All Upcoming Confirmed Bookings with MEC", "systemgenerated@zohocrm.com"),
        )
    }

    @Test
    fun parsesDatesAndTimes() {
        assertEquals(LocalDate.of(2026, 11, 7), JobParser.parseDate("Saturday | 07/11/2026"))
        assertEquals(LocalDate.of(2026, 9, 19), JobParser.parseDate("Event Date(s): 19.09.26"))
        assertEquals(LocalDate.of(2026, 10, 3), JobParser.parseDate("Job Sheet | X | Wedding | Oct 3, 2026"))
        assertEquals(LocalDate.of(2026, 11, 20), JobParser.parseDate("Friday, Nov 20, 2026 Has Been INVOICED!"))
        assertEquals(LocalDate.of(2026, 3, 27), JobParser.parseDate("27th March 2026"))
        assertNull(JobParser.parseDate("no date here"))

        fun t(s: String) = JobParser.times(s)?.let { (a, b) -> JobParser.hm(a) + "-" + JobParser.hm(b) }
        assertEquals("18:00-23:00", t("6-11pm"))
        assertEquals("17:30-21:30", t("5:30-9:30pm"))
        assertEquals("12:00-19:00", t("12-7pm"))
        assertEquals("18:00-23:00", t("6:00 PM -11:00 PM"))
        assertEquals("19:00-00:00", t("7pm - 12am"))
        assertEquals("18:00-00:00", t("6pm - 12"))
        assertEquals("18:30-23:00", t("6.30pm - 11pm **UPDATED START TIME"))
        assertEquals("22:00-02:00", t("10pm-2am"))
        assertNull(t("5hrs tbc"))
        assertEquals(17 * 60 + 20, JobParser.oneTime("Arrive: 5:20pm"))
    }

    @Test
    fun readsJobSheet() {
        val f = JobParser.parse("m1", 1, "Job Sheet | Alex Sample | Sample Co Gala | Corporate | 07/11/2026", "x@melbentco.com.au", jobSheet2026, null)!!
        assertEquals(MailKind.JOBSHEET, f.kind)
        assertEquals(LocalDate.of(2026, 11, 7), f.date)
        val v = f.fields
        assertEquals("Alex Sample | Sample Co Gala", v[F.NAME])
        assertEquals("17:00", v[F.ARRIVE])
        assertEquals("18:30", v[F.START])
        assertEquals("23:00", v[F.END])
        assertEquals("0400 000 222", v[F.CLIENT_PHONE])
        assertEquals("(03) 9000 0000", v[F.VENUE_PHONE])
        assertEquals("Sam Venue", v[F.VENUE_CONTACT])
        assertEquals("Example Hotel", v[F.VENUE])
        assertEquals("1 Test St", v[F.ADDRESS])
        assertEquals("Testville", v[F.SUBURB])
        assertEquals("Loading dock at rear", v[F.PARKING])
        assertNull(v[F.VENUE_INFO]) // ". |" placeholder dropped
        assertEquals("Evie | 0400 000 333", v[F.MEC_CONTACT])
        assertEquals("$ 432.50", v[F.PAY])
        assertEquals("80", v[F.GUESTS])
        assertTrue(v[F.NOTES]!!.contains("Jo Bloggs"))
        assertEquals(listOf("https://open.spotify.com/playlist/AAA111?si=x"), f.spotify)
    }

    @Test
    fun readsOlderJobSheetLayout() {
        val v = JobParser.parse("m2", 1, "Job Sheet | Pat Example | Wedding | 08/08/2025", "", jobSheet2025, null)!!.fields
        assertEquals("16:30", v[F.ARRIVE])
        assertEquals("17:30", v[F.START])
        assertEquals("23:00", v[F.END])
        assertEquals("Chris", v[F.PARTNER])
        assertEquals("$ 407.50", v[F.PAY])
    }

    @Test
    fun readsConfirmationsOldAndNew() {
        val new = table(
            "BOOKING CONFIRMATION", "Saturday | 30/01/2027", "CURRENT DETAILS", "Name", "Jordan Test | Jordan + Kim",
            "Event Type", "Wedding", "Day & Date", "Saturday | 30/01/2027", "Venue/Suburb", "Test Hall (Northside) Northside",
            "Current Performance Time*", "7pm - 12am", "Bump-In Time*", "6pm",
            "Current Total Pay**(incl. super where applicable)", "$ 475.00", "Current Info**", "DJ | 5 hours",
            "Booked By", "Aimee", "MEC Manager", "Leanne",
        )
        val a = JobParser.parse("c1", 1, "Confirmed Jordan Test | Jordan + Kim, Wedding, Saturday, 30/01/2027", "", new, null)!!
        assertEquals(MailKind.CONFIRMED, a.kind)
        assertEquals("Jordan Test | Jordan + Kim", a.fields[F.NAME])
        assertEquals("Test Hall (Northside) Northside", a.fields[F.VENUE])
        assertEquals("18:00", a.fields[F.ARRIVE])
        assertEquals("19:00", a.fields[F.START])
        assertEquals("00:00", a.fields[F.END])
        assertEquals("$ 475.00", a.fields[F.PAY])

        val old = table(
            "BOOKING CONFIRMATION", "Name", "Robin Demo", "Event Type", "Wedding", "Day & Date", "Saturday | 13/09/2025",
            "Venue/Suburb", "Demo Lodge / Hillside", "Current Time*", "6pm - 10pm", "Bump-In Time*", "5pm",
            "Current Pay**", "$ 390.00", "Current Info**", "4hrs", "MEC Manager", "Mary",
        )
        val b = JobParser.parse("c2", 1, "Confirmed Robin Demo, Wedding, Saturday, 13/09/2025", "", old, null)!!
        assertEquals(LocalDate.of(2025, 9, 13), b.date)
        assertEquals("Demo Lodge, Hillside", b.fields[F.VENUE])
        assertEquals("17:00", b.fields[F.ARRIVE])
        assertEquals("18:00", b.fields[F.START])
        assertEquals("$ 390.00", b.fields[F.PAY])
    }

    @Test
    fun readsCancellationAndInvoiced() {
        val c = table(
            "BOOKING CANCELLATION", "THIS EVENT IS NO LONGER GOING AHEAD", "Name", "Robin Demo", "Event Type", "Wedding",
            "Day & Date", "Saturday | 12/04/2025", "Venue/Suburb", "Demo Ballroom", "50% Cancellation Pay", "$ 237.50",
            "What Happens Now",
        )
        val f = JobParser.parse("x1", 1, "BOOKING CANCELLATION FOR CANCELLED - Robin Demo, Wedding, 12/04/2025", "", c, null)!!
        assertEquals(MailKind.CANCELLED, f.kind)
        assertEquals(LocalDate.of(2025, 4, 12), f.date)
        assertEquals("$ 237.50", f.fields[F.CANCEL_PAY])

        val inv = "<p>Great news!!</p><p>A few details we currently have:<br>Booking Name: Sam Corp | Corp Co<br>" +
            "Date: Friday, 27/11/2026<br>Venue: Demo Haus Melbourne<br>Event Type: Christmas Party</p>"
        val i = JobParser.parse("i1", 1, "Friday, 27/11/2026 Has Been INVOICED!", "", inv, null)!!
        assertEquals(MailKind.INVOICED, i.kind)
        assertEquals("Sam Corp | Corp Co", i.fields[F.NAME])
        assertEquals("Demo Haus Melbourne", i.fields[F.VENUE])
    }

    @Test
    fun dropsJunkAttachments() {
        fun a(n: String, size: Int) = AttachmentRef("m", "a", n, "", size)
        assertTrue(JobParser.worthKeeping(a("Run Sheet.pdf", 300_000)))
        assertTrue(JobParser.worthKeeping(a("DJ Requests - Event Form.docx", 50_000)))
        assertTrue(JobParser.worthKeeping(a("running schedule 1.png", 200_000)))
        assertFalse(JobParser.worthKeeping(a("MEC Email Header.jpeg", 300_000)))
        assertFalse(JobParser.worthKeeping(a("image002.jpg", 300_000)))
        assertFalse(JobParser.worthKeeping(a("tiny.png", 3_000)))
        assertFalse(JobParser.worthKeeping(a("clip.mp4", 3_000_000)))
    }

    @Test
    fun findsLinksInWordFiles() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write("<w:document><w:p><w:t>Our playlist</w:t></w:p><w:p><w:t>https://open.spotify.com/playlist/BBB222?si=1&amp;pi=2</w:t></w:p></w:document>".toByteArray())
            z.putNextEntry(ZipEntry("word/_rels/document.xml.rels"))
            z.write("<Relationships><Relationship Target=\"https://open.spotify.com/playlist/CCC333\"/></Relationships>".toByteArray())
        }
        val links = JobParser.spotifyLinks(JobParser.docxText(out.toByteArray()))
        assertEquals(
            listOf("https://open.spotify.com/playlist/BBB222?si=1&pi=2", "https://open.spotify.com/playlist/CCC333"),
            links,
        )
    }

    /** Word positions copied from a real report (names changed). */
    private fun reportWords(): List<PdfWord> {
        val w = mutableListOf<PdfWord>()
        fun put(y: Float, x: Float, text: String) = text.split(' ').fold(x) { cx, t ->
            w += PdfWord(1, cx, y, t); cx + t.length * 5f + 3f
        }
        put(20f, 6f, "X All Upcoming Confirmed Bookings with MEC")
        put(38f, 6f, "Generated by Someone on 01/11/2026 01:00 PM")
        val header = listOf(
            21.7f to "Event Date", 138.9f to "Week Day", 256f to "Booking Name", 373.1f to "Event Type",
            490.2f to "Venue", 607.3f to "Event Suburb", 724.5f to "Start Time", 841.6f to "End Time",
            958.7f to "Entertainment 1", 1075.8f to "Entertainment 2", 1192.9f to "Entertainment 3", 1310f to "Producer",
        )
        header.forEach { (x, t) -> put(116f, x, t) }
        // Row 1 (two lines of booking name)
        put(139f, 21.7f, "20/11/2026 ( 2 )"); put(139f, 138.9f, "Friday"); put(139f, 256f, "Ann Example |")
        put(151f, 256f, "Bubbly Christmas Party"); put(139f, 373.1f, "Corporate"); put(139f, 490.2f, "Demo Bar")
        put(139f, 724.5f, "12pm"); put(139f, 841.6f, "5pm"); put(139f, 1075.8f, "Me DJ")
        // Row 2: same date, date cell empty
        put(186f, 138.9f, "Friday"); put(186f, 256f, "Joe Sample"); put(186f, 373.1f, "School Event")
        put(186f, 490.2f, "Sample Town"); put(198f, 490.2f, "Hall"); put(186f, 724.5f, "7pm"); put(186f, 841.6f, "11pm")
        put(186f, 1310f, "Leanne Producer")
        // Row 3: on hold
        put(256f, 21.7f, "27/11/2026 ( 1 )"); put(256f, 138.9f, "Friday"); put(256f, 256f, "ON HOLD")
        put(256f, 373.1f, "ON HOLD")
        // Row 4: typo'd end time
        put(300f, 21.7f, "20/02/2027 ( 1 )"); put(300f, 138.9f, "Saturday"); put(300f, 256f, "Kim Tester")
        put(300f, 373.1f, "Wedding"); put(300f, 724.5f, "7pm"); put(300f, 841.6f, "12:45pm")
        return w
    }

    @Test
    fun readsZohoReportTable() {
        val (generated, rows) = ZohoReport.parse(reportWords())
        assertEquals(LocalDate.of(2026, 11, 1), generated)
        assertEquals(4, rows.size)
        assertEquals("Ann Example | Bubbly Christmas Party", rows[0].name)
        assertEquals("12pm", rows[0].start)
        assertEquals(LocalDate.of(2026, 11, 20), rows[1].date) // inherited
        assertEquals("Sample Town Hall", rows[1].venue)
        assertEquals("Leanne Producer", rows[1].producer)
        assertTrue(rows[2].onHold)
        val typo = JobBook.zohoFact("r", 1, 3, rows[3])
        assertEquals("19:00", typo.fields[F.START])
        assertEquals("00:45", typo.fields[F.END])
    }

    @Test
    fun readsNarrowZohoLayoutWithWrappedWords() {
        val w = mutableListOf<PdfWord>()
        fun put(y: Float, x: Float, text: String) = text.split(' ').fold(x) { cx, t ->
            w += PdfWord(1, cx, y, t); cx + t.length * 5f + 3f
        }
        put(18f, 5f, "Generated by Someone on 01/08/2026 12:00 PM")
        listOf(18.8f to "Event Date", 122.5f to "Week", 180.8f to "Booking Name", 420.5f to "Event", 485.3f to "Venue",
            718.5f to "Event", 796.3f to "Start", 861.1f to "End", 919.4f to "Entertainment 1", 1347f to "Producer",
        ).forEach { (x, t) -> put(100f, x, t) }
        listOf(122.5f to "Day", 420.5f to "Type", 718.5f to "Suburb", 796.3f to "Time", 861.1f to "Time")
            .forEach { (x, t) -> put(110.4f, x, t) }
        put(130f, 18.8f, "05/08/2026 ( 1 )"); put(130f, 122.5f, "Wedne"); put(140.3f, 122.5f, "sday")
        put(130f, 180.8f, "Erin Example | Sample Event"); put(130f, 420.5f, "Corporat"); put(140.3f, 420.5f, "e")
        put(130f, 485.3f, "Demo Hall"); put(130f, 796.3f, "6pm"); put(130f, 861.1f, "11:30p"); put(140.3f, 861.1f, "m")
        put(160f, 18.8f, "08/08/2026 ( 1 )"); put(160f, 122.5f, "Saturd"); put(170.3f, 122.5f, "ay")
        put(160f, 180.8f, "Dev Sample"); put(160f, 420.5f, "Weddin"); put(170.3f, 420.5f, "g")
        val (_, rows) = ZohoReport.parse(w)
        assertEquals(2, rows.size)
        assertEquals("Erin Example | Sample Event", rows[0].name)
        assertEquals("Corporate", rows[0].type)
        assertEquals("11:30pm", rows[0].end)
        assertEquals("Wedding", rows[1].type)
        assertEquals(LocalDate.of(2026, 8, 8), rows[1].date)
    }

    // ------------------------------------------------------------------ merging into jobs

    private fun fact(id: String, at: Long, kind: MailKind, date: LocalDate?, vararg f: Pair<String, String>) =
        MailFact(id, at, kind, "s $id", date, mapOf(*f))

    private val d = LocalDate.of(2026, 11, 20)

    @Test
    fun mergesEmailsAboutOneGigAndKeepsOthersSeparate() {
        val facts = listOf(
            fact("inv", 1, MailKind.INVOICED, d, F.NAME to "Joe Sample | Sample High School"),
            fact("conf", 2, MailKind.CONFIRMED, d, F.NAME to "Joe Sample", F.START to "19:00", F.END to "23:00", F.ARRIVE to "18:00", F.PAY to "$ 390.00"),
            fact("sheet", 3, MailKind.JOBSHEET, d, F.NAME to "Joe Sample | School Formal", F.START to "19:30", F.END to "23:30", F.CLIENT_PHONE to "0400 000 999"),
            JobBook.zohoFact("rep", 4, 0, ZohoRow(d, "Ann Example | Bubbly", "Corporate", "Demo Bar", "", "12pm", "5pm", "")),
            fact("hold", 5, MailKind.HOLD, d),
        )
        val jobs = JobBook.build(facts)
        assertEquals(2, jobs.size)
        val joe = jobs.single { it[F.NAME]!!.startsWith("Joe") }
        assertEquals(JobStatus.CONFIRMED, joe.status)
        assertEquals("19:30", joe[F.START]) // job sheet beats confirmation
        assertEquals("23:30", joe[F.END])
        assertEquals("$ 390.00", joe[F.PAY]) // filled from the confirmation
        assertEquals("0400 000 999", joe[F.CLIENT_PHONE])
        assertTrue(Regex("[0-9a-v]{5,1024}").matches(joe.eventId))
        // Same mail always gives the same event id (so every device updates the same event).
        assertEquals(joe.eventId, JobBook.build(facts.reversed()).single { it[F.NAME]!!.startsWith("Joe") }.eventId)
        assertNotEquals(joe.eventId, jobs.single { it[F.NAME]!!.startsWith("Ann") }.eventId)
    }

    @Test
    fun cancellationAndPending() {
        val c = LocalDate.of(2026, 12, 5)
        val jobs = JobBook.build(
            listOf(
                fact("conf", 1, MailKind.CONFIRMED, c, F.NAME to "Robin Demo"),
                fact("cancel", 2, MailKind.CANCELLED, c, F.NAME to "Robin Demo", F.CANCEL_PAY to "$ 200.00"),
                fact("inv", 3, MailKind.INVOICED, d, F.NAME to "Sam Corp | Corp Co"),
            )
        )
        assertEquals(JobStatus.CANCELLED, jobs.single { it.date == c }.status)
        assertEquals(JobStatus.PENDING, jobs.single { it.date == d }.status)
    }

    @Test
    fun flagsJobsMissingFromLatestReport() {
        val report = ReportInfo("rep", 100, LocalDate.of(2026, 11, 1))
        val facts = listOf(
            fact("a", 10, MailKind.CONFIRMED, d, F.NAME to "Joe Sample"),
            fact("b", 10, MailKind.CONFIRMED, d.plusDays(1), F.NAME to "Kim Tester"),
            JobBook.zohoFact("rep", 100, 0, ZohoRow(d.plusDays(1), "Kim Tester", "Wedding", "", "", "7pm", "11pm", "")),
            fact("c", 200, MailKind.CONFIRMED, d.plusDays(2), F.NAME to "New Booking"), // booked after the report
        )
        val jobs = JobBook.build(facts, report).associateBy { it.date }
        assertTrue(jobs[d]!!.notInReport)
        assertFalse(jobs[d.plusDays(1)]!!.notInReport)
        assertFalse(jobs[d.plusDays(2)]!!.notInReport)
    }

    @Test
    fun buildsCalendarEvent() {
        val job = JobBook.build(
            listOf(
                fact(
                    "s", 1, MailKind.JOBSHEET, d, F.NAME to "Joe Sample | School Formal", F.TYPE to "School Event",
                    F.ARRIVE to "18:00", F.START to "19:00", F.END to "00:30", F.VENUE to "Sample Town Hall",
                    F.ADDRESS to "1 Test St", F.SUBURB to "Testville", F.CLIENT to "Joe Sample",
                    F.CLIENT_PHONE to "0400 000 999", F.VENUE_PHONE to "(03) 9000 0000",
                ).copy(spotify = listOf("https://open.spotify.com/playlist/AAA111")),
            )
        ).single()
        val ev = JobEvent.toJson(job, "me@example.com", listOf(DriveFileInfo("Run Sheet.pdf", "fid", "https://drive/x", "application/pdf")))
        assertEquals("🎧 Joe Sample | School Formal (School Event)", ev.getString("summary"))
        assertEquals("2026-11-20T18:00:00+11:00", ev.getJSONObject("start").getString("dateTime"))
        assertEquals("2026-11-21T00:30:00+11:00", ev.getJSONObject("end").getString("dateTime")) // past midnight
        assertEquals("Sample Town Hall, 1 Test St, Testville", ev.getString("location"))
        val desc = ev.getString("description")
        assertTrue(desc.contains("tel:0400000999"))
        assertTrue(desc.contains("tel:0390000000"))
        assertTrue(desc.contains("https://www.google.com/maps/search/?api=1&query=Sample+Town+Hall"))
        assertTrue(desc.contains("open.spotify.com/playlist/AAA111"))
        assertEquals("fid", ev.getJSONArray("attachments").getJSONObject(0).getString("fileId"))
        val props = ev.getJSONObject("extendedProperties").getJSONObject("private")
        assertEquals("0400 000 999", props.getString(F.CLIENT_PHONE))
        assertEquals("https://open.spotify.com/playlist/AAA111", props.getString("spotify"))

        // No times known -> all-day event.
        val allDay = JobEvent.toJson(JobBook.build(listOf(fact("z", 1, MailKind.CONFIRMED, d, F.NAME to "Kim Tester"))).single(), "", emptyList())
        assertEquals("2026-11-20", allDay.getJSONObject("start").getString("date"))
    }

    @Test
    fun readsHoldEmail() {
        val html = table(
            "ON HOLD CONFIRMATION", "Friday | 27/11/2026", "Hi James, we have you placed on hold 😊",
            "ON HOLD DETAILS:Friday, 27/11/2026$ 180.00", "WHAT THIS MEANS:",
        )
        val h = JobParser.parse("h1", 1, "ON HOLD CONFIRMATION | Friday, 27/11/2026", "", html, null)!!
        assertEquals(MailKind.HOLD, h.kind)
        assertEquals(LocalDate.of(2026, 11, 27), h.date)
        assertEquals("$ 180.00", h.fields[F.PAY])
        assertNull(JobParser.parse("h2", 1, "Mel Ent Co | On Hold dates for 2025 Summer", "", html, null))
    }

    @Test
    fun holdsShowUntilBookedOrDroppedFromReport() {
        val nov27 = LocalDate.of(2026, 11, 27)
        val dec4 = LocalDate.of(2026, 12, 4)
        val dec12 = LocalDate.of(2026, 12, 12)
        val jan9 = LocalDate.of(2027, 1, 9)
        val report = ReportInfo("rep", 100, LocalDate.of(2026, 11, 1))
        val rows = listOf(
            ZohoRow(nov27, "ON HOLD", "ON HOLD", "", "", "", "", ""),
            ZohoRow(dec12, "ON HOLD", "ON HOLD", "", "", "", "", ""),
            ZohoRow(dec4, "Kim Tester", "Corporate", "Demo Bar", "", "5pm", "9pm", ""),
        )
        val facts = JobBook.reportFacts("rep", 100, rows) + listOf(
            fact("h27", 10, MailKind.HOLD, nov27, F.NAME to "ON HOLD", F.PAY to "$ 180.00"),
            fact("h4", 10, MailKind.HOLD, dec4, F.NAME to "ON HOLD"), // became a gig
            fact("h12", 10, MailKind.HOLD, dec12, F.NAME to "ON HOLD"),
            fact("old", 10, MailKind.HOLD, jan9.minusDays(7), F.NAME to "ON HOLD"), // released: not in newer report
            fact("new", 200, MailKind.HOLD, jan9, F.NAME to "ON HOLD"), // put on hold after the report
            fact("inv", 20, MailKind.INVOICED, nov27, F.NAME to "Sam Corp | Corp Co", F.VENUE to "Demo Haus"),
        )
        val holds = JobBook.build(facts, report).filter { it.status == JobStatus.HOLD }.associateBy { it.date }
        assertEquals(setOf(nov27, dec12, jan9), holds.keys)
        assertEquals("$ 180.00", holds[nov27]!![F.PAY])
        assertEquals("Sam Corp | Corp Co", holds[nov27]!![F.INVOICED])

        val ev = JobEvent.toJson(holds[nov27]!!, "", emptyList())
        assertEquals("⏸ ON HOLD · MEC (invoiced: Sam Corp | Corp Co)", ev.getString("summary"))
        assertEquals("2026-11-27", ev.getJSONObject("start").getString("date"))
        assertEquals("5", ev.getString("colorId"))
        assertTrue(Regex("[0-9a-v]{5,1024}").matches(holds[nov27]!!.eventId))
    }

    // ------------------------------------------------------------------ removal lock

    private val today = LocalDate.of(2026, 11, 5)

    private fun calEv(id: String, date: LocalDate, status: String, deleted: Boolean = false) = CalEvent(
        id, "title $id", date, null, null, "", "", "", emptyList(),
        mapOf("ih" to "1", "status" to status), if (deleted) "cancelled" else "confirmed",
    )

    private fun job(name: String, date: LocalDate, kind: MailKind = MailKind.CONFIRMED, at: Long = 1) =
        JobBook.build(listOf(fact(name, at, kind, date, F.NAME to name))).single()

    @Test
    fun syncNeverRemovesDatesByItself() {
        val gig = job("Joe Sample", d)
        val oldHold = calEv(JobBook.eventId(d, "on-hold"), d, JobStatus.HOLD.name) // hold that became the gig
        val released = calEv(JobBook.eventId(d.plusDays(7), "on-hold"), d.plusDays(7), JobStatus.HOLD.name)
        val moved = calEv(JobBook.eventId(d.plusDays(1), "kim"), d.plusDays(1), JobStatus.CONFIRMED.name)
        val past = calEv(JobBook.eventId(today.minusDays(3), "old"), today.minusDays(3), JobStatus.CONFIRMED.name)
        val mine = CalEvent("myevent", "Dentist", d, null, null, "", "", "", emptyList(), emptyMap())
        val existing = listOf(oldHold, released, moved, past, mine).associateBy { it.id }

        val plan = CalendarPlan.make(listOf(gig), existing, emptySet(), emptyList(), today)
        assertEquals(listOf(gig), plan.writes)
        assertTrue(plan.replacedUnconfirmed.isEmpty()) // nothing is deleted automatically
        val asked = plan.ask.associateBy { it.eventId }
        assertEquals(setOf(oldHold.id, released.id, moved.id), asked.keys) // past + your own events untouched
        assertTrue(asked[oldHold.id]!!.reason.contains("Gig confirmed that night: Joe Sample"))
        assertTrue(plan.ask.all { it.action == Removal.DELETE })

        // "Keep" means it's never asked about again.
        val kept = CalendarPlan.make(listOf(gig), existing, setOf(released.id), emptyList(), today)
        assertFalse(kept.ask.any { it.eventId == released.id })
    }

    @Test
    fun cancellationsNeedApprovalAndDeletedEventsStayDeleted() {
        val c = d.plusDays(3)
        val cancelled = JobBook.build(
            listOf(
                fact("conf", 1, MailKind.CONFIRMED, c, F.NAME to "Robin Demo"),
                fact("x", 2, MailKind.CANCELLED, c, F.NAME to "Robin Demo", F.CANCEL_PAY to "$ 200.00"),
            )
        ).single()
        val live = calEv(cancelled.eventId, c, JobStatus.CONFIRMED.name)
        val plan = CalendarPlan.make(listOf(cancelled), mapOf(live.id to live), emptySet(), emptyList(), today)
        assertTrue(plan.writes.isEmpty()) // not overwritten as CANCELLED by itself
        assertEquals(Removal.CANCEL, plan.ask.single().action)
        assertTrue(plan.ask.single().reason.contains("$ 200.00"))

        // A gig you deleted yourself in Google Calendar isn't recreated.
        val gig = job("Joe Sample", d)
        val gone = calEv(gig.eventId, d, JobStatus.CONFIRMED.name, deleted = true)
        assertTrue(CalendarPlan.make(listOf(gig), mapOf(gone.id to gone), emptySet(), emptyList(), today).writes.isEmpty())
    }

    @Test
    fun unconfirmedIsReplacedOnlyByAConfirmedGig() {
        val u1 = CalEvent.fromJson(unconfirmedEvent(d, "Wedding enquiry"))!!
        val u2 = CalEvent.fromJson(unconfirmedEvent(d.plusDays(1), ""))!!
        assertTrue(u1.unconfirmed)
        assertTrue(Regex("[0-9a-v]{5,1024}").matches(u1.id))
        val existing = mapOf(u1.id to u1, u2.id to u2)
        val hold = JobBook.build(
            listOf(fact("h", 1, MailKind.HOLD, d.plusDays(1), F.NAME to "ON HOLD"))
        ).single()
        val plan = CalendarPlan.make(listOf(job("Joe Sample", d), hold), existing, emptySet(), emptyList(), today)
        assertEquals(listOf(u1.id), plan.replacedUnconfirmed) // a hold doesn't replace it, a gig does
        assertTrue(plan.ask.isEmpty())
        // Pending requests for events that no longer exist are dropped.
        val stale = Removal("gone", d, "t", Removal.DELETE, "r")
        assertTrue(CalendarPlan.make(emptyList(), emptyMap(), emptySet(), listOf(stale), today).ask.isEmpty())
    }

    @Test
    fun factsRoundTripThroughJson() {
        val f = fact("x", 5, MailKind.JOBSHEET, d, F.NAME to "A").copy(
            spotify = listOf("s"), links = listOf("l"), attachments = listOf(AttachmentRef("x", "a", "f.pdf", "application/pdf", 9)),
        )
        assertEquals(f, MailFact.fromJson(f.toJson()))
    }

    @Test
    fun invoicePrefill() {
        assertEquals("Jordan + Kim", invoiceClient("Jordan Test | Jordan + Kim"))
        assertEquals("Robin Demo", invoiceClient("Robin Demo"))
        assertEquals("475.00", invoiceAmount("$ 475.00"))
        assertEquals("1250", invoiceAmount("$1,250"))
    }
}
