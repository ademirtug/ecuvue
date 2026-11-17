[CmdletBinding()]
param([string]$SigningProperties)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
try {
    if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $studioJdk = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
        if (Test-Path -LiteralPath (Join-Path $studioJdk 'bin/java.exe')) { $env:JAVA_HOME = $studioJdk }
    }
    $gradleArguments = @(
        ':androidApp:verifyUploadSigning',
        ':core:jvmTest', ':composeApp:jvmTest', ':androidApp:testDebugUnitTest',
        ':androidApp:lintRelease', ':androidApp:preparePlayUpload', '--console=plain'
    )
    if ($SigningProperties) {
        $resolvedSigningProperties = (Resolve-Path -LiteralPath $SigningProperties).Path
        $gradleArguments += "-PecuVueSigningProperties=$resolvedSigningProperties"
    }
    Push-Location -LiteralPath $projectRoot
    try {
        # Windows PowerShell represents native stderr as ErrorRecord objects. Keep
        # Gradle's diagnostics visible and use its exit code to decide success.
        $previousPreference = $ErrorActionPreference
        $releaseExitCode = $null
        try {
            $ErrorActionPreference = 'Continue'
            & (Join-Path $projectRoot 'gradlew.bat') @gradleArguments 2>&1 | ForEach-Object { Write-Host $_.ToString() }
            $releaseExitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previousPreference }
        if ($releaseExitCode -ne 0) { throw 'Release preparation failed. No new upload bundle is ready; see the error above.' }
    } finally { Pop-Location }
    Write-Host "Signed upload bundle and checksum: $projectRoot/androidApp/build/outputs/play"
    Write-Host 'Confirm the build number is unused in Play Console before uploading. This command does not publish the app.'
} catch {
    Write-Host ("ECU Vue: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
