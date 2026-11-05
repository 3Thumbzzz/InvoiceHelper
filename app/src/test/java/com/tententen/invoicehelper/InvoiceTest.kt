package com.tententen.invoicehelper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.junit.Before
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class InvoiceTest {
    private val outDir = File("build/invoice-test").apply { mkdirs() }

    @Before
    fun fonts() = InvoicePdf.init(RuntimeEnvironment.getApplication())

    private fun mec() = InvoiceData(
        Defaults.companies[0], Defaults.profile, 74, "Brandon & Cherry Wedding",
        490.0, 25.0, 0.0, LocalDate.of(2026, 11, 5),
    )

    private fun php() = InvoiceData(
        Defaults.companies[1], Defaults.profile, 58,
        "Acme Corporation End of Year Christmas Party Extravaganza 2026",
        1200.0, 0.0, 80.0, LocalDate.of(2026, 9, 21),
    )

    private fun render(d: InvoiceData, name: String) {
        val scale = 1.5f
        val bmp = Bitmap.createBitmap((612 * scale).toInt(), (792 * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(scale, scale)
        InvoicePdf.draw(c, d)
        File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun renderInvoices() {
        render(mec(), "mec.png")
        render(php(), "php.png")
        render(
            mec().copy(travel = 45.0, extras = listOf(ExtraItem("Extra hour", 100.0), ExtraItem("Uplighting", 150.0))),
            "extras.png",
        )
    }

    @Test
    fun namingAndSubject() {
        assertEquals("Invoice_MEC74_2026.11.5.pdf", mec().fileName())
        assertEquals("5/Nov/2026 - Brandon & Cherry Wedding", mec().subject())
        assertEquals(1280.0, php().total, 0.001)
        assertEquals("Invoice_PHP58_2026.9.21.pdf", php().fileName())
    }

    @Test
    fun emailMessage() {
        val pdf = File(outDir, "dummy.pdf").apply { writeBytes("%PDF-1.4 test".toByteArray()) }
        val d = mec()
        val msg = Mailer.buildMessage(
            "dj@example.com", d.profile.name,
            d.company.email, d.subject(), "", File(outDir, d.fileName()).also { pdf.copyTo(it, true) },
        )
        val raw = ByteArrayOutputStream().also { msg.writeTo(it) }.toString()
        File(outDir, "email.eml").writeText(raw)
        assertEquals(d.subject(), msg.subject)
        assertTrue(raw.contains("To: bookings@melbentco.com.au"))
        assertTrue(raw.contains("application/pdf"))
        assertTrue(raw.contains("Invoice_MEC74_2026.11.5.pdf"))
    }

    @Test
    fun companyExportImportRoundTrip() {
        val json = CompanyTransfer.export(Defaults.companies)
        File(outDir, "companies.json").writeText(json)
        val parsed = CompanyTransfer.parse(json)
        assertEquals(Defaults.companies, parsed)
        // bare array form is accepted too
        assertEquals(2, CompanyTransfer.parse(org.json.JSONArray(Defaults.companies.map { it.toJson() }).toString()).size)
    }

    @Test
    fun importMergesByCodeAndKeepsHigherInvoiceNumber() {
        val state = AppState(Store(RuntimeEnvironment.getApplication()))
        val mecLocal = state.companies.first { it.code == "MEC" }
        val incoming = listOf(
            // same company from another phone: different id, newer email, higher counter
            mecLocal.copy(id = "other-device-id", email = "new@melbentco.com.au", nextInvoiceNo = 80),
            // PHP with a lower counter must not move the local counter backwards
            state.companies.first { it.code == "PHP" }.copy(id = "x", nextInvoiceNo = 10),
            Company(name = "New Venue Co", code = "NVC", email = "a@b.c", nextInvoiceNo = 1),
        )
        val (added, updated) = state.importCompanies(CompanyTransfer.parse(CompanyTransfer.export(incoming)))
        assertEquals(1, added)
        assertEquals(2, updated)
        val mec = state.companies.first { it.code == "MEC" }
        assertEquals(mecLocal.id, mec.id)
        assertEquals(80, mec.nextInvoiceNo)
        assertEquals("new@melbentco.com.au", mec.email)
        assertEquals(58, state.companies.first { it.code == "PHP" }.nextInvoiceNo)
        assertEquals(3, state.companies.size)
    }

    @Test
    fun extrasInLinesAndTotal() {
        val d = mec().copy(travel = 45.0, extras = listOf(ExtraItem("Extra hour", 100.0)))
        assertEquals(
            listOf("Brandon & Cherry Wedding" to 490.0, "Parking" to 25.0, "Travel" to 45.0, "Extra hour" to 100.0),
            d.lineItems(),
        )
        assertEquals(660.0, d.total, 0.001)
    }
}
