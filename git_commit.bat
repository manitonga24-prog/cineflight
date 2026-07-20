@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================================
echo   CineFlight Solo - Enregistrer une nouvelle version
echo ============================================================
echo.
set /p MSG="Decris en quelques mots ce que tu as change : "
if "%MSG%"=="" set MSG=Sauvegarde
git add .
git commit -m "%MSG%"
echo.
echo Version enregistree. Historique recent :
git log --oneline -5
echo.
pause
