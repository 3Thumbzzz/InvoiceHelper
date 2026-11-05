# Builds the installable APK -> InvoiceHelper.apk in this folder.
$env:JAVA_HOME = "$env:LOCALAPPDATA\Android\jdk\jdk-17.0.21+9"
& "$PSScriptRoot\gradlew.bat" -p "$PSScriptRoot" assembleRelease
if ($LASTEXITCODE -eq 0) {
    Copy-Item "$PSScriptRoot\app\build\outputs\apk\release\app-release.apk" "$PSScriptRoot\InvoiceHelper.apk" -Force
    Write-Host "Built: $PSScriptRoot\InvoiceHelper.apk"
}
