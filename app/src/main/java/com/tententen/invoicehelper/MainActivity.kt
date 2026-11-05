@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.tententen.invoicehelper

import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        InvoicePdf.init(applicationContext)
        val state = AppState(Store(applicationContext))
        val auth = GoogleAuth(this)
        val sync = SyncManager(state, auth, lifecycleScope)
        // Pull changes from other devices whenever the app comes to the foreground.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { sync.sync(interactive = false) }
        }
        setContent { AppTheme { App(state, auth, sync) } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(state: AppState, auth: GoogleAuth, sync: SyncManager) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Email, null) }, label = { Text("Invoice") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text("Companies") },
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Person, null) }, label = { Text("Me") },
                )
            }
        },
    ) { inner ->
        val scope = rememberCoroutineScope()
        var refreshing by remember { mutableStateOf(false) }
        // Pull down from the top of any tab to sync with your other devices.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    if (state.profile.googleAccount.isBlank()) {
                        snackbar.showSnackbar("Sign in with Google on the Me tab to sync.")
                        return@launch
                    }
                    refreshing = true
                    val err = sync.sync(interactive = true)
                    refreshing = false
                    snackbar.showSnackbar(
                        err?.let { "Sync failed: $it" } ?: "Synced",
                        withDismissAction = err != null,
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            when (tab) {
                0 -> InvoiceScreen(state, auth, sync, snackbar)
                1 -> CompaniesScreen(state, snackbar)
                else -> ProfileScreen(state, auth, sync, snackbar)
            }
        }
    }
}

// ---------------------------------------------------------------- Invoice

@Composable
fun InvoiceScreen(state: AppState, auth: GoogleAuth, sync: SyncManager, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val company = state.selectedCompany

    var client by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var parking by rememberSaveable { mutableStateOf("") }
    var travel by rememberSaveable { mutableStateOf("") }
    // Extra line items as (description, amount text) pairs.
    val extras = rememberSaveable(saver = ExtrasSaver) { mutableStateListOf() }
    var dateDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var sending by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<InvoiceData?>(null) }
    val date = LocalDate.ofEpochDay(dateDay)

    fun build(): InvoiceData? = state.selectedCompany?.let {
        InvoiceData(
            company = it,
            profile = state.profile,
            invoiceNo = it.nextInvoiceNo,
            client = client.trim(),
            amount = amount.toMoney(),
            parking = parking.toMoney(),
            travel = travel.toMoney(),
            date = date,
            extras = extras
                .filter { (desc, amt) -> desc.isNotBlank() || amt.isNotBlank() }
                .map { (desc, amt) -> ExtraItem(desc.trim(), amt.toMoney()) },
        )
    }

    fun problem(d: InvoiceData?): String? = when {
        d == null -> "Add a company first (Companies tab)."
        d.client.isBlank() -> "Enter the client / event name."
        d.amount <= 0.0 -> "Enter the job amount."
        d.extras.any { it.name.isBlank() } -> "Enter a description for each extra item."
        d.extras.any { it.amount <= 0.0 } -> "Enter an amount for each extra item."
        d.lineItems().size > MAX_INVOICE_LINES -> "Too many lines: the invoice fits $MAX_INVOICE_LINES."
        d.company.email.isBlank() -> "${d.company.name} has no invoice email. Add one in Companies."
        d.profile.googleAccount.isBlank() -> "Sign in with Google in the Me tab first."
        else -> null
    }

    fun send(d: InvoiceData) {
        scope.launch {
            sending = true
            val result = runCatching {
                val token = auth.token(d.profile.googleAccount)
                withContext(Dispatchers.IO) {
                    val f = File(ctx.cacheDir, "sent/${d.fileName()}")
                    InvoicePdf.write(d, f)
                    val msg = Mailer.buildMessage(
                        d.profile.googleAccount, d.profile.name, d.company.email,
                        d.subject(), d.profile.emailBody, f,
                    )
                    GmailApi.send(token, msg)
                    f to token
                }
            }
            result.onSuccess { (f, token) ->
                // The email is out: record it before anything else can fail.
                state.markSent(d)
                client = ""; amount = ""; parking = ""; travel = ""
                extras.clear()
                dateDay = LocalDate.now().toEpochDay()
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        Exporter.saveToDownloads(ctx, f, d.company.code.ifBlank { "Other" })
                    }.isSuccess
                }
                // Keep a copy in Google Drive under Invoices/<company name>/.
                val driveNote = if (!auth.hasDriveFileScope) {
                    " Not saved to Drive: Drive permission wasn't allowed."
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching { DriveFiles.saveInvoice(token, d.company.name.ifBlank { d.company.code }, f) }
                    }.fold({ " Saved to Drive." }, { " Not saved to Drive: ${Mailer.friendlyError(it)}" })
                }
                sending = false
                snackbar.showSnackbar(
                    "Invoice #${d.invoiceNo} sent to ${d.company.email}." + driveNote +
                        if (saved) "" else " (Copy not saved to Downloads.)",
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
            }.onFailure {
                sending = false
                snackbar.showSnackbar(
                    "Not sent: ${Mailer.friendlyError(it)}",
                    withDismissAction = true,
                    duration = SnackbarDuration.Indefinite,
                )
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("New invoice", style = MaterialTheme.typography.headlineSmall)

        if (company == null) {
            Text("No companies yet. Add one in the Companies tab.")
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.companies.forEach { c ->
                    FilterChip(
                        selected = c.id == company.id,
                        onClick = { state.select(c.id) },
                        label = { Text(c.code.ifBlank { c.name }) },
                    )
                }
            }

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(company.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Invoice #${company.nextInvoiceNo}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (company.email.isBlank()) "No invoice email set" else "To: ${company.email}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            OutlinedTextField(
                value = client,
                onValueChange = { client = it },
                label = { Text("Client / event name") },
                placeholder = { Text("e.g. Brandon & Cherry Wedding") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            MoneyField("Job amount", amount, Modifier.fillMaxWidth()) { amount = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MoneyField("Parking (optional)", parking, Modifier.weight(1f)) { parking = it }
                MoneyField("Travel (optional)", travel, Modifier.weight(1f)) { travel = it }
            }

            extras.forEachIndexed { i, (desc, amt) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { extras[i] = it to extras[i].second },
                        label = { Text("Item") },
                        placeholder = { Text("e.g. Extra hour") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1.4f),
                    )
                    MoneyField("Amount", amt, Modifier.weight(1f)) { extras[i] = extras[i].first to it }
                    IconButton(onClick = { extras.removeAt(i) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove item")
                    }
                }
            }
            val usedLines = 1 + (if (parking.toMoney() > 0) 1 else 0) +
                (if (travel.toMoney() > 0) 1 else 0) + extras.size
            if (usedLines < MAX_INVOICE_LINES) {
                TextButton(onClick = { extras.add("" to "") }) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Add item")
                }
            }

            OutlinedButton(
                onClick = {
                    DatePickerDialog(
                        ctx,
                        { _, y, m, d -> dateDay = LocalDate.of(y, m + 1, d).toEpochDay() },
                        date.year, date.monthValue - 1, date.dayOfMonth,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.DateRange, null)
                Spacer(Modifier.padding(4.dp))
                Text("Date: ${date.format(LONG_DATE)}")
            }

            val preview = build()
            if (preview != null) {
                Text(
                    "Total: \$${money(preview.total)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (preview.client.isNotBlank()) {
                    Text(
                        "Subject: ${preview.subject()}\nAttachment: ${preview.fileName()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val d = build() ?: return@OutlinedButton
                        scope.launch {
                            val f = withContext(Dispatchers.IO) {
                                File(ctx.cacheDir, "preview/${d.fileName()}").also { InvoicePdf.write(d, it) }
                            }
                            if (!openPdf(ctx, f)) snackbar.showSnackbar("No PDF viewer app installed")
                        }
                    },
                    enabled = !sending,
                    modifier = Modifier.weight(1f),
                ) { Text("Preview") }
                Button(
                    onClick = {
                        val err0 = problem(build())
                        if (err0 != null) {
                            scope.launch { snackbar.showSnackbar(err0) }
                            return@Button
                        }
                        scope.launch {
                            // Get the latest invoice numbers from other devices before confirming.
                            checking = true
                            val syncErr = sync.sync(interactive = true)
                            checking = false
                            if (syncErr != null) {
                                launch {
                                    snackbar.showSnackbar(
                                        "Couldn't sync: $syncErr Using this device's invoice number.",
                                        withDismissAction = true,
                                    )
                                }
                            }
                            val d = build()
                            val err = problem(d)
                            if (err != null) snackbar.showSnackbar(err) else confirming = d
                        }
                    },
                    enabled = !sending && !checking,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when {
                            sending -> "Sending…"
                            checking -> "Checking…"
                            else -> "Send"
                        }
                    )
                }
            }
            if (sending) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        // Only the selected company's invoices.
        val recent = company?.let { c -> state.history.filter { it.isFor(c) } }.orEmpty()
        if (company != null) {
            HorizontalDivider(Modifier.padding(top = 8.dp))
            Text(
                "Recently sent to ${company.code.ifBlank { company.name }}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (recent.isEmpty()) {
                Text(
                    "No invoices sent to ${company.name} yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            recent.take(10).forEach { r ->
                Column {
                    Text("${r.company} #${r.invoiceNo} · ${r.client}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${r.date} · \$${money(r.total)} · ${r.to}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    confirming?.let { d ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Send invoice #${d.invoiceNo}?") },
            text = {
                Text(
                    "To: ${d.company.email}\n" +
                        "Subject: ${d.subject()}\n\n" +
                        d.lineItems().joinToString("\n") { (name, amt) -> "$name: \$${money(amt)}" } +
                        "\n\nTotal: \$${money(d.total)}"
                )
            },
            confirmButton = {
                TextButton(onClick = { confirming = null; send(d) }) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Cancel") }
            },
        )
    }
}

private val ExtrasSaver = listSaver<SnapshotStateList<Pair<String, String>>, String>(
    save = { list -> list.flatMap { listOf(it.first, it.second) } },
    restore = { flat ->
        mutableStateListOf<Pair<String, String>>().apply { addAll(flat.chunked(2).map { it[0] to it[1] }) }
    },
)

private val SYNC_TIME = java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a", java.util.Locale.ENGLISH)

fun formatTime(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(SYNC_TIME)

@Composable
fun MoneyField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { s ->
            val cleaned = s.filter { it.isDigit() || it == '.' }
            if (cleaned.count { it == '.' } <= 1) onChange(cleaned)
        },
        label = { Text(label) },
        prefix = { Text("$") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

fun openPdf(ctx: Context, file: File): Boolean {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/pdf")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        ctx.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

// ---------------------------------------------------------------- Companies

@Composable
fun CompaniesScreen(state: AppState, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Company?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri, "wt")!!.use {
                        it.write(CompanyTransfer.export(state.companies).toByteArray())
                    }
                }
            }
            snackbar.showSnackbar(
                r.fold(
                    { "Exported ${state.companies.size} companies" },
                    { "Export failed: ${it.message ?: it.javaClass.simpleName}" },
                ),
                withDismissAction = true,
            )
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = runCatching {
                val text = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                state.importCompanies(CompanyTransfer.parse(text))
            }
            snackbar.showSnackbar(
                r.fold(
                    { (added, updated) -> "Imported: $added added, $updated updated" },
                    { "Import failed: ${it.message ?: it.javaClass.simpleName}" },
                ),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
        }
    }
    BackHandler(enabled = editing != null) { editing = null }

    val e = editing
    if (e != null) {
        CompanyEditor(
            initial = e,
            isNew = state.companies.none { it.id == e.id },
            onSave = { state.upsertCompany(it); editing = null },
            onDelete = { state.deleteCompany(e.id); editing = null },
            onCancel = { editing = null },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Companies", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                }) { Text("Import") }
                TextButton(
                    onClick = { exportLauncher.launch("invoice-helper-companies-${LocalDate.now()}.json") },
                    enabled = state.companies.isNotEmpty(),
                ) { Text("Export") }
            }
            if (state.companies.isEmpty()) Text("No companies yet. Tap Add company.")
            state.companies.forEach { c ->
                Card(onClick = { editing = c }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(c.name.ifBlank { "(no name)" }, style = MaterialTheme.typography.titleMedium)
                        Text(c.email.ifBlank { "No invoice email" }, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Code ${c.code.ifBlank { "-" }} · next invoice #${c.nextInvoiceNo}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
        ExtendedFloatingActionButton(
            onClick = { editing = Company() },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Add company") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

@Composable
fun CompanyEditor(
    initial: Company,
    isNew: Boolean,
    onSave: (Company) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var c by remember(initial.id) { mutableStateOf(initial) }
    var nextNo by remember(initial.id) { mutableStateOf(initial.nextInvoiceNo.toString()) }
    var askDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (isNew) "New company" else "Edit company", style = MaterialTheme.typography.headlineSmall)
        Field("Company name", c.name) { c = c.copy(name = it) }
        Field("Short code for file names (e.g. MEC)", c.code, caps = KeyboardCapitalization.Characters) {
            c = c.copy(code = it.trim().uppercase())
        }
        Field(
            "Invoice email (where invoices are sent)", c.email,
            type = KeyboardType.Email, caps = KeyboardCapitalization.None,
        ) { c = c.copy(email = it.trim()) }
        Text(
            "Separate multiple addresses with commas.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field("Address line 1", c.address1) { c = c.copy(address1 = it) }
        Field("Address line 2 (suburb)", c.address2) { c = c.copy(address2 = it) }
        Field("Address line 3 (state & postcode)", c.address3, caps = KeyboardCapitalization.Characters) {
            c = c.copy(address3 = it)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Phone label", c.phoneLabel, Modifier.weight(0.4f)) { c = c.copy(phoneLabel = it) }
            Field("Phone", c.phone, Modifier.weight(0.6f), type = KeyboardType.Phone) { c = c.copy(phone = it) }
        }
        Field("Website", c.web, type = KeyboardType.Uri, caps = KeyboardCapitalization.None) {
            c = c.copy(web = it.trim())
        }
        Field("Next invoice number", nextNo, type = KeyboardType.Number) { nextNo = it.filter(Char::isDigit) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isNew) {
                TextButton(onClick = { askDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = {
                    onSave(
                        c.copy(
                            name = c.name.trim(),
                            nextInvoiceNo = nextNo.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        )
                    )
                },
                enabled = c.name.isNotBlank(),
            ) { Text("Save") }
        }
    }

    if (askDelete) {
        AlertDialog(
            onDismissRequest = { askDelete = false },
            title = { Text("Delete ${c.name}?") },
            text = { Text("This removes the company profile and its invoice counter.") },
            confirmButton = { TextButton(onClick = { askDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    type: KeyboardType = KeyboardType.Text,
    caps: KeyboardCapitalization = KeyboardCapitalization.Words,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(capitalization = caps, keyboardType = type),
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- Me / settings

@Composable
fun ProfileScreen(state: AppState, auth: GoogleAuth, sync: SyncManager, snackbar: SnackbarHostState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = state.profile
    fun set(np: Profile) = state.updateProfile(np)
    var busy by remember { mutableStateOf(false) }

    fun signIn() {
        scope.launch {
            busy = true
            val r = runCatching {
                val token = auth.token(null)
                withContext(Dispatchers.IO) { GmailApi.userEmail(token) }
            }
            busy = false
            r.onSuccess { email ->
                state.updateProfile(state.profile.copy(googleAccount = email))
                val syncErr = sync.sync(interactive = true)
                snackbar.showSnackbar(
                    if (syncErr == null) "Signed in as $email and synced" else "Signed in as $email. Sync failed: $syncErr",
                    withDismissAction = true,
                )
            }.onFailure {
                snackbar.showSnackbar(Mailer.friendlyError(it), withDismissAction = true, duration = SnackbarDuration.Long)
            }
        }
    }

    fun sendTest() {
        val acct = p.googleAccount
        scope.launch {
            busy = true
            val r = runCatching {
                val token = auth.token(acct)
                withContext(Dispatchers.IO) {
                    GmailApi.send(
                        token,
                        Mailer.buildMessage(acct, p.name, acct, "Invoice Helper test", "Gmail sending is set up correctly.", null),
                    )
                }
            }
            busy = false
            snackbar.showSnackbar(
                r.fold({ "Test email sent to $acct" }, { "Test failed: ${Mailer.friendlyError(it)}" }),
                withDismissAction = true,
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("My details", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Shown in the From section of every invoice. Changes save automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field("Name", p.name) { set(p.copy(name = it)) }
        Field("Address line 1", p.address1) { set(p.copy(address1 = it)) }
        Field("Address line 2 (suburb)", p.address2) { set(p.copy(address2 = it)) }
        Field("Address line 3 (state & postcode)", p.address3, caps = KeyboardCapitalization.Characters) {
            set(p.copy(address3 = it))
        }
        Field("Mobile", p.mobile, type = KeyboardType.Phone) { set(p.copy(mobile = it)) }
        Field("Email shown on invoice", p.email, type = KeyboardType.Email, caps = KeyboardCapitalization.None) {
            set(p.copy(email = it.trim()))
        }
        Field("ABN", p.abn, type = KeyboardType.Number) { set(p.copy(abn = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("BSB", p.bsb, Modifier.weight(1f), type = KeyboardType.Number) { set(p.copy(bsb = it)) }
            Field("Account", p.acc, Modifier.weight(1f), type = KeyboardType.Number) { set(p.copy(acc = it)) }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Gmail sending", style = MaterialTheme.typography.titleLarge)
        if (p.googleAccount.isBlank()) {
            Text(
                "Sign in with the Google account you send invoices from. " +
                    "Invoices go out through Gmail and appear in your Sent folder.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { signIn() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Signing in…" else "Sign in with Google")
            }
        } else {
            Text("Sending as", style = MaterialTheme.typography.bodySmall)
            Text(p.googleAccount, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { sendTest() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "Sending…" else "Send test email")
                }
                TextButton(onClick = { set(p.copy(googleAccount = "")) }, enabled = !busy) {
                    Text("Sign out")
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Field(
            "Email message (optional)", p.emailBody,
            caps = KeyboardCapitalization.Sentences, singleLine = false,
        ) { set(p.copy(emailBody = it)) }
        if (p.googleAccount.isNotBlank()) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Cloud sync", style = MaterialTheme.typography.titleLarge)
            Text(
                "Companies, invoice numbers, your details and sent history sync between your devices " +
                    "through ${p.googleAccount}'s Google Drive (a hidden app folder).",
                style = MaterialTheme.typography.bodySmall,
            )
            val failed = sync.lastError != null && !sync.syncing
            Text(
                when {
                    sync.syncing -> "Syncing…"
                    failed -> "Last sync failed: ${sync.lastError}"
                    sync.lastSyncAt > 0 -> "Last synced ${formatTime(sync.lastSyncAt)}"
                    else -> "Not synced yet"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val e = sync.sync(interactive = true)
                        snackbar.showSnackbar(e?.let { "Sync failed: $it" } ?: "Synced", withDismissAction = true)
                    }
                },
                enabled = !sync.syncing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sync now") }
        }
        Spacer(Modifier.height(24.dp))
    }
}
