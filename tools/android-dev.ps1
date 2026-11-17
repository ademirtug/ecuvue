[CmdletBinding()]
param(
    [ValidateSet('Run', 'Debug', 'Stop', 'Logcat')][string]$Mode = 'Run',
    [string]$DeviceSerial = $env:ANDROID_SERIAL,
    [string]$Avd = $env:ECU_VUE_AVD,
    [ValidateRange(1024, 65535)][int]$DebugPort = 5005,
    [switch]$SkipBuild,
    [switch]$Headless
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$applicationId = 'app.leximo.ecu_vue'
$activity = "$applicationId/.MainActivity"
$stateDirectory = Join-Path $projectRoot '.gradle/vscode'
$stateFile = Join-Path $stateDirectory 'android-debug.json'

function Invoke-Adb {
    param([string[]]$AdbArguments)
    # Windows PowerShell treats native stderr as ErrorRecord; capture it without hiding exit codes.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $lines = @(& $script:adb @AdbArguments 2>&1); $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $previousPreference }
    if ($code -ne 0) { throw "adb failed ($code): $($lines -join [Environment]::NewLine)" }
    return ($lines | ForEach-Object { $_.ToString() })
}

function Get-Devices {
    $lines = @(Invoke-Adb @('devices'))
    foreach ($line in $lines) {
        if ($line -match '^(\S+)\s+(device|offline|unauthorized)\b') {
            [pscustomobject]@{ Serial = $Matches[1]; Status = $Matches[2] }
        }
    }
}

function Remove-OwnedForward {
    if (-not (Test-Path -LiteralPath $script:stateFile)) { return }
    $saved = Get-Content -Raw -LiteralPath $script:stateFile | ConvertFrom-Json
    $expected = "$($saved.serial) tcp:$($saved.port) jdwp:$($saved.processId)"
    $forwards = @(Invoke-Adb @('forward', '--list'))
    if ($forwards | Where-Object { (($_ -split '\s+') -join ' ') -eq $expected }) {
        Invoke-Adb @('-s', $saved.serial, 'forward', '--remove', "tcp:$($saved.port)") | Out-Null
    }
    Remove-Item -LiteralPath $script:stateFile
}

try {
    $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $localProperties = Join-Path $projectRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir\s*=' } | Select-Object -First 1
        if ($sdkLine) { $sdkCandidates += (($sdkLine -replace '^sdk\.dir\s*=\s*', '').Replace('\\', '\').Replace('\:', ':')) }
    }
    $sdkCandidates += (Join-Path $env:LOCALAPPDATA 'Android/sdk')
    $sdkRoot = $sdkCandidates | Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platform-tools/adb.exe')) } | Select-Object -First 1
    if (-not $sdkRoot) { throw 'Android SDK not found. Set ANDROID_HOME or sdk.dir in local.properties.' }
    $script:adb = Join-Path $sdkRoot 'platform-tools/adb.exe'
    New-Item -ItemType Directory -Force -Path $stateDirectory | Out-Null

    if ($Mode -eq 'Stop') {
        Remove-OwnedForward
        Write-Host 'Android debugger forwarding removed. The app can keep running.'
        exit 0
    }

    if (-not $SkipBuild -and $Mode -ne 'Logcat') {
        if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
            $studioJdk = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
            if (Test-Path -LiteralPath (Join-Path $studioJdk 'bin/java.exe')) { $env:JAVA_HOME = $studioJdk }
        }
        Write-Host 'Building the Android debug app...'
        Push-Location -LiteralPath $projectRoot
        try {
            & (Join-Path $projectRoot 'gradlew.bat') ':androidApp:assembleDebug' '--console=plain'
            if ($LASTEXITCODE -ne 0) { throw 'Android build failed. See the build output above.' }
        } finally { Pop-Location }
    }

    Invoke-Adb @('start-server') | Out-Null
    $devices = @(Get-Devices)
    if ($DeviceSerial) {
        $selected = $devices | Where-Object Serial -eq $DeviceSerial | Select-Object -First 1
        if (-not $selected) { throw "Device '$DeviceSerial' is not connected. Check adb devices." }
    } else {
        $ready = @($devices | Where-Object Status -eq 'device')
        if ($ready.Count -gt 1) { throw 'Multiple Android devices are connected. Set ANDROID_SERIAL to the device shown by adb devices, or pass -DeviceSerial.' }
        if ($ready.Count -eq 1) { $selected = $ready[0] }
        elseif ($devices | Where-Object Status -eq 'unauthorized') { throw 'Authorize USB debugging on your phone, then press F5 again.' }
        elseif ($devices.Count -eq 1 -and $devices[0].Serial -like 'emulator-*') { $selected = $devices[0] }
        elseif ($Mode -eq 'Logcat') { throw 'No Android device is running. Start the app first.' }
        else {
            $emulator = Join-Path $sdkRoot 'emulator/emulator.exe'
            if (-not (Test-Path -LiteralPath $emulator)) { throw 'No device is connected and Android Emulator is not installed.' }
            $available = @(& $emulator -list-avds | Where-Object { $_.Trim() })
            if (-not $Avd) {
                if ($available.Count -eq 1) { $Avd = $available[0].Trim() }
                elseif ($available.Count -eq 0) { throw 'Create an Android virtual device in Android Studio Device Manager, or connect a phone.' }
                else { throw "Multiple emulators are available: $($available -join ', '). Set ECU_VUE_AVD to the one to start." }
            }
            if ($Avd -notin $available) { throw "Emulator '$Avd' was not found. Available: $($available -join ', ')" }
            Write-Host "Starting Android emulator: $Avd"
            $emulatorArguments = @('-avd', ('"' + $Avd + '"'), '-no-snapshot-save')
            if ($Headless) { $emulatorArguments += @('-no-window', '-no-audio') }
            # Detach the emulator and hide its console host. Its separate Qt device window
            # remains visible unless -no-window was requested; no terminal must stay open.
            Start-Process -FilePath $emulator -ArgumentList $emulatorArguments -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $stateDirectory 'emulator.log') `
                -RedirectStandardError (Join-Path $stateDirectory 'emulator-error.log') | Out-Null
            $deadline = [DateTime]::UtcNow.AddMinutes(3)
            do {
                Start-Sleep -Milliseconds 700
                $devices = @(Get-Devices)
                $selected = $devices | Where-Object { $_.Serial -like 'emulator-*' -and $_.Status -eq 'device' } | Select-Object -First 1
            } until ($selected -or [DateTime]::UtcNow -gt $deadline)
            if (-not $selected) { throw "Emulator did not start. See $stateDirectory/emulator-error.log." }
        }
    }
    if ($selected.Status -eq 'unauthorized') { throw 'Authorize USB debugging on your phone, then press F5 again.' }
    $DeviceSerial = $selected.Serial
    Write-Host "Using Android device: $DeviceSerial"
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    do {
        $booted = ((Invoke-Adb @('-s', $DeviceSerial, 'shell', 'getprop', 'sys.boot_completed')) -join '').Trim()
        if ($booted -eq '1') { break }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Android did not finish booting within three minutes.' }
        Start-Sleep -Milliseconds 700
    } while ($true)

    if ($Mode -eq 'Logcat') {
        $appProcessId = ((Invoke-Adb @('-s', $DeviceSerial, 'shell', "pidof -s $applicationId || true")) -join '').Trim()
        if (-not $appProcessId) { throw 'ECU Vue is not running. Start the app first.' }
        & $script:adb -s $DeviceSerial logcat "--pid=$appProcessId"
        exit $LASTEXITCODE
    }

    $apk = Join-Path $projectRoot 'androidApp/build/outputs/apk/debug/androidApp-debug.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw "Debug APK not found: $apk" }
    Remove-OwnedForward
    Write-Host 'Installing the debug app (existing app data is retained)...'
    # Installation errors, including Play/debug signature mismatch, stop here. Never uninstall automatically.
    Invoke-Adb @('-s', $DeviceSerial, 'install', '-r', $apk) | Write-Host
    Invoke-Adb @('-s', $DeviceSerial, 'shell', 'am', 'force-stop', $applicationId) | Out-Null
    $startArguments = @('-s', $DeviceSerial, 'shell', 'am', 'start')
    # -W would wait for an Activity that cannot start until the debugger attaches.
    if ($Mode -eq 'Debug') { $startArguments += '-D' } else { $startArguments += '-W' }
    $startArguments += @('-n', $activity)
    $startOutput = @(Invoke-Adb $startArguments)
    $startOutput | Write-Host
    if ($startOutput -match '^Error:') { throw 'Android could not start ECU Vue. See the error above.' }

    if ($Mode -eq 'Debug') {
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            $appProcessId = ((Invoke-Adb @('-s', $DeviceSerial, 'shell', "pidof -s $applicationId || true")) -join '').Trim()
            if ($appProcessId -match '^\d+$') { break }
            if ([DateTime]::UtcNow -gt $deadline) { throw 'ECU Vue did not start a debuggable process.' }
            Start-Sleep -Milliseconds 250
        } while ($true)
        Invoke-Adb @('-s', $DeviceSerial, 'forward', '--no-rebind', "tcp:$DebugPort", "jdwp:$appProcessId") | Out-Null
        @{serial = $DeviceSerial; port = $DebugPort; processId = $appProcessId} | ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding UTF8
        Write-Host "Android debugger ready on 127.0.0.1:$DebugPort (process $appProcessId). VS Code can now attach."
    } else { Write-Host 'ECU Vue is running.' }
} catch {
    Write-Host ("ECU Vue: " + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
