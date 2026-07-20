<#
  capture_vol.ps1 — Capture des logs d'observation du gate obstacle (mode MIROIR).

  USAGE :
    Clic droit sur le fichier -> "Executer avec PowerShell"
    OU dans un terminal : powershell -ExecutionPolicy Bypass -File .\capture_vol.ps1

  Ce que fait le script :
    1. Localise adb.exe automatiquement (emplacements Android SDK usuels).
    2. Verifie qu'un appareil (telephone) est branche et autorise.
    3. Vide le buffer logcat (repart propre).
    4. Capture en direct vers un fichier horodate : gate_vol_AAAAMMJJ_HHMMSS.txt

  Par defaut : capture SEULEMENT les tags du gate (PERCEPTION_SAMPLE, GATE_MIRROR,
  GATE_MIRROR_VIOLATION) -> fichier petit et cible.
  Option -Full : capture TOUT le logcat (plus gros, utile pour debug elargi).

  RAPPEL : avant le vol, mettre OBSTACLE_GATE_MIROIR_ACTIF = true dans Phase3Activity.kt
  (temporaire, non commite), rebuild + reinstaller. Apres le vol, remettre a false.
  ARRET DE LA CAPTURE : Ctrl+C dans cette fenetre.
#>

param(
    [switch]$Full  # -Full pour capturer tout le logcat au lieu des seuls tags du gate
)

$ErrorActionPreference = "Stop"

Write-Host "=== Capture logs gate obstacle (mode miroir) ===" -ForegroundColor Cyan

# --- 1. Localiser adb.exe ---
$candidats = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe",
    "C:\Android\Sdk\platform-tools\adb.exe",
    "$env:ANDROID_HOME\platform-tools\adb.exe",
    "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"
)
$adb = $candidats | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1

if (-not $adb) {
    # dernier recours : adb deja dans le PATH ?
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}

if (-not $adb) {
    Write-Host "ERREUR : adb.exe introuvable." -ForegroundColor Red
    Write-Host "Installe les Android SDK Platform-Tools, ou ajoute adb au PATH." -ForegroundColor Yellow
    Read-Host "Appuie sur Entree pour fermer"
    exit 1
}
Write-Host "adb trouve : $adb" -ForegroundColor Green

# --- 2. Verifier qu'un appareil est branche et autorise ---
$devices = & $adb devices
$ligne = $devices | Select-String -Pattern "\tdevice$"
if (-not $ligne) {
    Write-Host "ERREUR : aucun appareil autorise detecte." -ForegroundColor Red
    Write-Host "Verifie : cable USB, debogage USB active, et 'Autoriser' accepte sur le telephone." -ForegroundColor Yellow
    & $adb devices
    Read-Host "Appuie sur Entree pour fermer"
    exit 1
}
$serie = ($ligne.ToString() -split "\t")[0]
Write-Host "Appareil connecte : $serie" -ForegroundColor Green

# --- 3. Vider le buffer logcat ---
& $adb logcat -c
Write-Host "Buffer logcat vide." -ForegroundColor Green

# --- 4. Fichier de sortie horodate ---
$horodatage = Get-Date -Format "yyyyMMdd_HHmmss"
$fichier = Join-Path (Get-Location) "gate_vol_$horodatage.txt"

Write-Host ""
Write-Host "Fichier de sortie : $fichier" -ForegroundColor Cyan
if ($Full) {
    Write-Host "Mode : COMPLET (tout le logcat)" -ForegroundColor Yellow
} else {
    Write-Host "Mode : CIBLE (tags PERCEPTION_SAMPLE / GATE_MIRROR / GATE_MIRROR_VIOLATION)" -ForegroundColor Yellow
}
Write-Host ""
Write-Host ">>> Capture EN COURS. Fais ton vol, puis appuie sur Ctrl+C pour arreter. <<<" -ForegroundColor Green
Write-Host ""

# --- Capture ---
try {
    if ($Full) {
        & $adb logcat -v threadtime *:I | Tee-Object -FilePath $fichier
    } else {
        & $adb logcat -v threadtime *:I |
            Select-String "PERCEPTION_SAMPLE|GATE_MIRROR|GATE_MIRROR_VIOLATION" |
            Tee-Object -FilePath $fichier
    }
}
finally {
    Write-Host ""
    Write-Host "=== Capture terminee ===" -ForegroundColor Cyan
    if (Test-Path $fichier) {
        $n = (Get-Content $fichier -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
        Write-Host "Fichier ecrit : $fichier" -ForegroundColor Green
        Write-Host "Lignes capturees : $n" -ForegroundColor Green
        Write-Host ""
        Write-Host "Verifs rapides (a copier-coller) :" -ForegroundColor Cyan
        Write-Host "  Select-String -Path `"$fichier`" -Pattern `"send_equals_sanitized=False`" | Measure-Object   # attendu : 0"
        Write-Host "  Select-String -Path `"$fichier`" -Pattern `"GATE_MIRROR_VIOLATION`" | Measure-Object          # attendu : 0"
    }
    Write-Host ""
    Write-Host "RAPPEL : remets OBSTACLE_GATE_MIROIR_ACTIF = false dans Phase3Activity.kt." -ForegroundColor Yellow
    Read-Host "Appuie sur Entree pour fermer"
}
