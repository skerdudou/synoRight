param(
    [Parameter(Mandatory = $true)][int]$AppPid,
    [Parameter(Mandatory = $true)][string]$AppDir,
    [Parameter(Mandatory = $true)][string]$NewDir,
    [Parameter(Mandatory = $true)][string]$Relaunch,
    [Parameter(Mandatory = $true)][string]$LogDir,
    [switch]$CopyOnly,
    [switch]$NoRelaunch
)

# Mise à jour d'une copie portable de synoRight : attend la fin de l'application, remplace seulement l'image de
# l'application (app et runtime en miroir, fichiers racine copiés sans rien supprimer), relance l'application puis
# supprime le dossier temporaire (parent de NewDir). La configuration posée à côté de l'exe, l'ancienne DLL du menu et
# tout fichier de l'utilisateur restent. Journal en anglais (fichier technique) : LogDir\update.log.
# -CopyOnly : copie seule (relance élevée quand le dossier n'est pas modifiable) ; -NoRelaunch : pour les tests.

$ErrorActionPreference = 'Continue'

# chemins absolus sans séparateur final (un « \" » final casserait les guillemets passés à robocopy)
function Get-FullPath([string]$path) {
    return [System.IO.Path]::GetFullPath($path).TrimEnd('\')
}

$AppDir = Get-FullPath $AppDir
$NewDir = Get-FullPath $NewDir
$LogDir = Get-FullPath $LogDir

try {
    New-Item -ItemType Directory -Force -Path $LogDir -ErrorAction Stop | Out-Null
} catch {
    # pas de journal possible : on continue quand même
}
$log = Join-Path $LogDir 'update.log'

function Write-Log([string]$message) {
    $line = '{0} {1}' -f (Get-Date -Format 's'), $message
    try {
        Add-Content -LiteralPath $log -Value $line -Encoding UTF8 -ErrorAction Stop
    } catch {
        # journal indisponible : tant pis
    }
}

function Start-App {
    if ($NoRelaunch) {
        Write-Log 'relaunch skipped (-NoRelaunch)'
        return
    }
    try {
        Start-Process -FilePath $Relaunch -WorkingDirectory $AppDir -ErrorAction Stop
        Write-Log "relaunched $Relaunch"
    } catch {
        Write-Log "relaunch failed: $($_.Exception.Message)"
    }
}

# miroir de app et runtime, puis fichiers racine sans suppression ; codes robocopy 0-7 : succès, 8 et plus : échec
function Copy-AppImage {
    foreach ($sub in @('app', 'runtime')) {
        $src = Join-Path $NewDir $sub
        $dst = Join-Path $AppDir $sub
        if (-not (Test-Path -LiteralPath $src)) {
            Write-Log "robocopy $sub skipped: $src missing"
            continue
        }
        & robocopy $src $dst /MIR /R:2 /W:1 /NP /NJH /NJS | Out-Null
        $code = $LASTEXITCODE
        if ($code -ge 8) {
            Write-Log "robocopy $sub failed (code $code)"
        } else {
            Write-Log "robocopy $sub done (code $code)"
        }
    }
    & robocopy $NewDir $AppDir /LEV:1 /R:2 /W:1 /NP /NJH /NJS | Out-Null
    $code = $LASTEXITCODE
    if ($code -ge 8) {
        Write-Log "robocopy root files failed (code $code)"
    } else {
        Write-Log "robocopy root files done (code $code)"
    }
}

function Test-Writable([string]$dir) {
    $probe = Join-Path $dir ('.update-probe-' + [guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType File -Path $probe -ErrorAction Stop | Out-Null
        Remove-Item -LiteralPath $probe -Force -ErrorAction SilentlyContinue
        return $true
    } catch {
        return $false
    }
}

# vrai si le dossier peut être supprimé : ni racine d'un lecteur, ni le dossier de l'appli, ni un de ses parents
function Test-Deletable([string]$folder) {
    $full = Get-FullPath $folder
    $root = [System.IO.Path]::GetPathRoot($full).TrimEnd('\')
    if ($full -eq $root) {
        return $false
    }
    if ($AppDir.Equals($full, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $false
    }
    return -not $AppDir.StartsWith($full + '\', [System.StringComparison]::OrdinalIgnoreCase)
}

function Remove-TempDir {
    $temp = Get-FullPath (Split-Path -Parent $NewDir)
    if (-not (Test-Deletable $temp)) {
        Write-Log "Refusing to delete $temp (drive root, the application folder or one of its parents)"
        return
    }
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $temp) {
        # le script tourne depuis ce dossier : suppression différée, au mieux
        try {
            $cmd = '/c timeout /t 2 /nobreak >nul & rmdir /s /q "{0}"' -f $temp
            Start-Process -FilePath 'cmd.exe' -ArgumentList $cmd -WindowStyle Hidden -ErrorAction Stop
            Write-Log "temporary folder $temp partly removed, removal of the rest scheduled"
        } catch {
            Write-Log "cleanup of $temp failed: $($_.Exception.Message)"
        }
    } else {
        Write-Log "temporary folder $temp removed"
    }
}

if ($CopyOnly) {
    # relance élevée : copie seulement, la relance et le nettoyage restent au script non élevé
    Write-Log 'elevated copy started'
    Copy-AppImage
    Write-Log 'elevated copy finished'
    exit 0
}

Write-Log "Update started (pid $AppPid, app $AppDir, new $NewDir)"

# 1. attente de la fin de l'application et de tout processus lancé depuis son dossier (60 s en tout)
$watch = [System.Diagnostics.Stopwatch]::StartNew()
Wait-Process -Id $AppPid -Timeout 60 -ErrorAction SilentlyContinue
$prefix = $AppDir + '\'
$running = @()
do {
    $running = @(Get-Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase) })
    if ($running.Count -eq 0) {
        break
    }
    Start-Sleep -Milliseconds 500
} while ($watch.Elapsed.TotalSeconds -lt 60)
if ($running.Count -gt 0 -or (Get-Process -Id $AppPid -ErrorAction SilentlyContinue)) {
    Write-Log 'abort: synoRight still running, nothing was changed'
    Start-App
    Remove-TempDir
    exit 1
}
Write-Log 'synoRight has exited'

# 2. copie, élevée si le dossier de l'appli n'est pas modifiable
if (Test-Writable $AppDir) {
    Copy-AppImage
} else {
    Write-Log 'application folder not writable, requesting elevation'
    $quoted = { param($value) '"{0}"' -f $value }
    $argList = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File {0} -AppPid {1} -AppDir {2} -NewDir {3} -Relaunch {4} -LogDir {5} -CopyOnly' -f `
        (& $quoted $PSCommandPath), $AppPid, (& $quoted $AppDir), (& $quoted $NewDir), (& $quoted $Relaunch), (& $quoted $LogDir)
    try {
        $elevated = Start-Process -FilePath 'powershell' -ArgumentList $argList -Verb RunAs -Wait -PassThru -ErrorAction Stop
        Write-Log "elevated copy ended (code $($elevated.ExitCode))"
    } catch {
        # UAC refusée ou échec : rien n'a été copié, l'ancienne version est relancée telle quelle
        Write-Log "elevation declined or failed: $($_.Exception.Message)"
        Start-App
        Remove-TempDir
        Write-Log 'Update finished (not applied)'
        exit 1
    }
}

# 3. relance (jamais élevée) puis nettoyage
Start-App
Remove-TempDir
Write-Log 'Update finished'
exit 0
