package com.tententen.invoicehelper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Renders an invoice in the same layout as the existing Excel-generated invoices
 * (US Letter, To/From header, Invoice No. box, 7-row item table, total, date).
 */
object InvoicePdf {
    private const val PAGE_W = 612
    private const val PAGE_H = 792

    // Bundled static TrueType fonts (Liberation Sans = Arial metrics, like the Excel invoices).
    // Android's own system font is variable, which the PDF writer can only emit as Type3 fonts,
    // and some viewers (e.g. Drive's PDF viewer) render those as a blank page.
    private var regularFace: Typeface? = null
    private var boldFace: Typeface? = null

    fun init(ctx: Context) {
        if (regularFace != null) return
        runCatching {
            regularFace = Typeface.createFromAsset(ctx.assets, "fonts/LiberationSans-Regular.ttf")
            boldFace = Typeface.createFromAsset(ctx.assets, "fonts/LiberationSans-Bold.ttf")
        }
    }

    fun write(d: InvoiceData, out: File) {
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
            draw(page.canvas, d)
            doc.finishPage(page)
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
    }

    private data class Line(val name: String, val qty: Int, val unit: Double)

    internal fun draw(c: Canvas, d: InvoiceData) {
        val normal = regularFace ?: Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        val heavy = boldFace ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        fun paint(bold: Boolean = false, size: Float = 9.5f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (bold) heavy else normal
            textSize = size
            color = Color.BLACK
            // Without these, glyph advances are hinted/rounded to whole points at small sizes,
            // which shows up in the PDF as gaps after letters like "v" ("Tra vel").
            hinting = Paint.HINTING_OFF
            isSubpixelText = true
            @Suppress("DEPRECATION")
            isLinearText = true
        }
        val reg = paint()
        val bold = paint(bold = true)
        val link = paint().apply { color = Color.rgb(0, 0, 204); isUnderlineText = true }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f
            color = Color.BLACK
        }

        val co = d.company
        val me = d.profile
        val lh = 17.7f
        val lLabel = 54f
        val lVal = 111f
        val rLabel = 390f
        val rVal = 440f

        // ---- To / From blocks ----
        var y = 92f
        c.drawText("To:", lLabel, y, reg)
        listOf(co.name, co.address1, co.address2, co.address3).filter { it.isNotBlank() }
            .forEachIndexed { i, s -> drawFitted(c, s, lVal, y + i * lh, 265f, reg) }

        c.drawText("From:", rLabel, y, reg)
        val from = buildList {
            if (me.name.isNotBlank()) add(me.name to bold)
            listOf(me.address1, me.address2, me.address3).filter { it.isNotBlank() }.forEach { add(it to reg) }
        }
        from.forEachIndexed { i, (s, p) -> drawFitted(c, s, rVal, y + i * lh, 160f, p) }

        // ---- Contact blocks ----
        y = 186f
        listOf(
            Triple(co.phoneLabel.ifBlank { "Phone" }, co.phone, reg),
            Triple("Email", co.email, link),
            Triple("Web", co.web, link),
        ).filter { it.second.isNotBlank() }.forEachIndexed { i, (label, value, p) ->
            c.drawText(label, lLabel, y + i * lh, reg)
            drawFitted(c, value, lVal, y + i * lh, 265f, p)
        }
        listOf(
            "Mobile" to me.mobile,
            "Email" to me.email,
            "ABN" to me.abn,
            "BSB" to me.bsb,
            "ACC" to me.acc,
        ).filter { it.second.isNotBlank() }.forEachIndexed { i, (label, value) ->
            c.drawText(label, rLabel, y + i * lh, reg)
            drawFitted(c, value, rVal, y + i * lh, 160f, reg)
        }

        // ---- Invoice No. box + date ----
        val bx0 = 52f
        val bx1 = 109f
        c.drawRect(bx0, 264f, bx1, 281f, stroke)
        c.drawRect(bx0, 281f, bx1, 307f, stroke)
        center(c, "Invoice No.", (bx0 + bx1) / 2, 276f, paint(bold = true, size = 8.8f))
        center(c, d.invoiceNo.toString(), (bx0 + bx1) / 2, 297.5f, reg)
        c.drawText("Date:", rLabel, 297.5f, reg)
        c.drawText(d.date.format(LONG_DATE), rVal, 297.5f, reg)

        // ---- Item table ----
        val xs = floatArrayOf(52f, 109f, 330f, 372f, 450f, 564f)
        val top = 307f
        val headH = 30f
        val rowH = 29.5f
        val rows = 7
        val bodyTop = top + headH
        val totalTop = bodyTop + rows * rowH
        val bottom = totalTop + 30f

        c.drawRect(xs[0], top, xs[5], bottom, stroke)
        for (r in 0..rows) c.drawLine(xs[0], bodyTop + r * rowH, xs[5], bodyTop + r * rowH, stroke)
        for (i in 1..4) c.drawLine(xs[i], top, xs[i], totalTop, stroke)
        c.drawLine(xs[4], totalTop, xs[4], bottom, stroke)

        val headBase = top + headH / 2 + 3.5f
        listOf("Item No.", "Item", "No.", "Unit Cost", "Total Cost").forEachIndexed { i, h ->
            center(c, h, (xs[i] + xs[i + 1]) / 2, headBase, bold)
        }

        val lines = d.lineItems().map { (name, amount) -> Line(name, 1, amount) }
        for (r in 0 until rows) {
            val base = bodyTop + r * rowH + rowH / 2 + 3.5f
            val line = lines.getOrNull(r)
            if (line != null) {
                center(c, (r + 1).toString(), (xs[0] + xs[1]) / 2, base, reg)
                drawFitted(c, line.name, xs[1] + 5f, base, xs[2] - xs[1] - 10f, reg)
                center(c, line.qty.toString(), (xs[2] + xs[3]) / 2, base, reg)
                drawMoney(c, line.unit, xs[3], xs[4], base, reg)
                drawMoney(c, line.unit * line.qty, xs[4], xs[5], base, reg)
            } else {
                drawMoney(c, 0.0, xs[3], xs[4], base, reg)
                drawMoney(c, 0.0, xs[4], xs[5], base, reg)
            }
        }

        val totalBase = totalTop + 15f + 3.5f
        c.drawText("Total", xs[0] + 4f, totalBase, bold)
        drawMoney(c, d.total, xs[4], xs[5], totalBase, bold)

        // ---- Footer date ----
        val footBase = bottom + 22f
        c.drawText("Date", rLabel, footBase, bold)
        val short = d.date.format(SHORT_DATE)
        c.drawText(short, xs[5] - 4f - bold.measureText(short), footBase, bold)
    }

    private fun center(c: Canvas, s: String, cx: Float, base: Float, p: Paint) {
        c.drawText(s, cx - p.measureText(s) / 2, base, p)
    }

    private fun drawMoney(c: Canvas, v: Double, x0: Float, x1: Float, base: Float, p: Paint) {
        c.drawText("$", x0 + 4f, base, p)
        val s = if (v == 0.0) "-" else money(v)
        c.drawText(s, x1 - 4f - p.measureText(s), base, p)
    }

    /** Shrinks the font (down to 7pt) and then truncates so text stays inside [maxW]. */
    private fun drawFitted(c: Canvas, s: String, x: Float, base: Float, maxW: Float, p: Paint) {
        val fp = Paint(p)
        while (fp.measureText(s) > maxW && fp.textSize > 7f) fp.textSize -= 0.25f
        var t = s
        if (fp.measureText(t) > maxW) {
            while (t.isNotEmpty() && fp.measureText("$t…") > maxW) t = t.dropLast(1)
            t = "$t…"
        }
        c.drawText(t, x, base, fp)
    }
}
