@echo off
setlocal
where cl >nul 2>nul || (echo run vcvars64.bat first & exit /b 1)

cl /nologo /std:c++20 /O2 /EHsc /W3 /D_WIN32_WINNT=0x0A00 ^
   main.cpp offset_resolver.cpp keyring.cpp read_queue.cpp ^
   aim.cpp power.cpp crosshair.cpp antitamper.cpp etw_guard.cpp ^
   /Fe:8bp.exe /link user32.lib gdi32.lib
if errorlevel 1 exit /b 1

cl /nologo /std:c++20 /O2 /EHsc /W3 /D_WIN32_WINNT=0x0A00 pe_hardening.cpp /Fe:peh.exe
if errorlevel 1 exit /b 1

peh.exe 8bp.exe 8bp.hardened.exe 64

cl /nologo /std:c++20 /O2 /EHsc /W3 /D_WIN32_WINNT=0x0A00 ^
   selftest.cpp offset_resolver.cpp keyring.cpp read_queue.cpp aim.cpp power.cpp ^
   antitamper.cpp etw_guard.cpp /Fe:8bp_test.exe /link user32.lib
if errorlevel 1 exit /b 1
8bp_test.exe
