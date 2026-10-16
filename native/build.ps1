# Compile la DLL du menu Windows 11, construit et signe le package sparse synoRight.msix,
# et dépose synoRightMenu.dll, synoRight.msix et synoRight.cer dans le dossier de l'application.
# Fichier enregistré en UTF-8 avec BOM : PowerShell 5.1 lit sinon les accents en ANSI.
param(
    [Parameter(Mandatory)][string]$AppDir,
    [Parameter(Mandatory)][string]$Version
)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$target = Join-Path $root '..\target\native'
# chemin absolu : makeappx et signtool ne connaissent pas le dossier courant de PowerShell
New-Item -ItemType Directory -Force $AppDir | Out-Null
$AppDir = (Resolve-Path $AppDir).Path

# version Maven (0.0.2-SNAPSHOT) -> version de package (0.0.2.0)
$parts = @(($Version -replace '-.*$', '').Split('.') | Where-Object { $_ -ne '' })
if ($parts.Count -gt 4 -or @($parts | Where-Object { $_ -notmatch '^\d{1,5}$' -or [int]$_ -gt 65535 }).Count) {
    throw "Version non convertible en version de package (a.b.c.d) : $Version"
}
while ($parts.Count -lt 4) { $parts += '0' }
$packageVersion = ($parts[0..3]) -join '.'

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$msbuild = if (Test-Path $vswhere) { & $vswhere -latest -products * -requires Microsoft.Component.MSBuild -find 'MSBuild\**\Bin\MSBuild.exe' | Select-Object -First 1 }
if (-not $msbuild) { throw 'MSBuild introuvable : installer Visual Studio 2022 Build Tools (charge C++).' }
$makeappx = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\10.*\x64\makeappx.exe" -ErrorAction SilentlyContinue |
    Sort-Object { [version]$_.Directory.Parent.Name } -Descending | Select-Object -First 1
if (-not $makeappx) { throw 'makeappx introuvable : installer le Windows 11 SDK.' }
$signtool = Join-Path $makeappx.DirectoryName 'signtool.exe'

$pfx = if ($env:SYNORIGHT_SIGN_PFX) { $env:SYNORIGHT_SIGN_PFX } else { Join-Path $HOME '.synoRight-sign\synoRight.pfx' }
if (-not (Test-Path $pfx)) { throw "Certificat de signature absent ($pfx) : lancer native\create-cert.ps1 (ou construire avec -Dnative.skip)." }
$password = if ($env:SYNORIGHT_SIGN_PASSWORD) { $env:SYNORIGHT_SIGN_PASSWORD } else {
    [System.Net.NetworkCredential]::new('', (Get-Content "$pfx.pwd" | ConvertTo-SecureString)).Password
}

# 1. DLL
& $msbuild (Join-Path $root 'synoRightMenu\synoRightMenu.vcxproj') /p:Configuration=Release /p:Platform=x64 /nologo /v:minimal
if ($LASTEXITCODE) { throw 'Compilation de synoRightMenu.dll en échec.' }
Copy-Item (Join-Path $target 'synoRightMenu.dll') $AppDir -Force

# 2. package sparse (manifeste et logos seulement : les fichiers de l'application restent dans AppDir)
$package = Join-Path $target 'package'
if (Test-Path $package) { Remove-Item $package -Recurse -Force }
New-Item -ItemType Directory $package | Out-Null
(Get-Content (Join-Path $root 'package\AppxManifest.xml') -Raw -Encoding UTF8).Replace('@VERSION@', $packageVersion) |
    Set-Content (Join-Path $package 'AppxManifest.xml') -Encoding UTF8
Copy-Item (Join-Path $root 'package\Assets') $package -Recurse
$msix = Join-Path $AppDir 'synoRight.msix'
& $makeappx.FullName pack /d $package /p $msix /nv /o
if ($LASTEXITCODE) { throw 'makeappx en échec.' }
& $signtool sign /fd SHA256 /f $pfx /p $password $msix
if ($LASTEXITCODE) { throw 'signtool en échec.' }

# 3. certificat public, approuvé sur chaque poste à l'installation du menu
$pfxData = Get-PfxData -FilePath $pfx -Password (ConvertTo-SecureString $password -AsPlainText -Force)
Export-Certificate -Cert $pfxData.EndEntityCertificates[0] -FilePath (Join-Path $AppDir 'synoRight.cer') | Out-Null

Write-Host "Menu Windows 11 : synoRightMenu.dll, synoRight.msix ($packageVersion) et synoRight.cer dans $AppDir"
