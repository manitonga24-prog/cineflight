@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo ============================================================
echo   CineFlight Solo - Installation de Git (une seule fois)
echo ============================================================
echo.
where git >nul 2>nul
if errorlevel 1 (
  echo [X] Git n'est pas installe sur ce PC.
  echo     Telecharge-le sur https://git-scm.com puis relance ce fichier.
  echo.
  pause
  exit /b 1
)
git rev-parse --is-inside-work-tree >nul 2>nul
if errorlevel 1 (
  echo - Initialisation du depot...
  git init
) else (
  echo - Depot deja initialise.
)
git config user.name "Christian"
git config user.email "manitonga24@gmail.com"
git config core.autocrlf true
echo - Ajout du code source (les gros fichiers sont ignores par .gitignore)...
git add .
echo - Enregistrement de la version initiale...
git commit -m "Version initiale - code source CineFlight Solo (Etape 1 suivi RTK relatif au cap)"
echo.
echo ============================================================
echo   Termine. Derniere version enregistree :
echo ============================================================
git log --oneline -1
echo.
echo Fichiers suivis : 
git ls-files | find /c /v ""
echo.
pause
