#ifndef AppVersion
  #define AppVersion "1.4.0"
#endif
[Setup]
AppId={{8E878E75-59C3-45C0-8FF1-A6A8A422559B}
AppName=XPhone
AppVersion={#AppVersion}
AppPublisher=XPhone
DefaultDirName={autopf}\XPhone
DefaultGroupName=XPhone
DisableProgramGroupPage=yes
DisableDirPage=no
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
OutputDir=..\dist\Setup
OutputBaseFilename=XPhone-Setup-{#AppVersion}-x64
SetupIconFile=..\src\XPhone\Assets\xphone.ico
UninstallDisplayIcon={app}\XPhone.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern dark
CloseApplications=yes
RestartApplications=no
[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"
[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; Flags: unchecked
[Files]
Source: "..\dist\XPhone\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\XPhone"; Filename: "{app}\XPhone.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\XPhone"; Filename: "{app}\XPhone.exe"; WorkingDir: "{app}"; Tasks: desktopicon
[Run]
Filename: "{app}\XPhone.exe"; Description: "Открыть XPhone"; Flags: nowait postinstall skipifsilent
