# synoRight

**Manage Synology Photos Shared Space permissions from Windows File Explorer, with plain marker files on the NAS share.**

[![Build](https://github.com/skerdudou/synoRight/actions/workflows/build.yml/badge.svg)](https://github.com/skerdudou/synoRight/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/skerdudou/synoRight?include_prereleases&sort=semver)](https://github.com/skerdudou/synoRight/releases)
![Java 25](https://img.shields.io/badge/Java-25-orange)
![Windows](https://img.shields.io/badge/platform-Windows-0078D6)

[Français](README.fr.md)

synoRight synchronises the folder permissions of the **Synology Photos Shared Space** (API `SYNO.FotoTeam`) with hidden marker files `@<short name>.synoRight` placed in the photo folders of the NAS share. Permissions become visible and editable where the photos are: in File Explorer, with a right-click.

## How it works

- Each managed NAS group gets a **short name** (e.g. `Smith` for the group "Smith family"). Only the group id (gid) links it to the NAS; the short name is used for the marker file names and for the menu entries.
- A photo folder contains a hidden file `@<short name>.synoRight` for each group allowed to see it. Example: `\\NAS\photo\2024\Holidays\@Smith.synoRight`.
- synoRight compares these files with the actual Synology Photos permissions and applies the differences, in either direction:
  - **Files → Photos** (`push`): the marker files are the reference, Synology Photos permissions are updated. It also removes the permissions that new folders inherit from their parent folder in Synology Photos.
  - **Photos → files** (`pull`): Synology Photos is the reference, marker files are created or deleted.
- Permissions are managed on **2 folder levels** below the photo root (e.g. `2024` and `2024\Holidays`), the levels on which Synology Photos lets you manage them. Giving a group access to a sub-folder also gives it access to the parent folder.
- Folders not yet indexed by Synology Photos are reported and ignored.

## Features

- **Configuration window** (`synoRightGui.exe`): NAS connection (with OTP and self-signed certificate support), photo folder, managed groups and their short names, File Explorer menu, synchronization (preview of the differences, then Files → Photos or Photos → files), language.
- **File Explorer context menu** on folders and folder backgrounds:
  - **Windows 11 menu**, shown at the top level of the right-click menu (native shell extension in a signed sparse package);
  - **classic menu** (under "Show more options"), no administrator rights needed;
  - entries chosen separately for each menu: folder permissions, open in Synology Photos, synchronization status, both full synchronizations, add / remove each group, synoRight configuration.
- **Folder permissions window**: check or uncheck the groups allowed on a folder.
- **Command line** (`synoRight.exe`) for every operation, with dry-run and confirmation.
- Password and session encrypted with a per-computer key; the Synology session (OTP) is remembered.
- Available in **English, French, German, Spanish and Italian**.

## Installation

1. Download `synoRight-<version>-windows-x64.zip` from the [Releases](https://github.com/skerdudou/synoRight/releases) page. It embeds its own Java runtime: nothing else to install.
2. Unzip it into e.g. `C:\Program Files` (the archive contains a `synoRight` folder; writing there needs administrator rights) or into any folder. The `synoRight` folder contains `synoRight.exe` (command line), `synoRightGui.exe` (no console) and, for the Windows 11 menu, `synoRightMenu.dll`, `synoRight.msix` and `synoRight.cer`.
3. Run **`synoRightGui.exe`**: the configuration window opens. Fill in the connection, test it, choose the photo folder and the groups, then install the File Explorer menu.

The **Windows 11 menu** is installed for the current user, but the first installation on a computer asks **once for administrator approval**: Windows must trust the `CN=synoRight` certificate that signs the menu package. The UAC prompt shows "Windows PowerShell": it imports `synoRight.cer` (from the application folder) into the local machine "Trusted People" store. The classic menu needs no administrator rights.

The menu commands point to the application at its current location: after an update or a move of the folder, the menus are repaired automatically the next time the GUI starts (or with `synoRight menu repair`).

A plain jar (`synoRight-<version>.jar`) is also published; it runs with `java -jar synoRight-<version>.jar <command>` (Java 25+) but does not include the Windows 11 menu.

## Usage

### Configuration window

`synoRightGui.exe` (or `synoRight gui`) opens the configuration window, with these sections and, in the bottom bar, the **language** combo box (automatic, i.e. Windows display language, or a fixed language):

- **Connection**: DSM URL, account, password (optionally remembered), connection test (asks for the OTP if needed, offers to trust a self-signed certificate).
- **Photo folder**: the root of the photos on the share (e.g. `\\NAS\photo`).
- **Groups**: the NAS groups to manage, picked from the list of groups of the NAS, and their short names.
- **Explorer menu**: entries of each menu, menu to install (Windows 11, classic or both), install / update / uninstall.
- **Synchronization**: analysis of the differences, then Files → Photos or Photos → files after confirmation.

### File Explorer menu

Right-click a photo folder (or the background of an open folder) and open the "Synology Photos permissions" menu. The entries open windows without any console:

- **Folder permissions…**: the groups allowed on the folder, to check or uncheck;
- **Synchronization status…** and **Synchronize files → Photos… / Photos → files…**: preview of the differences, then synchronization after confirmation;
- **Add / Remove &lt;group&gt;**: one entry per managed group;
- **Open in Synology Photos** (disabled by default) and **synoRight configuration…**.

**Several folders** can be selected at once. **Folder permissions…** then shows a table with one row per folder and one checkbox per group: clicking a group header ticks or unticks the whole column, ticking a group on a subfolder also ticks it on its parent folder when the parent is in the table, and cells where Synology Photos and the marker file disagree are highlighted (tooltip with both states). **Add / Remove &lt;group&gt;** and **Open in Synology Photos** apply to every selected folder; a folder in error (e.g. not indexed yet) does not stop the others.

Notes:

- Multiple selection with the **classic menu**: File Explorer starts one process per selected folder (15 folders at most); the first one gathers the folders of the others, which adds about 1.5 s before the window opens. The Windows 11 menu receives the whole selection directly.
- **Folder permissions…** waits for folders not indexed yet by Synology Photos (just copied, for example): the window shows "Waiting for Synology Photos to index the folders…" and tries again every 10 seconds, for 10 minutes at most (another NAS error is retried twice only, so that a lasting error such as a refused access is shown quickly). Closing the window cancels the wait. Folders still unknown at the end are reported, the others shown.

- Without an explicit choice, the Windows 11 menu is used when available, otherwise the classic menu (`menuTarget`).
- On Windows 11, the entry of the Windows 11 menu also appears under "Show more options" (Windows behaviour for packaged extensions). With "Both", it is hidden there to leave room for the classic menu.
- The Windows 11 menu shows no separators between groups of entries (a Windows 11 limitation for extension sub-menus); the classic menu does.
- Group or entry changes are applied **without reinstalling** the menu (`groups add/remove` and the configuration window update it).
- If the Windows 11 menu does not appear or is not up to date after an installation or an update, restart File Explorer (Windows cache).
- A menu installed by an old version (`-a ACTION` syntax) shows a message asking to reinstall it.

### Command line

```
synoRight [-c <configFolder>] [-v] <command>
```

| Command | Description |
|---|---|
| `init` | Configuration wizard in the console: tests the connection right away (OTP included), checks the photo folder, picks the groups among the NAS groups, offers to install the menu |
| `status` | Differences between the permission files and Synology Photos, without changing anything |
| `pull [-n] [-y]` | Synology Photos → files: creates / deletes the `.synoRight` files |
| `push [-n] [-y]` | Files → Synology Photos: applies the permissions described by the files |
| `show [folder...]` | Permissions of folders on the Synology Photos side and on the files side |
| `grant <group> [folder...]` | Grants a group permission on folders (and on their parent folder) |
| `revoke <group> [folder...]` | Removes a group's permission on folders |
| `rights [folder...]` | Chooses the groups allowed on each folder (console prompt), then applies everything |
| `config` | Shows the configuration and checks the photo folder, the NAS connection and the groups |
| `groups` / `groups add` / `groups remove` | Lists / adds (among the NAS groups) / removes managed groups |
| `password [--forget]` | Changes the stored password (verified against the NAS), or forgets it |
| `logout` | Forgets the stored Synology session (the next login asks for the OTP again) |
| `menu install [--modern\|--classic\|--all]` / `menu uninstall` / `menu repair` | File Explorer context menu |
| `update` | Checks whether a newer version is available on GitHub |
| `gui` | Opens the configuration window (what `synoRightGui.exe` runs) |

- `<group>` is the short name of a managed group; `[folder]` defaults to the current directory.
- `pull` and `push` show the detailed changes, then ask for confirmation: `-y` / `--yes` applies directly, `-n` / `--dry-run` only shows them.
- Common options: `-c` / `--config` configuration folder, `-v` / `--verbose` processing times and error details, `-h` / `--help` help (also per command: `synoRight pull -h`), `-V` / `--version` version.

Running `init` again on an existing configuration offers the current values, password included (press Enter to keep them). The stored session is only replaced when the configuration is saved: abandoning the wizard changes nothing.

### Folders created by another tool

A copy or import tool can open the folder permissions window on the folders it has just created:

```
synoRightGui.exe -c <configFolder> rights --new-folders [--wait-index <minutes>] <folder>...
```

- Every created folder can be passed, all levels mixed: only the level 1 and 2 folders under the photo folder are kept, deeper ones are ignored without a message (they follow their parent), duplicates removed. Nothing left: no window, exit code 0. If none of the folders is under the photo folder (path written differently from the configuration, network drive not recognized), an error lists them.
- Folders not indexed yet by Synology Photos are waited for, as from the File Explorer menu; `--wait-index <minutes>` changes the 10-minute limit (0 = no wait).
- Folders can be given as UNC paths (`\\NAS\photo\...`) or on a mapped network drive (`Z:\...`, persistent mapping).

## Configuration

The configuration is stored in `~/.synoRight/` by default (otherwise a configuration found in the current directory is used; `-c` selects another folder). The file `config.synoRight.properties` can be edited by hand (UTF-8):

```properties
folder=\\\\NAS\\photo
url=https://nas.example.com:5001
user=admin
# encrypted password (written by the configuration window, init or password); optional: asked at login otherwise
password=v2:...
# id:short name;id:short name: group id (gid) on the NAS and free short name (marker files @<short name>.synoRight, menu entries)
groups=65536:Smith;65537:Jones
# processing times and error details
verbose=false
# optional: SHA-256 fingerprint of the NAS self-signed certificate to trust (offered when testing the connection)
certFingerprint=AB:CD:...
# optional: entries of each Explorer menu (rights, open, status, push, pull, groups, config); default: rights,status,push,config
menuEntries.modern=rights,status,push,config
menuEntries.classic=rights,status,push,pull,groups,config
# optional: modern, classic or all (absent: Windows 11 if available, otherwise classic)
menuTarget=modern
# auto (Windows display language), en, fr, de, es or it
language=auto
```

- The legacy key `menuEntries` (common to both menus) is still read when `menuEntries.modern` / `menuEntries.classic` are absent.
- Changing the short name of a managed group changes the expected file names: the old `@<name>.synoRight` files are no longer taken into account, and Photos → files creates the new ones.
- Commands that modify the configuration (`init`, `groups`, `password`, the configuration window, automatic migrations) rewrite the file: comments added by hand are not kept.
- **Session file**: the Synology session (`cookieSession`, `synoToken`, `cookieDid`) is stored separately in `session.synoRight.properties` in the same folder, so the configuration can be copied to another computer without its session. Old configurations that contained it are migrated automatically.
- **Key file**: the password and the session are encrypted with AES-GCM using a random per-computer key stored in `~/.synoRight/key` (owner-only access). If the key is missing, the password is simply asked again at login (and the session recreated). To use the same configuration on another computer, copy the key file too.
- If the stored password is refused by the NAS, it is asked again and can be stored instead.
- **Self-signed certificate**: if DSM presents an unrecognized certificate (default self-signed certificate), its SHA-256 fingerprint is shown and you can choose to trust it. Only this exact certificate is then accepted, in addition to the standard validation; if it changes (renewal), the connection fails again until it is trusted again.

## Languages

synoRight is translated into **English** (default), **French**, **German**, **Spanish** and **Italian**: windows, dialogs, File Explorer menu entries, console messages and command line help.

The `language` key (`auto`, `en`, `fr`, `de`, `es`, `it`) selects the language; `auto` uses the Windows display language when it is one of these, English otherwise. It can be changed in the configuration window; the new language applies to the next window opened and to the File Explorer menus, rewritten on save.

## Building from source

Requirements: **JDK 25** and **Maven**.

```
mvn package
```

produces `target/synoRight.jar` (runs with `java -jar target/synoRight.jar <command>`).

```
mvn package -Pexe
```

produces the standalone application (embedded Java runtime, built by `jpackage`) in `target/dist/synoRight/`, including the Windows 11 menu. This needs in addition:

- **Visual Studio 2022 Build Tools** with the C++ workload, and the **Windows 11 SDK** (MSBuild is located with `vswhere`, `makeappx` and `signtool` in the Windows Kits folder);
- a **signing certificate** for the menu package (subject `CN=synoRight`, matching the package manifest):
  - created once by `native\create-cert.ps1` (in `~\.synoRight-sign\`, password protected with DPAPI for the current user), or
  - provided by the environment variables `SYNORIGHT_SIGN_PFX` (path to the `.pfx`) and `SYNORIGHT_SIGN_PASSWORD`.

`mvn package -Pexe -Dnative.skip` builds the application without the Windows 11 menu (no native requirement); the classic menu remains available.

## Releases and CI

The GitHub Actions workflow [`build.yml`](.github/workflows/build.yml) runs the tests (`mvn verify`) on every push and pull request. Pushing a `v*` tag builds the full application with the Windows 11 menu and creates a draft pre-release with `synoRight-<version>-windows-x64.zip` and `synoRight-<version>.jar`. The menu package is signed with the certificate given by the `SYNORIGHT_SIGN_PFX_BASE64` and `SYNORIGHT_SIGN_PASSWORD` secrets, or else with a certificate generated for that build only (the release notes then say so).

To create the secrets from a .pfx (e.g. the synoRight.pfx made by 
ative\create-cert.ps1):

- `SYNORIGHT_SIGN_PFX_BASE64`: the output of `[Convert]::ToBase64String([IO.File]::ReadAllBytes('synoRight.pfx'))` in PowerShell.
- `SYNORIGHT_SIGN_PASSWORD`: the password of the .pfx. For a .pfx made by 
ative\create-cert.ps1, it is stored DPAPI-protected in synoRight.pfx.pwd; read it, as the same Windows user, with `[System.Net.NetworkCredential]::new('', (Get-Content synoRight.pfx.pwd | ConvertTo-SecureString)).Password`.
