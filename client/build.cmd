@echo off
rem Mydrive PC app build (Visual Studio 2022 Build Tools)
setlocal
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cd /d "%~dp0"
if not exist build mkdir build
if not exist build\obj mkdir build\obj
set CF=/nologo /std:c++20 /O2 /EHsc /utf-8 /W3 /MT /DUNICODE /D_UNICODE /D_CRT_SECURE_NO_WARNINGS /I third_party\winfsp /Fobuild\obj\
rem cloud.cpp (Explorer integration, C++/WinRT) is compiled once and shared
cl %CF% /c src\cloud.cpp || exit /b 1
rem web drive: WinFsp import library from the .def (no WinFsp SDK needed); the DLL is delay-loaded at run time
lib /nologo /def:third_party\winfsp\winfsp-x64.def /machine:x64 /out:build\obj\winfsp-x64.lib >nul || exit /b 1
cl %CF% /Fe:build\mydrive-cli.exe src\cli.cpp src\engine.cpp src\http.cpp build\obj\cloud.obj /link /SUBSYSTEM:CONSOLE || exit /b 1
rc /nologo /i res /i src /fo build\obj\app.res res\app.rc || exit /b 1
cl %CF% /Fe:build\Mydrive.exe src\app.cpp src\ui.cpp src\engine.cpp src\http.cpp src\webdrive.cpp build\obj\cloud.obj build\obj\app.res /link /SUBSYSTEM:WINDOWS build\obj\winfsp-x64.lib delayimp.lib /DELAYLOAD:winfsp-x64.dll || exit /b 1
rc /nologo /i res /i src /fo build\obj\setup.res res\setup.rc || exit /b 1
cl %CF% /Fe:build\setup.exe src\setup.cpp build\obj\setup.res /link /SUBSYSTEM:WINDOWS || exit /b 1
exit /b 0
