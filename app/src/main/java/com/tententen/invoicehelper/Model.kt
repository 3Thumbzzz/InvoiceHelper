package com.tententen.invoicehelper

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** A company you DJ for and send invoices to. */
data class Company(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Short code used in the PDF file name, e.g. MEC -> Invoice_MEC74_2026.11.5.pdf */
    val code: String = "",
    val address1: String = "",
    val address2: String = "",
    val address3: String = "",
    val phoneLabel: String = "Phone",
    val phone: String = "",
    /** Where invoices get emailed. Comma-separate multiple addresses. */
    val email: String = "",
    val web: String = "",
    val nextInvoiceNo: Int = 1,
    /** When this company was last changed on any device (ms since epoch); 0 = untouched default. */
    val updatedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("code", code)
        .put("address1", address1).put("address2", address2).put("address3", address3)
        .put("phoneLabel", phoneLabel).put("phone", phone)
        .put("email", email).put("web", web).put("nextInvoiceNo", nextInvoiceNo)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject) = Company(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            code = o.optString("code"),
            address1 = o.optString("address1"),
            address2 = o.optString("address2"),
            address3 = o.optString("address3"),
            phoneLabel = o.optString("phoneLabel", "Phone"),
            phone = o.optString("phone"),
            email = o.optString("email"),
            web = o.optString("web"),
            nextInvoiceNo = o.optInt("nextInvoiceNo", 1),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/** Your own details (the "From" block) plus the Google account invoices are sent from. */
data class Profile(
    val name: String = "",
    val address1: String = "",
    val address2: String = "",
    val address3: String = "",
    val mobile: String = "",
    val email: String = "",
    val abn: String = "",
    val bsb: String = "",
    val acc: String = "",
    /** Google account connected via "Sign in with Google"; blank until signed in. */
    val googleAccount: String = "",
    val emailBody: String = "",
    /** When the shared details (not the Google account) last changed; 0 = untouched default. */
    val updatedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("address1", address1).put("address2", address2).put("address3", address3)
        .put("mobile", mobile).put("email", email).put("abn", abn).put("bsb", bsb).put("acc", acc)
        .put("googleAccount", googleAccount).put("emailBody", emailBody).put("updatedAt", updatedAt)

    /** Same details without the device-specific Google account, for comparing/syncing. */
    fun shared(): Profile = copy(googleAccount = "", updatedAt = 0L)

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            name = o.optString("name"),
            address1 = o.optString("address1"),
            address2 = o.optString("address2"),
            address3 = o.optString("address3"),
            mobile = o.optString("mobile"),
            email = o.optString("email"),
            abn = o.optString("abn"),
            bsb = o.optString("bsb"),
            acc = o.optString("acc"),
            googleAccount = o.optString("googleAccount"),
            emailBody = o.optString("emailBody"),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

data class SentRecord(
    val company: String,
    val invoiceNo: Int,
    val client: String,
    val total: Double,
    val date: String,
    val to: String,
    val sentAt: Long = 0L,
) {
    /** Identifies the same sent invoice across devices. */
    val key: String get() = "$company|$invoiceNo|$client|$date"

    fun toJson(): JSONObject = JSONObject()
        .put("company", company).put("invoiceNo", invoiceNo).put("client", client)
        .put("total", total).put("date", date).put("to", to).put("sentAt", sentAt)

    companion object {
        fun fromJson(o: JSONObject) = SentRecord(
            company = o.optString("company"),
            invoiceNo = o.optInt("invoiceNo"),
            client = o.optString("client"),
            total = o.optDouble("total", 0.0),
            date = o.optString("date"),
            to = o.optString("to"),
            sentAt = o.optLong("sentAt", 0L),
        )
    }
}

val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d/MMM/yyyy", Locale.ENGLISH)
val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d/MMM/yy", Locale.ENGLISH)

fun money(v: Double): String = String.format(Locale.US, "%,.2f", v)

fun String.toMoney(): Double = replace(",", "").trim().toDoubleOrNull() ?: 0.0

/** An extra invoice line beyond the job/parking/travel, e.g. "Extra hour" $100. */
data class ExtraItem(val name: String, val amount: Double)

/** The invoice table has room for this many lines. */
const val MAX_INVOICE_LINES = 7

/** Everything needed to render and send one invoice. */
data class InvoiceData(
    val company: Company,
    val profile: Profile,
    val invoiceNo: Int,
    val client: String,
    val amount: Double,
    val parking: Double,
    val travel: Double,
    val date: LocalDate,
    val extras: List<ExtraItem> = emptyList(),
) {
    val total: Double get() = amount + parking + travel + extras.sumOf { it.amount }

    /** The rows that appear in the invoice table, in order. */
    fun lineItems(): List<Pair<String, Double>> = buildList {
        add(client to amount)
        if (parking > 0) add("Parking" to parking)
        if (travel > 0) add("Travel" to travel)
        extras.forEach { add(it.name to it.amount) }
    }

    /** Matches the existing naming, e.g. Invoice_MEC73_2026.10.3.pdf */
    fun fileName(): String {
        val code = company.code.filter { it.isLetterOrDigit() }
        return "Invoice_${code}${invoiceNo}_${date.year}.${date.monthValue}.${date.dayOfMonth}.pdf"
    }

    /** "3/Oct/2026 - Brandon & Cherry Wedding" */
    fun subject(): String = "${date.format(LONG_DATE)} - $client"
}

object Defaults {
    /** Personal/bank details are entered in the app (Me tab) and synced via Drive, never stored in code. */
    val profile = Profile()

    val companies = listOf(
        Company(
            name = "Melbourne Entertainment Company",
            code = "MEC",
            address1 = "10 Oxley Rd",
            address2 = "Hawthorn",
            address3 = "VIC 3122",
            phoneLabel = "Phone",
            phone = "1300 858 981",
            email = "bookings@melbentco.com.au",
            web = "https://melbourneentertainmentco.com.au/",
            nextInvoiceNo = 74,
        ),
        Company(
            name = "Party Hire Productions",
            code = "PHP",
            address1 = "PO Box 187",
            address2 = "Chirnside Park",
            address3 = "VIC 3116",
            phoneLabel = "Mobile",
            phone = "0411 095 966",
            email = "info@partyhireproductions.com",
            web = "partyhireproductions.com.au",
            nextInvoiceNo = 58,
        ),
    )
}

/**
 * Portable company-list file for moving companies between devices.
 * Format (JSON):
 * { "app": "InvoiceHelper", "type": "companies", "version": 1, "exportedAt": "...", "companies": [ {...}, ... ] }
 * Each company uses the same fields as [Company.toJson]. A bare JSON array of companies is also accepted.
 */
object CompanyTransfer {
    fun export(list: List<Company>): String = JSONObject()
        .put("app", "InvoiceHelper")
        .put("type", "companies")
        .put("version", 1)
        .put("exportedAt", java.time.OffsetDateTime.now().toString())
        .put("companies", JSONArray(list.map { it.toJson() }))
        .toString(2)

    fun parse(text: String): List<Company> {
        val t = text.trim().removePrefix("﻿")
        val arr = if (t.startsWith("[")) {
            JSONArray(t)
        } else {
            val o = JSONObject(t)
            require(o.has("companies")) { "This isn't an Invoice Helper companies file." }
            o.getJSONArray("companies")
        }
        val list = (0 until arr.length()).map { Company.fromJson(arr.getJSONObject(it)) }
            .filter { it.name.isNotBlank() }
        require(list.isNotEmpty()) { "No companies found in that file." }
        return list
    }
}

/** Persists everything in private SharedPreferences as JSON. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("invoice_helper", Context.MODE_PRIVATE)

    fun loadCompanies(): List<Company> {
        val raw = prefs.getString("companies", null)
            ?: return Defaults.companies.also { saveCompanies(it) }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Company.fromJson(arr.getJSONObject(it)) }
    }

    fun saveCompanies(list: List<Company>) {
        prefs.edit().putString("companies", JSONArray(list.map { it.toJson() }).toString()).commit()
    }

    fun loadProfile(): Profile {
        val raw = prefs.getString("profile", null) ?: return Defaults.profile.also { saveProfile(it) }
        return Profile.fromJson(JSONObject(raw))
    }

    fun saveProfile(p: Profile) {
        prefs.edit().putString("profile", p.toJson().toString()).apply()
    }

    fun loadHistory(): List<SentRecord> {
        val raw = prefs.getString("history", null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { SentRecord.fromJson(arr.getJSONObject(it)) }
    }

    fun saveHistory(list: List<SentRecord>) {
        prefs.edit().putString("history", JSONArray(list.map { it.toJson() }).toString()).commit()
    }

    var selectedCompanyId: String?
        get() = prefs.getString("selectedCompany", null)
        set(v) {
            prefs.edit().putString("selectedCompany", v).apply()
        }

    /** Deleted company ids -> when they were deleted, so deletions sync to other devices. */
    fun loadDeleted(): Map<String, Long> {
        val o = JSONObject(prefs.getString("deletedCompanies", null) ?: return emptyMap())
        return o.keys().asSequence().associateWith { o.getLong(it) }
    }

    fun saveDeleted(m: Map<String, Long>) {
        prefs.edit().putString("deletedCompanies", JSONObject(m).toString()).commit()
    }

    /** True once this device has completed a cloud sync. */
    var everSynced: Boolean
        get() = prefs.getBoolean("everSynced", false)
        set(v) {
            prefs.edit().putBoolean("everSynced", v).apply()
        }

    var lastSyncAt: Long
        get() = prefs.getLong("lastSyncAt", 0L)
        set(v) {
            prefs.edit().putLong("lastSyncAt", v).apply()
        }
}

/** Observable app state backed by [Store]. */
class AppState(private val store: Store) {
    /** Called after any local change that should be synced to the cloud. */
    var onChanged: (() -> Unit)? = null

    var companies by mutableStateOf(store.loadCompanies())
        private set
    var deleted by mutableStateOf(store.loadDeleted())
        private set
    var profile by mutableStateOf(store.loadProfile())
        private set
    var history by mutableStateOf(store.loadHistory())
        private set
    private var selectedId by mutableStateOf(store.selectedCompanyId)

    val selectedCompany: Company?
        get() = companies.find { it.id == selectedId } ?: companies.firstOrNull()

    fun select(id: String) {
        selectedId = id
        store.selectedCompanyId = id
    }

    fun upsertCompany(c: Company) {
        val touched = c.copy(updatedAt = System.currentTimeMillis())
        companies = if (companies.any { it.id == c.id }) {
            companies.map { if (it.id == c.id) touched else it }
        } else {
            companies + touched
        }
        store.saveCompanies(companies)
        onChanged?.invoke()
    }

    /**
     * Merges imported companies. A company matches an existing one by id, then short code, then name;
     * matches are updated (keeping the higher next-invoice number so numbers are never reused),
     * everything else is added. Returns (added, updated).
     */
    fun importCompanies(incoming: List<Company>): Pair<Int, Int> {
        val list = companies.toMutableList()
        var added = 0
        var updated = 0
        val now = System.currentTimeMillis()
        for (c in incoming.map { it.copy(updatedAt = now) }) {
            val idx = list.indexOfFirst { it.id == c.id }.takeIf { it >= 0 }
                ?: list.indexOfFirst { c.code.isNotBlank() && it.code.equals(c.code, ignoreCase = true) }
                    .takeIf { it >= 0 }
                ?: list.indexOfFirst { it.name.trim().equals(c.name.trim(), ignoreCase = true) }
                    .takeIf { it >= 0 }
            if (idx == null) {
                list += c
                added++
            } else {
                val cur = list[idx]
                list[idx] = c.copy(id = cur.id, nextInvoiceNo = maxOf(cur.nextInvoiceNo, c.nextInvoiceNo))
                updated++
            }
        }
        companies = list
        store.saveCompanies(list)
        onChanged?.invoke()
        return added to updated
    }

    fun deleteCompany(id: String) {
        companies = companies.filterNot { it.id == id }
        deleted = deleted + (id to System.currentTimeMillis())
        store.saveCompanies(companies)
        store.saveDeleted(deleted)
        onChanged?.invoke()
    }

    fun updateProfile(p: Profile) {
        // Only bump the timestamp when shared details change (not when signing in/out).
        val changedShared = p.shared() != profile.shared()
        profile = if (changedShared) p.copy(updatedAt = System.currentTimeMillis()) else p
        store.saveProfile(profile)
        if (changedShared) onChanged?.invoke()
    }

    /** Everything that is shared between devices. */
    fun snapshot() = SyncData(
        companies = companies,
        deleted = deleted,
        profile = profile.copy(googleAccount = ""),
        history = history,
    )

    /** True if this device has never synced and still only has the built-in defaults. */
    fun isPristine(): Boolean = !store.everSynced && history.isEmpty() &&
        companies.all { it.updatedAt == 0L } && profile.updatedAt == 0L

    /** Replaces local data with merged sync data (does not trigger [onChanged]). */
    fun applySync(d: SyncData) {
        companies = d.companies
        deleted = d.deleted
        profile = d.profile.copy(googleAccount = profile.googleAccount)
        history = d.history
        store.saveCompanies(companies)
        store.saveDeleted(deleted)
        store.saveProfile(profile)
        store.saveHistory(history)
    }

    fun markSynced(at: Long) {
        store.everSynced = true
        store.lastSyncAt = at
    }

    val lastSyncAt: Long get() = store.lastSyncAt

    fun markSent(d: InvoiceData) {
        companies.find { it.id == d.company.id }?.let { cur ->
            upsertCompany(cur.copy(nextInvoiceNo = maxOf(cur.nextInvoiceNo, d.invoiceNo + 1)))
        }
        val rec = SentRecord(
            company = d.company.code.ifBlank { d.company.name },
            invoiceNo = d.invoiceNo,
            client = d.client,
            total = d.total,
            date = d.date.format(LONG_DATE),
            to = d.company.email,
            sentAt = System.currentTimeMillis(),
        )
        history = (listOf(rec) + history).take(MAX_HISTORY)
        store.saveHistory(history)
        onChanged?.invoke()
    }
}
