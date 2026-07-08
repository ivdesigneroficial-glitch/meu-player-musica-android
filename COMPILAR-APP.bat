@echo off
chcp 65001 >nul
title Compilar Meu Player de Musica
color 0b

echo ============================================================
echo   COMPILANDO O APP "MEU PLAYER DE MUSICA"
echo   (a primeira vez baixa dependencias e pode demorar)
echo ============================================================
echo.

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
set "ANDROID_HOME=C:\Android\sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE=C:\Android\gradle\gradle-8.7\bin\gradle.bat"

cd /d "%~dp0"

echo Iniciando compilacao...
echo.
call "%GRADLE%" assembleDebug --console=plain > "%~dp0build-log.txt" 2>&1

set APK=%~dp0app\build\outputs\apk\debug\app-debug.apk

if exist "%APK%" (
    echo.
    echo ============================================================
    echo   SUCESSO! APK gerado.
    echo ============================================================
    copy /Y "%APK%" "%USERPROFILE%\Downloads\Meu Player de Musica.apk" >nul
    echo.
    echo O arquivo foi copiado para:
    echo   %USERPROFILE%\Downloads\Meu Player de Musica.apk
    echo.
    echo Passe esse arquivo .apk para o seu celular e instale.
    echo ^(No celular, permita "instalar apps de fontes desconhecidas"^)
    echo.
) else (
    echo.
    echo ============================================================
    echo   ERRO na compilacao.
    echo ============================================================
    echo Abra o arquivo build-log.txt nesta pasta e me envie o conteudo.
    echo Caminho: %~dp0build-log.txt
    echo.
)

echo.
pause
