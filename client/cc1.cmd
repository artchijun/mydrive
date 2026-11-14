@echo off
rem compile one file only (cc1 src\cloud.cpp)
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cd /d "%~dp0"
if not exist build\obj mkdir build\obj
cl /nologo /std:c++20 /O2 /EHsc /utf-8 /W3 /MT /DUNICODE /D_UNICODE /D_CRT_SECURE_NO_WARNINGS /Fobuild\obj\ /c %1
