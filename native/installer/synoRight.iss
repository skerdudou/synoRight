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
english.ProtectedDir=The folder %1 is protected (Program Files, Windows or a folder you cannot write to).%n%nsynoRight installs for you only, without administrator rights: choose a folder in your profile, for example the one proposed by default.
french.ProtectedDir=Le dossier %1 est protégé (Program Files, Windows ou dossier où vous ne pouvez pas écrire).%n%nsynoRight s'installe pour vous seul, sans droits administrateur : choisissez un dossier de votre profil, par exemple celui proposé par défaut.
german.ProtectedDir=Der Ordner %1 ist geschützt (Program Files, Windows oder ein Ordner ohne Schreibrecht).%n%nsynoRight wird nur für Sie installiert, ohne Administratorrechte: Wählen Sie einen Ordner in Ihrem Profil, zum Beispiel den vorgeschlagenen.
spanish.ProtectedDir=La carpeta %1 está protegida (Program Files, Windows o una carpeta en la que no puede escribir).%n%nsynoRight se instala solo para usted, sin derechos de administrador: elija una carpeta de su perfil, por ejemplo la propuesta por defecto.
italian.ProtectedDir=La cartella %1 è protetta (Program Files, Windows o una cartella in cui non può scrivere).%n%nsynoRight si installa solo per lei, senza diritti di amministratore: scelga una cartella del suo profilo, ad esempio quella proposta.

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

// Vrai si Dir est Base ou un de ses sous-dossiers (chemins complets, sans casse)
function IsUnder(const Dir, Base: String): Boolean;
begin
  Result := (Base <> '') and
    (Pos(AddBackslash(Lowercase(Base)), AddBackslash(Lowercase(ExpandFileName(Dir)))) = 1);
end;

// Vrai si l'utilisateur peut créer un dossier dans Dir, ou dans son parent existant le plus proche
// (un dossier et non un fichier : à la racine d'un disque, un utilisateur peut créer un dossier mais pas un fichier)
function CanCreateIn(const Dir: String): Boolean;
var
  Existing, Parent, Probe: String;
begin
  Existing := ExpandFileName(Dir);
  while not DirExists(Existing) do
  begin
    Parent := ExtractFileDir(Existing);
    if (Parent = '') or (Parent = Existing) then
    begin
      Result := False;
      Exit;
    end;
    Existing := Parent;
  end;
  Probe := AddBackslash(Existing) + 'synoRight-test-' + GetDateTimeString('yyyymmddhhnnsszzz', #0, #0);
  Result := CreateDir(Probe);
  if Result then
    RemoveDir(Probe);
end;

// Dossier de destination : refus d'un dossier protégé (installation sans droits administrateur)
function NextButtonClick(CurPageID: Integer): Boolean;
var
  Dir: String;
begin
  Result := True;
  if CurPageID = wpSelectDir then
  begin
    Dir := WizardDirValue;
    if IsUnder(Dir, ExpandConstant('{commonpf64}')) or IsUnder(Dir, ExpandConstant('{commonpf32}'))
        or IsUnder(Dir, ExpandConstant('{win}')) or not CanCreateIn(Dir) then
    begin
      MsgBox(FmtMessage(CustomMessage('ProtectedDir'), [Dir]), mbError, MB_OK);
      Result := False;
    end;
  end;
end;
