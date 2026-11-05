# Publishes a new version that friends' apps pick up as an in-app update.
# Usage: .\release.ps1 "What changed (first line shows in the update banner)"
param([string]$Notes = "Bug fixes and improvements")

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Write-Host "Install the GitHub CLI first: winget install GitHub.cli, then gh auth login" -ForegroundColor Red; exit 1 }
if (git status --porcelain) { Write-Host "Commit or stash your changes first, so the release matches the repo." -ForegroundColor Red; exit 1 }

# The app compares the release tag (v<versionCode>) with its own versionCode, so every release must bump it.
$manifest = Get-Content AndroidManifest.xml -Raw
$code = [int]([regex]::Match($manifest, 'android:versionCode="(\d+)"').Groups[1].Value) + 1
$name = "1.$code"
$manifest = $manifest -replace 'android:versionCode="\d+"', "android:versionCode=`"$code`"" -replace 'android:versionName="[^"]*"', "android:versionName=`"$name`""
[IO.File]::WriteAllText("$PWD\AndroidManifest.xml", $manifest)

powershell -ExecutionPolicy Bypass -File .\build.ps1
if ($LASTEXITCODE) { git checkout -- AndroidManifest.xml; exit 1 }

git commit -am "Release v$code"
git push
gh release create "v$code" balorant.apk --title "Balorant $name" --notes $Notes
if ($LASTEXITCODE) { exit 1 }
Write-Host "Released v$code. Apps on v$($code - 1) or older will offer the update on next launch." -ForegroundColor Green
