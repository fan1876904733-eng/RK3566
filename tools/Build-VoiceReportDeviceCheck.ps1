param(
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$Keystore = (Join-Path $env:USERPROFILE '.android/debug.keystore')
)

# Builds a separate test APK; it does not install anything or issue device commands.
# Sign with the same debug key as the target app. Run only while RF control is idle.
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$repoRoot = Split-Path $PSScriptRoot -Parent
$output = Join-Path $repoRoot 'build/voice-report-device-check'
$classes = Join-Path $output 'classes'
$dex = Join-Path $output 'dex'
$androidJar = Join-Path $SdkRoot 'platforms/android-36.1/android.jar'
$buildTools = Join-Path $SdkRoot 'build-tools/36.0.0'
New-Item -ItemType Directory -Force -Path $output, $classes, $dex | Out-Null

function Invoke-Checked([string]$Program, [string[]]$CommandArgs) {
    & $Program @CommandArgs
    if ($LASTEXITCODE -ne 0) { throw "Command failed: $Program" }
}

Invoke-Checked (Join-Path $JavaHome 'bin/javac.exe') @(
    '-encoding', 'UTF-8', '--release', '11', '-cp', $androidJar,
    '-d', $classes, (Join-Path $PSScriptRoot 'VoiceReportDeviceCheck.java')
)
Invoke-Checked (Join-Path $JavaHome 'bin/jar.exe') @(
    "-J-Djava.io.tmpdir=$output", '--create', '--file', (Join-Path $output 'classes.jar'), '-C', $classes, '.'
)
Invoke-Checked (Join-Path $buildTools 'd8.bat') @(
    '--lib', $androidJar, '--min-api', '26', '--output', $dex, (Join-Path $output 'classes.jar')
)
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'voice-report-check-manifest.xml') -Destination (Join-Path $output 'AndroidManifest.xml')
Invoke-Checked (Join-Path $buildTools 'aapt.exe') @(
    'package', '-f', '-M', (Join-Path $output 'AndroidManifest.xml'),
    '-I', $androidJar, '-F', (Join-Path $output 'unsigned.apk')
)
Push-Location $dex
try {
    Invoke-Checked (Join-Path $buildTools 'aapt.exe') @('add', (Join-Path $output 'unsigned.apk'), 'classes.dex')
} finally { Pop-Location }
Invoke-Checked (Join-Path $buildTools 'zipalign.exe') @(
    '-f', '4', (Join-Path $output 'unsigned.apk'), (Join-Path $output 'aligned.apk')
)
Invoke-Checked (Join-Path $buildTools 'apksigner.bat') @(
    'sign', '--ks', $Keystore, '--ks-key-alias', 'androiddebugkey',
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
    '--out', (Join-Path $output 'voice-report-check.apk'), (Join-Path $output 'aligned.apk')
)
Write-Output (Join-Path $output 'voice-report-check.apk')
