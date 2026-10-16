# Crée le certificat auto-signé de signature du package du menu Windows 11 (CN=synoRight,
# identique au Publisher du manifest). Le .pfx et son mot de passe (chiffré DPAPI, lisible
# seulement par cet utilisateur sur ce poste) restent hors du dépôt.
# Fichier enregistré en UTF-8 avec BOM : PowerShell 5.1 lit sinon les accents en ANSI.
param([string]$OutDir = (Join-Path $HOME '.synoRight-sign'))
$ErrorActionPreference = 'Stop'

New-Item -ItemType Directory -Force $OutDir | Out-Null
$pfx = Join-Path $OutDir 'synoRight.pfx'
if (Test-Path $pfx) { throw "Certificat déjà présent : $pfx" }

$cert = New-SelfSignedCertificate -Type Custom -Subject 'CN=synoRight' -KeyUsage DigitalSignature `
    -FriendlyName 'synoRight (signature du menu Windows 11)' -CertStoreLocation 'Cert:\CurrentUser\My' `
    -TextExtension @('2.5.29.37={text}1.3.6.1.5.5.7.3.3', '2.5.29.19={text}') -NotAfter (Get-Date).AddYears(10)
try {
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $password = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    Export-PfxCertificate -Cert $cert -FilePath $pfx -Password $password | Out-Null
    $password | ConvertFrom-SecureString | Set-Content "$pfx.pwd"
} finally {
    # la clé privée ne reste pas dans le magasin de l'utilisateur
    Remove-Item "Cert:\CurrentUser\My\$($cert.Thumbprint)" -DeleteKey
}

Write-Host "Certificat de signature créé : $pfx"
