; Inno Setup 6 script — packaging/windows/installer.iss
; Paths below are relative to this file's folder (packaging/windows).
; Requires Inno Setup 6: winget install --id JRSoftware.InnoSetup -e
; Compile:  "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" packaging/windows/installer.iss

#ifndef MyAppVersion
  #define MyAppVersion "1.0.0"
#endif
#define MyAppName "MQTT H.264 视频接收端"
#define MyAppShortName "mqtt-h264-client"
#define MyAppPublisher "com.mqttclient"
#define MyAppExe "launch-client.vbs"

[Setup]
AppId={{3F7F67FC-E09B-4777-884E-D9CF9845E742}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\{#MyAppShortName}
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\..\release
OutputBaseFilename=mqtt-h264-client-setup-{#MyAppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName={#MyAppName}
;SetupIconFile=app.ico            ; uncomment once packaging/windows/app.ico exists

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式(&D)"; GroupDescription: "附加任务:"

[Files]
; The fat runnable jar (contains /maps/field.png and a built-in /config.json fallback).
Source: "..\..\target\mqtt-h264-client.jar"; DestDir: "{app}"; Flags: ignoreversion
; Editable user config: app prefers ./config.json (working dir = {app}); hot-reload watches it.
Source: "..\..\config.json";             DestDir: "{app}"; DestName: "config.json"; Flags: ignoreversion
Source: "launch-client.vbs";              DestDir: "{app}"; Flags: ignoreversion
Source: "run-client-debug.bat";           DestDir: "{app}"; Flags: ignoreversion
Source: "..\..\README.md";                DestDir: "{app}"; DestName: "README.txt"; Flags: ignoreversion isreadme

[Icons]
Name: "{userprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExe}"; WorkingDir: "{app}"; Comment: "启动 {#MyAppName}"
Name: "{userdesktop}\{#MyAppName}";   Filename: "{app}\{#MyAppExe}"; WorkingDir: "{app}"; Comment: "启动 {#MyAppName}"; Tasks: desktopicon

[Code]
{ -- Soft prerequisite check: warn (not block) when no common JDK 17+ install is found -- }

function FindJavaw(Dir: String; Depth: Integer): Boolean;
var
  FindRec: TFindRec;
begin
  Result := False;
  if Depth = 0 then Exit;
  if FindFirst(AddBackslash(Dir) + '*', FindRec) then begin
    try
      repeat
        if (FindRec.Attributes and FILE_ATTRIBUTE_DIRECTORY <> 0) and
           (FindRec.Name <> '.') and (FindRec.Name <> '..') then begin
          if FileExists(AddBackslash(Dir) + FindRec.Name + '\bin\javaw.exe') then begin
            Result := True;
            Exit;
          end else if FindJavaw(AddBackslash(Dir) + FindRec.Name, Depth - 1) then begin
            Result := True;
            Exit;
          end;
        end;
      until not FindNext(FindRec);
    finally
      FindClose(FindRec);
    end;
  end;
end;

function JavaLikelyInstalled(): Boolean;
var
  Roots: array of String;
  s: String;
  i: Integer;
begin
  Result := False;

  s := GetEnv('JAVA_HOME');
  if (s <> '') and FileExists(AddBackslash(s) + 'bin\javaw.exe') then begin
    Result := True;
    Exit;
  end;

  SetArrayLength(Roots, 7);
  Roots[0] := '{pf}\Eclipse Adoptium';
  Roots[1] := '{pf}\Java';
  Roots[2] := '{pf}\Microsoft';
  Roots[3] := '{pf}\BellSoft';
  Roots[4] := '{pf}\Amazon Corretto';
  Roots[5] := '{pf}\Zulu';
  Roots[6] := '{localappdata}\Programs\Eclipse Adoptium';
  for i := 0 to GetArrayLength(Roots) - 1 do begin
    if DirExists(ExpandConstant(Roots[i])) and
       FindJavaw(ExpandConstant(Roots[i]), 3) then begin
      Result := True;
      Exit;
    end;
  end;
end;

function InitializeSetup(): Boolean;
begin
  Result := True;
  if not JavaLikelyInstalled() then
    if MsgBox('未检测到常见的 JDK/JRE 17+ 安装。程序运行需要 Java 17 或更高版本。' + #13#10 +
              '是否仍要安装？(建议先安装 JDK 17/21: https://adoptium.net)' + #13#10#13#10 +
              'No common JDK/JRE 17+ detected. Install anyway?', mbConfirmation, MB_YESNO) = IDNO then
      Result := False;
end;
