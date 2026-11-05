package com.tententen.invoicehelper

import android.accounts.Account
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Date
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

class SignInCancelledException : Exception("Google sign-in was cancelled")
class MissingScopeException : Exception("Gmail send permission not granted")
/** Background (non-interactive) call needed Google's permission screen. */
class ConsentNeededException : Exception("Google permission needed")
class DriveScopeMissingException : Exception("Google Drive permission not granted")
class ApiHttpException(val service: String, val code: Int, val detail: String) :
    Exception("$service error $code: $detail")

/**
 * "Sign in with Google" via Google Play services. Gets OAuth access tokens for the
 * gmail.send scope; shows Google's account picker / consent screen only when needed.
 * Must be constructed in Activity.onCreate (registers an activity-result launcher).
 */
class GoogleAuth(private val activity: ComponentActivity) {
    companion object {
        const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        /** Only files/folders this app creates (the Invoices/<company>/ copies). */
        const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        /** Reading MEC job emails. */
        const val GMAIL_READ = "https://www.googleapis.com/auth/gmail.readonly"
        /** Writing jobs to the "DJ Jobs" calendar. */
        const val CALENDAR = "https://www.googleapis.com/auth/calendar"
        private val SCOPES = listOf(
            Scope(GMAIL_SEND), Scope("email"), Scope(DRIVE_APPDATA), Scope(DRIVE_FILE), Scope(GMAIL_READ), Scope(CALENDAR),
        )
    }

    private var pending: CompletableDeferred<AuthorizationResult>? = null

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val d = pending
        pending = null
        if (res.resultCode == Activity.RESULT_OK) {
            runCatching { Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(res.data) }
                .fold({ d?.complete(it) }, { d?.completeExceptionally(it) })
        } else {
            d?.completeExceptionally(SignInCancelledException())
        }
    }

    /** Whether the last token obtained included Drive app-data access (needed for sync). */
    var hasDriveScope = false
        private set

    /** Whether the last token could create files in Drive (needed to save invoice copies). */
    var hasDriveFileScope = false
        private set

    /** Whether the last token can read Gmail and write the calendar (needed for the Jobs calendar). */
    var hasJobScopes = false
        private set

    /**
     * Returns a valid access token. If Google needs to show its account/permission screen, it does so
     * when [interactive], otherwise throws [ConsentNeededException].
     */
    suspend fun token(account: String?, interactive: Boolean = true): String {
        val req = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES)
            .apply { if (!account.isNullOrBlank()) setAccount(Account(account, "com.google")) }
            .build()
        var result = Identity.getAuthorizationClient(activity).authorize(req).await()
        if (result.hasResolution()) {
            if (!interactive) throw ConsentNeededException()
            val d = CompletableDeferred<AuthorizationResult>()
            pending = d
            launcher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())
            result = d.await()
        }
        if (result.grantedScopes.none { it.endsWith("gmail.send") }) throw MissingScopeException()
        hasDriveScope = result.grantedScopes.any { it.endsWith("drive.appdata") }
        hasDriveFileScope = result.grantedScopes.any { it.endsWith("drive.file") }
        hasJobScopes = result.grantedScopes.any { it.endsWith("gmail.readonly") } &&
            result.grantedScopes.any { it.endsWith("auth/calendar") }
        return result.accessToken ?: error("Google didn't return an access token")
    }
}

/** Tiny authenticated HTTP helper for Google REST APIs. Call off the main thread. */
object Http {
    fun request(
        url: String,
        token: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        service: String,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                val detail = runCatching { JSONObject(err).getJSONObject("error").getString("message") }
                    .getOrDefault(err.take(300))
                throw ApiHttpException(service, code, detail)
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}

/** Minimal Gmail REST client (users.messages.send) plus a userinfo lookup. Call off the main thread. */
object GmailApi {
    fun send(token: String, msg: MimeMessage) {
        val bytes = ByteArrayOutputStream().also { msg.writeTo(it) }.toByteArray()
        val raw = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        Http.request(
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send", token, "POST",
            JSONObject().put("raw", raw).toString().toByteArray(), "application/json; charset=UTF-8", "Gmail",
        )
    }

    fun userEmail(token: String): String = JSONObject(
        Http.request("https://www.googleapis.com/oauth2/v3/userinfo", token, "GET", null, null, "Google")
    ).getString("email")
}

object Mailer {
    fun buildMessage(
        fromEmail: String,
        fromName: String,
        to: String,
        subject: String,
        body: String,
        attachment: File?,
    ): MimeMessage = MimeMessage(Session.getInstance(Properties())).apply {
        setFrom(InternetAddress(fromEmail, fromName.ifBlank { fromEmail }, "UTF-8"))
        setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false))
        setSubject(subject, "UTF-8")
        sentDate = Date()
        val parts = MimeMultipart()
        parts.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
        if (attachment != null) {
            parts.addBodyPart(MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(attachment.readBytes(), "application/pdf"))
                fileName = attachment.name
            })
        }
        setContent(parts)
        saveChanges()
    }

    fun friendlyError(t: Throwable): String {
        var e: Throwable? = t
        while (e != null) {
            when (e) {
                is SignInCancelledException -> return "Google sign-in was cancelled."
                is MissingScopeException ->
                    return "Permission to send email wasn't granted. Sign in again and allow sending email."
                is ConsentNeededException ->
                    return "Google needs your permission again. Tap Sync now on the Me tab."
                is JobScopeMissingException ->
                    return "To show your MEC jobs, allow reading Gmail and managing Calendar. Pull down to try again."
                is DriveScopeMissingException ->
                    return "Google Drive access wasn't allowed. Tap Sync now and tick the Drive box."
                is ApiException -> return when (e.statusCode) {
                    CommonStatusCodes.DEVELOPER_ERROR ->
                        "Google sign-in isn't set up for this app yet. Check the Android OAuth client " +
                            "(package name and SHA-1) in Google Cloud."
                    CommonStatusCodes.NETWORK_ERROR -> "No internet connection."
                    CommonStatusCodes.CANCELED -> "Google sign-in was cancelled."
                    else -> "Google sign-in failed (code ${e.statusCode})."
                }
                is ApiHttpException -> return when {
                    e.code == 401 -> "Google sign-in expired. Sign in again in the Me tab."
                    e.code == 403 && (e.detail.contains("has not been used") || e.detail.contains("disabled")) ->
                        "The ${e.service} API isn't enabled in your Google Cloud project."
                    e.code == 403 && e.detail.contains("insufficient", ignoreCase = true) ->
                        "${e.service} permission wasn't granted. Tap Sync now on the Me tab and allow it."
                    e.code == 403 -> "${e.service} refused: ${e.detail}"
                    else -> "${e.service} error ${e.code}: ${e.detail}"
                }
                is UnknownHostException, is ConnectException ->
                    return "Couldn't reach Google. Check your internet connection."
                is SocketTimeoutException ->
                    return "Google timed out. Check your connection and try again."
            }
            e = e.cause
        }
        return t.message ?: t.javaClass.simpleName
    }
}

/** Saves a copy of a sent invoice to Downloads/Invoices/<code>/ so it's easy to find later. */
object Exporter {
    fun saveToDownloads(ctx: Context, file: File, folder: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Invoices/$folder")
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create file in Downloads")
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    }
}
