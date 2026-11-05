# Invoice Helper

Android app for sending DJ invoices. Pick a company, enter the client/event name and amounts, tap Send.
It generates the PDF in the same layout as the Excel invoices and emails it from your Gmail
(through the Gmail API, after you tap "Sign in with Google").

## Install
Copy `InvoiceHelper.apk` to the phone and open it (allow "install unknown apps" when asked),
or with the phone plugged in (USB debugging on):
`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r InvoiceHelper.apk`

## One-time Google setup (about 10 minutes, free)
Google only lets an app send Gmail after it's registered in a Google Cloud project.
Do this in a browser while signed in to the Google account you send invoices from.

1. Open https://console.cloud.google.com/projectcreate, name the project `Invoice Helper`, then **Create**.
2. Open https://console.cloud.google.com/apis/library/gmail.googleapis.com with that project selected
   and click **Enable**.
3. Open https://console.cloud.google.com/auth/overview and click **Get started**:
   - App name `Invoice Helper`, support email = your Gmail
   - Audience: **External**
   - Contact email = your Gmail, tick the agreement, then **Create**
4. **Data Access** → **Add or remove scopes** → tick `.../auth/gmail.send` and `.../auth/userinfo.email`
   → **Update** → **Save**.
5. **Audience** → **Publish app** → **Confirm**.
   This stops Google asking you to sign in again every 7 days. It doesn't list the app anywhere public.
6. **Clients** → **Create client**:
   - Application type: **Android**
   - Package name: `com.tententen.invoicehelper`
   - SHA-1 certificate fingerprint: `F2:C4:67:A6:83:EC:CB:EB:F6:0E:7D:FD:85:6D:D3:35:3A:0B:C9:FE`
   - **Create**. You don't need to copy anything into the app.

Then on the phone, go to the **Me** tab and tap **Sign in with Google**, then pick your account.
- Google shows **"Google hasn't verified this app"**. That's expected for a personal app:
  tap **Advanced** → **Go to Invoice Helper**.
- Allow **Send email on your behalf**, then **Continue**.
- Tap **Send test email** to check it works.

If sign-in says "isn't set up for this app yet", Google may still be applying the new client.
Wait a few minutes and try again.

## What happens on Send
- PDF: `Invoice_<CODE><No>_<yyyy.M.d>.pdf`, for example `Invoice_MEC74_2026.11.5.pdf`
- Email to the company's invoice email, subject `5/Nov/2026 - <client name>`, with the PDF attached
- The company's invoice number goes up by 1 only if the email sends
- A copy is saved to `Download/Invoices/<CODE>/` on the phone and appears in Gmail's Sent folder
- Parking/Travel lines appear on the invoice only when they're above $0

## Moving companies between devices
On the **Companies** tab, **Export** saves all companies (details plus next invoice numbers) to a
`invoice-helper-companies-<date>.json` file. Save it to Google Drive or Downloads.
On the other device, **Import** that file. Each company matches an existing one by id, then short code,
then name. Matches are updated and new companies are added.
The next invoice number keeps whichever is higher, so a number is never reused.
Only one device can safely send for a given company at a time, unless you export/import after each invoice.

## Signing key: back it up
`keystore/` holds the app's signing key. Google sign-in is registered to its SHA-1,
and Android only installs updates signed with the same key.
If you lose it, you'll need a new key, a new Android client in step 6, and a reinstall of the app.

## Building from source
If you don't have `keystore/`, the build signs with the standard Android debug key.
To use Google sign-in with that build, register your own Android OAuth client with that key's SHA-1.
Your personal details aren't in the code: enter them in the app's **Me** tab.

## Rebuild
Run `build-apk.ps1` (PowerShell). It uses the JDK 17 + Android SDK in `%LOCALAPPDATA%\Android`.
