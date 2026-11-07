package com.tententen.invoicehelper

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One MEC email behind a job. [subject]/[sentAt] are null when this device hasn't read it. */
data class JobEmail(val messageId: String, val kind: MailKind, val subject: String?, val sentAt: Long?) {
    val label: String
        get() = when (kind) {
            MailKind.JOBSHEET -> "Job sheet"
            MailKind.CONFIRMED -> "Booking confirmation"
            MailKind.INTRO -> "E-introduction"
            MailKind.ZOHO -> "Monthly bookings report"
            MailKind.INVOICED -> "Invoiced notice"
            MailKind.HOLD -> "On hold confirmation"
            MailKind.CANCELLED -> "Cancellation"
            MailKind.RELATED, MailKind.OTHER -> subject ?: "Related email"
        }

    /** "Sent 3 Oct 2026" (or "" when unknown). */
    val sent: String
        get() = sentAt?.takeIf { it > 0 }?.let { "Sent " + sentText(it) }.orEmpty()
}

private val SENT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

private fun sentText(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.of(MEL_TZ)).format(SENT_FMT).replace("AM", "am").replace("PM", "pm")

private val OFFICE_TYPES = mapOf(
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "doc" to "application/msword",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "xls" to "application/vnd.ms-excel",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "pdf" to "application/pdf",
)

/** The type to open an attachment as: from its file extension, else what the email said, else anything. */
fun attachmentMime(fileName: String, emailType: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return OFFICE_TYPES[ext]
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: emailType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: "*/*"
}

/** The email the main button opens: job sheet first, then the confirmation, then the newest. */
fun List<JobEmail>.primary(): JobEmail? =
    firstOrNull { it.kind == MailKind.JOBSHEET } ?: firstOrNull { it.kind == MailKind.CONFIRMED } ?: firstOrNull()

/**
 * Gmail's Android app doesn't let other apps open a particular email (its message deep links only accept
 * Google-signed callers, and mail.google.com links land on the inbox), so emails are shown in the app.
 */
object GmailApp {
    private const val PACKAGE = "com.google.android.gm"

    /** Opens the Gmail app (or Gmail in the browser). */
    fun open(ctx: Context) {
        // Start Gmail's launcher screen directly (a package lookup needs <queries> and returns null without it).
        val app = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(ctx, app)) openUrl(ctx, "https://mail.google.com/")
    }

    /** Starts a reply to [from] ("Name <a@b.c>") in Gmail, or any mail app. */
    fun reply(ctx: Context, from: String, subject: String): Boolean {
        val to = Regex("""<([^>]+)>""").find(from)?.groupValues?.get(1) ?: from.trim()
        val re = if (subject.startsWith("re:", ignoreCase = true)) subject else "Re: $subject"
        val uri = Uri.parse("mailto:" + Uri.encode(to) + "?subject=" + Uri.encode(re))
        val intent = Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(ctx, Intent(intent).setPackage(PACKAGE)) || start(ctx, intent)
    }

    private fun start(ctx: Context, i: Intent) = try {
        ctx.startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Shows one Gmail message: sender, date, attachments and the email itself. */
@Composable
fun EmailScreen(
    messageId: String,
    title: String,
    jobs: JobSyncManager,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mail by remember(messageId) { mutableStateOf<GmailReader.Full?>(null) }
    var error by remember(messageId) { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    LaunchedEffect(messageId) {
        jobs.loadEmail(messageId).onSuccess { mail = it }.onFailure { error = Mailer.friendlyError(it) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        val m = mail
        when {
            error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            m == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(m.meta.subject, style = MaterialTheme.typography.titleLarge)
                    Text(
                        m.meta.from.replace("\"", "") + " · " + sentText(m.meta.sentAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (m.attachments.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            m.attachments.forEach { a ->
                                AssistChip(
                                    onClick = {
                                        if (opening == null) scope.launch {
                                            opening = a.attachmentId
                                            jobs.openAttachment(ctx, a)?.let { snackbar.showSnackbar(it) }
                                            opening = null
                                        }
                                    },
                                    label = { Text(a.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                                    leadingIcon = {
                                        if (opening == a.attachmentId) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.Default.Email, null, Modifier.size(16.dp))
                                    },
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            if (!GmailApp.reply(ctx, m.meta.from, m.meta.subject)) scope.launch { snackbar.showSnackbar("No email app found.") }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Reply in Gmail")
                        }
                        OutlinedButton(onClick = { GmailApp.open(ctx) }) { Text("Open Gmail") }
                    }
                }
                EmailBody(emailHtml(m), Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
            }
        }
    }
}

/** The email's HTML (or its plain text), ready for a WebView. */
private fun emailHtml(m: GmailReader.Full): String {
    val body = m.html ?: ("<pre style=\"white-space:pre-wrap;font-family:sans-serif\">" +
        (m.text.orEmpty()).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</pre>")
    return "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>body{margin:12px;background:#fff;color:#202124;word-wrap:break-word}img{max-width:100%;height:auto}</style>" + body
}

@Composable
private fun EmailBody(html: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    // Links (Spotify, Maps, forms...) open in their own apps.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        openUrl(view.context, request.url.toString())
                        return true
                    }
                }
            }
        },
        update = { it.loadDataWithBaseURL("https://mail.google.com/", html, "text/html", "utf-8", null) },
    )
}
