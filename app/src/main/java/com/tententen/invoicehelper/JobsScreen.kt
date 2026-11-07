package com.tententen.invoicehelper

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pre-fills the Invoice tab from a job. */
data class InvoicePrefill(val client: String, val amount: String, val companyCode: String = "MEC")

private val MONTH_FMT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val CARD_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

private fun today(): LocalDate = LocalDate.now(ZoneId.of(MEL_TZ))

fun openUrl(ctx: Context, url: String): Boolean = try {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
}

private fun dial(ctx: Context, phone: String) {
    JobEvent.telUri(phone)?.let { tel ->
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(tel))) }
    }
}

/** "🎧 Name (Type)" -> "Name (Type)". */
private fun CalEvent.cleanTitle() =
    when {
        onHold -> "On hold for MEC"
        unconfirmed -> "MEC unconfirmed"
        else -> title.removePrefix("🎧").removePrefix("CANCELLED ·").trim()
    }

/** "Arrive 5pm · 6:30pm – 11pm" (or the event's own times for non-MEC events). */
private fun CalEvent.timeLine(): String {
    if (onHold) return "Keep the night free" + (this[F.PAY]?.let { " · hold pay $it" } ?: "")
    if (unconfirmed) return this[F.NOTES] ?: "Waiting on MEC to confirm"
    if (isJob) {
        val arrive = JobEvent.clock(this[F.ARRIVE])
        val start = JobEvent.clock(this[F.START])
        val end = JobEvent.clock(this[F.END])
        val parts = listOfNotNull(
            arrive?.let { "Arrive $it" },
            start?.let { "$it – ${end ?: "?"}" } ?: this[F.TIME_TEXT],
        )
        if (parts.isNotEmpty()) return parts.joinToString(" · ")
    }
    val fmt = DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH)
    return if (start != null && end != null) {
        "${start.format(fmt).lowercase()} – ${end.format(fmt).lowercase()}"
    } else {
        "All day"
    }
}

/** What to put on an invoice: "Jaden Christensen | Jaden + Kiara" -> "Jaden + Kiara". */
fun invoiceClient(name: String?): String {
    val parts = name.orEmpty().split('|').map { it.trim() }.filter { it.isNotEmpty() }
    return if (parts.size >= 2) parts.drop(1).joinToString(" ") else parts.firstOrNull().orEmpty()
}

fun invoiceAmount(pay: String?): String =
    pay?.let { Regex("""[\d,]+(?:\.\d{1,2})?""").find(it)?.value?.replace(",", "") }.orEmpty()

@Composable
fun JobsScreen(
    state: AppState,
    jobs: JobSyncManager,
    snackbar: SnackbarHostState,
    onInvoice: (InvoicePrefill) -> Unit,
) {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var addDay by rememberSaveable { mutableStateOf(today().toString()) }
    var addNote by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today()).toString()) }
    var dayText by rememberSaveable { mutableStateOf<String?>(null) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.parse(monthText)
    val day = dayText?.let { LocalDate.parse(it) }
    val events = jobs.events

    val open = openId?.let { id -> events.firstOrNull { it.id == id } }
    var mail by remember { mutableStateOf<JobEmail?>(null) }
    // Kept here so coming back from an email returns to the same place on the job.
    val detailScroll = remember(openId) { ScrollState(0) }
    BackHandler(enabled = open != null) { openId = null }
    val m = mail
    if (open != null && m != null) {
        EmailScreen(m.messageId, m.label, jobs, snackbar) { mail = null }
        return
    }
    if (open != null) {
        JobDetail(
            scroll = detailScroll,
            open, onBack = { openId = null }, onInvoice = onInvoice,
            onRemoveUnconfirmed = {
                scope.launch {
                    val err = jobs.removeUnconfirmed(open)
                    if (err == null) openId = null
                    snackbar.showSnackbar(err ?: "Removed MEC unconfirmed for ${open.date.format(CARD_DAY)}")
                }
            },
            emails = remember(open) { jobs.emailsFor(open) },
            onOpenEmail = { mail = it },
        )
        return
    }

    if (adding) {
        val d = LocalDate.parse(addDay)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add MEC unconfirmed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Pencils the date into your calendar while you wait on MEC. It's replaced automatically " +
                            "when MEC confirms a gig that night.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = {
                            android.app.DatePickerDialog(
                                ctx, { _, y, m, day -> addDay = LocalDate.of(y, m + 1, day).toString() },
                                d.year, d.monthValue - 1, d.dayOfMonth,
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.DateRange, null)
                        Spacer(Modifier.width(8.dp))
                        Text(d.format(JobEvent.LONG_DAY))
                    }
                    OutlinedTextField(
                        value = addNote,
                        onValueChange = { addNote = it },
                        label = { Text("Note (optional)") },
                        placeholder = { Text("e.g. Wedding enquiry, Yarra Valley") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    adding = false
                    scope.launch {
                        val err = jobs.addUnconfirmed(d, addNote)
                        if (err == null) addNote = ""
                        snackbar.showSnackbar(err ?: "Added MEC unconfirmed for ${d.format(CARD_DAY)}")
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Jobs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (state.profile.googleAccount.isNotBlank()) {
                TextButton(onClick = { adding = true }, enabled = !jobs.syncing) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(4.dp))
                    Text("MEC unconfirmed")
                }
            }
        }

        if (state.profile.googleAccount.isBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Sign in with Google on the Me tab. Your confirmed MEC jobs are then read from Gmail " +
                        "and kept in a \"$JOBS_CALENDAR\" calendar in Google Calendar.",
                    Modifier.padding(16.dp),
                )
            }
            return@Column
        }

        // Removal lock: nothing leaves your calendar without your OK.
        if (jobs.pendingRemovals.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Needs your OK", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your calendar is locked: these entries stay until you approve removing them.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    jobs.pendingRemovals.forEach { r ->
                        HorizontalDivider()
                        Text(
                            r.date.format(CARD_DAY) + " · " + r.title.removePrefix("🎧").trim(),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(r.reason, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        val err = jobs.approveRemoval(r)
                                        snackbar.showSnackbar(
                                            err ?: if (r.action == Removal.CANCEL) "Marked cancelled" else "Removed from calendar",
                                        )
                                    }
                                },
                                enabled = !jobs.syncing,
                            ) { Text(if (r.action == Removal.CANCEL) "Mark cancelled" else "Remove") }
                            OutlinedButton(onClick = { jobs.keep(r) }, enabled = !jobs.syncing) { Text("Keep") }
                        }
                    }
                }
            }
        }

        val failed = jobs.lastError != null && !jobs.syncing
        when {
            jobs.syncing -> {
                Text(jobs.progress ?: "Updating…", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            failed || jobs.lastSyncAt == 0L -> {
                if (failed) {
                    Text(
                        "Couldn't update jobs: ${jobs.lastError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    onClick = { scope.launch { jobs.sync(interactive = true) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (jobs.lastSyncAt == 0L) "Connect Gmail & Calendar" else "Try again") }
            }
            else -> Text(
                "From your MEC emails · updated ${formatTime(jobs.lastSyncAt)} · pull down to refresh",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val calColor = GColors.parse(jobs.calendarColor) ?: GColors.Tomato
        GoogleMonth(
            month = month,
            events = events,
            calendarColor = calColor,
            selected = day,
            onDay = { d -> dayText = if (d == day) null else d.toString() },
            onMonth = { monthText = it.toString(); dayText = null },
        )

        val list = if (day != null) events.filter { it.date == day } else events.filter { !it.date.isBefore(today()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                day?.let { dayHeading(it) } ?: "Upcoming",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (day != null) TextButton(onClick = { dayText = null }) { Text("Show upcoming") }
        }
        if (list.isEmpty()) {
            Text(
                when {
                    day != null -> "Nothing on this day."
                    jobs.lastSyncAt == 0L -> "Jobs appear here after the first update."
                    else -> "No upcoming jobs."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GoogleSchedule(
            events = list,
            calendarColor = calColor,
            onOpen = { openId = it.id },
            timeLine = { e ->
                when {
                    e.cancelled -> "Cancelled"
                    else -> e.timeLine()
                }
            },
            whereLine = { e ->
                when {
                    e.onHold -> e[F.INVOICED]?.let { "Likely: $it (invoiced)" }.orEmpty()
                    e["notInReport"] == "1" -> "⚠️ Not in MEC's latest monthly report"
                    else -> e["location"] ?: e.location
                }
            },
            title = { e -> e.cleanTitle() },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun JobDetail(
    scroll: ScrollState,
    e: CalEvent,
    onBack: () -> Unit,
    onInvoice: (InvoicePrefill) -> Unit,
    onRemoveUnconfirmed: () -> Unit,
    emails: List<JobEmail>,
    onOpenEmail: (JobEmail) -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(e.date.format(JobEvent.LONG_DAY), style = MaterialTheme.typography.titleMedium, color = colors.primary)
        }
        Text(e.cleanTitle(), style = MaterialTheme.typography.headlineSmall)

        if (e.cancelled) {
            Banner(
                "This booking was cancelled." + (e[F.CANCEL_PAY]?.let { " 50% cancellation pay: $it" } ?: ""),
                colors.errorContainer,
            )
        }
        if (e["notInReport"] == "1") {
            Banner("Not in MEC's latest monthly bookings report. Check with MEC that it's still on.", colors.errorContainer)
        }
        if (e.onHold) {
            Banner(
                "MEC has you on hold this night. Keep it free with your gear and outfit ready: they may assign " +
                    "you a gig or call on the day." +
                    (e[F.PAY]?.let { " If you're not used, invoice the hold pay ($it) the Sunday after." } ?: ""),
                colors.tertiaryContainer,
            )
            e[F.INVOICED]?.let { n ->
                Labeled("Likely gig", n + (e[F.INVOICED_VENUE]?.let { " at $it" } ?: "") + " (invoiced, awaiting the client's payment)")
            }
        }

        // Times
        if (e.unconfirmed) {
            Banner(
                "You added this while waiting on MEC. It's replaced automatically when MEC confirms a gig on this date.",
                colors.surfaceVariant,
            )
            e[F.NOTES]?.let { Labeled("Note", it) }
            OutlinedButton(onClick = onRemoveUnconfirmed, modifier = Modifier.fillMaxWidth()) {
                Text("Remove MEC unconfirmed")
            }
        }
        if (!e.onHold && !e.unconfirmed) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (e.isJob) {
                    JobEvent.clock(e[F.ARRIVE])?.let { Labeled("Arrive", it, big = true) }
                    val start = JobEvent.clock(e[F.START])
                    if (start != null) Labeled("Start", start, big = true)
                    JobEvent.clock(e[F.END])?.let { Labeled("Finish", it, big = true) }
                    if (start == null) e[F.TIME_TEXT]?.let { Labeled("Time", it) }
                } else {
                    Text(e.timeLine(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        // Venue
        val where = e["location"] ?: e.location
        if (where.isNotBlank()) {
            Section("Venue")
            Text(where, style = MaterialTheme.typography.bodyLarge)
            val maps = e["maps"] ?: JobEvent.mapsUrl(where)
            if (maps != null) {
                FilledTonalButton(onClick = { openUrl(ctx, maps) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Place, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Directions in Google Maps")
                }
            }
            e[F.PARKING]?.let { Labeled("Parking & bump-in", it) }
            e[F.VENUE_INFO]?.let { Labeled("Venue info", it) }
        }

        // Contacts
        val contacts = listOfNotNull(
            e[F.CLIENT_PHONE]?.let { Triple("Client", listOfNotNull(e[F.CLIENT], e[F.PARTNER]).joinToString(" & "), it) },
            e[F.SECOND_PHONE]?.let { Triple("Second phone", "", it) },
            e[F.OTHER_PHONE]?.let { Triple("Other phone", "", it) },
            e[F.VENUE_PHONE]?.let { Triple("Venue", e[F.VENUE_CONTACT].orEmpty(), it) },
            e[F.MEC_CONTACT]?.let { mec ->
                val phone = Regex("""[+\d][\d ()-]{7,}""").find(mec)?.value?.trim()
                phone?.let { Triple("MEC", mec.substringBefore('|').trim(), it) }
            },
        )
        if (contacts.isNotEmpty()) {
            Section("Contacts")
            contacts.forEach { (label, name, phone) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        Text(listOf(name, phone).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyLarge)
                    }
                    FilledTonalIconButton(onClick = { dial(ctx, phone) }) { Icon(Icons.Default.Phone, "Call $label") }
                }
            }
        }

        // Music
        val spotify = e["spotify"]?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (spotify.isNotEmpty()) {
            Section("Music")
            spotify.forEachIndexed { i, url ->
                LinkButton(
                    Icons.Default.PlayArrow,
                    if (url.contains("/track/")) "Spotify song ${i + 1}" else "Spotify playlist ${i + 1}",
                ) { openUrl(ctx, url) }
            }
        }

        // Files
        val links = e["links"]?.lines()?.filter { it.isNotBlank() }.orEmpty()
        if (e.files.isNotEmpty() || links.isNotEmpty()) {
            Section("Job sheet files")
            e.files.forEach { (name, url) -> LinkButton(Icons.AutoMirrored.Filled.List, name) { openUrl(ctx, url) } }
            links.forEachIndexed { i, url -> LinkButton(Icons.AutoMirrored.Filled.List, "Shared file ${i + 1}") { openUrl(ctx, url) } }
        }

        // Details
        if (e.isJob && !e.unconfirmed) {
            val details = listOf(
                "Type" to e[F.TYPE], "Guests" to e[F.GUESTS], "Dress" to e[F.DRESS], "Extras" to e[F.EXTRAS],
                "Notes" to e[F.NOTES], "Pay" to e[F.PAY], "Pay breakdown" to e[F.PAY_INFO], "Equipment" to e[F.EQUIPMENT],
            ).filter { it.second != null }
            if (details.isNotEmpty()) {
                Section("Details")
                details.forEach { (k, v) -> Labeled(k, v!!) }
            }
        } else if (!e.isJob && e.description.isNotBlank()) {
            Section("Details")
            Text(JobParser.cells(e.description).joinToString("\n"), style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider()
        if (e.isJob && !e.cancelled && !e.unconfirmed) {
            val amount = invoiceAmount(e[F.PAY])
            Button(
                onClick = {
                    val client = if (e.onHold) "On hold ${e.date.format(SHORT_DATE)}" else invoiceClient(e[F.NAME])
                    onInvoice(InvoicePrefill(client, amount))
                },
                enabled = amount.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Create, null)
                Spacer(Modifier.width(8.dp))
                Text(if (amount.isNotBlank()) "Autofill invoice · $$amount" else "Autofill invoice")
            }
            Text(
                if (amount.isNotBlank()) "Fills in MEC, the client and the pay. Add parking yourself."
                else "No pay found in MEC's emails for this job yet.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        emails.primary()?.let { main -> EmailButton(main, emails - main, onOpenEmail) }
        if (e.htmlLink.isNotBlank()) {
            OutlinedButton(onClick = { openUrl(ctx, e.htmlLink) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.DateRange, null)
                Spacer(Modifier.width(8.dp))
                Text("Open in Google Calendar")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** "Open job sheet email" with a ▾ beside it listing the job's other emails. */
@Composable
private fun EmailButton(main: JobEmail, others: List<JobEmail>, onOpen: (JobEmail) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onOpen(main) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Email, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (main.kind == MailKind.RELATED || main.kind == MailKind.OTHER) "Open email" else "Open ${main.label.lowercase()} email",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (others.isNotEmpty()) {
            Box {
                OutlinedIconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.ArrowDropDown, "Other emails for this job")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Text(
                        "Other emails for this job",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    others.forEach { m ->
                        DropdownMenuItem(
                            leadingIcon = { Icon(Icons.Default.Email, null) },
                            text = {
                                Column {
                                    Text(m.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val sub = listOf(m.sent, m.subject?.takeIf { it != m.label }.orEmpty())
                                        .filter { it.isNotBlank() }.joinToString(" · ")
                                    if (sub.isNotBlank()) {
                                        Text(
                                            sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            onClick = { menu = false; onOpen(m) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Labeled(label: String, value: String, big: Boolean = false) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(110.dp)
                .padding(top = if (big) 4.dp else 1.dp),
        )
        Text(
            value,
            style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
            fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Banner(text: String, color: androidx.compose.ui.graphics.Color) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LinkButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}
