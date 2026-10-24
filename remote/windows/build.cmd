@echo off
rem Builds ChillRemote.exe with the C# compiler that ships with Windows (.NET Framework 4).
cd /d "%~dp0"
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /codepage:65001 /out:ChillRemote.exe /r:System.Security.dll ChillRemote.cs
if errorlevel 1 exit /b 1
echo Built %~dp0ChillRemote.exe
