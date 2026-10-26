; Installateur synoRight (Inno Setup 6), installation par utilisateur sans droits administrateur.
; Compilation : ISCC.exe /DAppVersion=<version> /DSourceDir=<chemin de target\dist\synoRight> /O<dossier> synoRight.iss

#ifndef AppVersion
  #error AppVersion non défini : passer /DAppVersion=<version>
#endif
#ifndef SourceDir
  #error SourceDir non défini : passer /DSourceDir=<dossier de l'application jpackage>
#endif

[Setup]
; AppId fixe : c'est lui qui permet à une nouvelle version de s'installer par-dessus l'ancienne
AppId={{8E4C2A7B-3F1D-4B6E-9A25-C7D0E1F2A3B4}
AppName=synoRight
AppVersion={#AppVersion}
AppPublisher=synoRight
AppPublisherURL=https://github.com/skerdudou/synoRight
DefaultDirName={autopf}\synoRight
DisableDirPage=auto
DefaultGroupName=synoRight
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
; en mise à jour silencieuse, l'IHM ouverte est fermée puis relancée seulement avec /RELAUNCH
CloseApplications=yes
RestartApplications=no
OutputBaseFilename=synoRight-{#AppVersion}-setup
UninstallDisplayIcon={app}\synoRightGui.exe
SetupIconFile={#SourcePath}..\..\src\main\jpackage\synoRight.ico
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "french"; MessagesFile: "compiler:Languages\French.isl"
Name: "german"; MessagesFile: "compiler:Languages\German.isl"
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"
Name: "italian"; MessagesFile: "compiler:Languages\Italian.isl"

[CustomMessages]
english.RepairMenus=Updating the Explorer menus...
french.RepairMenus=Mise à jour des menus de l'Explorateur...
german.RepairMenus=Explorer-Menüs werden aktualisiert...
spanish.RepairMenus=Actualizando los menús del Explorador...
italian.RepairMenus=Aggiornamento dei menu di Esplora file...
english.LaunchApp=Launch synoRight
french.LaunchApp=Lancer synoRight
german.LaunchApp=synoRight starten
spanish.LaunchApp=Iniciar synoRight
italian.LaunchApp=Avvia synoRight

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; Excludes: "synoRightMenu-*.dll"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; DLL du menu Windows 11 : chargée par dllhost, elle peut être verrouillée ; son nom porte la version, un fichier présent
; est donc identique (réinstallation de la même version) et n'est pas remplacé. Absente si construite avec -Dnative.skip.
Source: "{#SourceDir}\synoRightMenu-*.dll"; DestDir: "{app}"; Flags: onlyifdoesntexist skipifsourcedoesntexist

; dossier de démarrage = dossier de l'application : une configuration posée à côté de l'application est trouvée
; quel que soit le lancement (la configuration du profil, ~\.synoRight, reste prioritaire)
[Icons]
Name: "{autoprograms}\synoRight"; Filename: "{app}\synoRightGui.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\synoRight"; Filename: "{app}\synoRightGui.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
; répare les menus de l'Explorateur seulement s'ils existent et pointent vers une autre version
Filename: "{app}\synoRight.exe"; Parameters: "menu repair --if-needed"; WorkingDir: "{app}"; Flags: runhidden waituntilterminated; StatusMsg: "{cm:RepairMenus}"
Filename: "{app}\synoRightGui.exe"; WorkingDir: "{app}"; Description: "{cm:LaunchApp}"; Flags: postinstall nowait skipifsilent
; mise à jour lancée par l'IHM (/SILENT ... /RELAUNCH) : l'IHM est relancée sans page finale
Filename: "{app}\synoRightGui.exe"; WorkingDir: "{app}"; Flags: nowait; Check: WizardSilent and RelaunchRequested

[UninstallRun]
; retire les menus avant la suppression des fichiers (la configuration est conservée)
Filename: "{app}\synoRight.exe"; Parameters: "menu uninstall"; WorkingDir: "{app}"; Flags: runhidden waituntilterminated; RunOnceId: "menuUninstall"

[Code]
// Vrai si /RELAUNCH figure sur la ligne de commande (sans valeur : le paramètre param ne convient pas)
function RelaunchRequested: Boolean;
var
  I: Integer;
begin
  Result := False;
  for I := 1 to ParamCount do
    if CompareText(ParamStr(I), '/RELAUNCH') = 0 then
    begin
      Result := True;
      Exit;
    end;
end;
