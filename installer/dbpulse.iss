; DBPulse Phase 18 — unsigned, per-user Windows setup preview (Inno Setup 6).
; AppImage is supplied at build time, and must contain the bundled Java
; app-image + self-contained .NET WinForms/WebView2 window host.
; Installation and uninstall NEVER reference %LOCALAPPDATA%\DBPulse user data.
#ifndef AppImage
  #error AppImage preprocessor variable is required
#endif

[Setup]
AppId={{F1A69B42-279C-4B32-99B5-84BED5A93BCD}
AppName=DBPulse
AppVerName=DBPulse 0.19.1 Preview
AppVersion=0.19.1
AppPublisher=DBPulse
VersionInfoVersion=0.19.1.0
DefaultDirName={localappdata}\Programs\DBPulse
DefaultGroupName=DBPulse
PrivilegesRequired=lowest
UsePreviousAppDir=yes
UninstallDisplayName=DBPulse
UninstallDisplayIcon={app}\window\DBPulse.WindowHost.exe
OutputBaseFilename=DBPulse-Setup-v0.19.1-preview
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
CloseApplications=no
RestartApplications=no
; Host holds this mutex for its lifetime; setup/uninstall must not replace
; binaries while the new desktop shell is running.
AppMutex=Local\DBPulse.WindowHost.InstallGuard

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"

[Files]
; The entire app-image is required: Java runtime, app/ JAR, native host,
; WebView2 DLLs and self-contained .NET runtime.
; Never put writable application data or DPAPI credentials into this tree.
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\DBPulse"; Filename: "{app}\window\DBPulse.WindowHost.exe"; WorkingDir: "{app}\window"
Name: "{userdesktop}\DBPulse"; Filename: "{app}\window\DBPulse.WindowHost.exe"; WorkingDir: "{app}\window"; Tasks: desktopicon

[Run]
Filename: "{app}\window\DBPulse.WindowHost.exe"; Description: "Launch DBPulse"; Flags: nowait postinstall skipifsilent

; Intentionally no [UninstallDelete] and no custom cleanup code.
; Inno removes installed PROGRAM files; it does not delete the independent
; %LOCALAPPDATA%\DBPulse\data, desktop, backups, logs or DPAPI secrets.
