# Builds balorant.apk with the bare Android SDK tools (no Gradle). Usage: .\build.ps1
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = "$sdk\build-tools\35.0.0"
$jar = "$sdk\platforms\android-35\android.jar"
$jdk = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { (Get-ChildItem 'C:\Program Files\Java\jdk*' | Select-Object -Last 1).FullName }
function run { $rest = @($args[1..($args.Count - 1)] | ForEach-Object { $_ }); & $args[0] @rest; if ($LASTEXITCODE) { Write-Host "FAILED: $($args[0])" -ForegroundColor Red; exit 1 } }

Remove-Item build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory build\classes, build\dex | Out-Null

# targetSdk 34: avoids Android 15's forced edge-to-edge drawing under the status bar
run "$bt\aapt2.exe" compile --dir res -o build\res.zip
run "$bt\aapt2.exe" link -o build\base.apk -I $jar --manifest AndroidManifest.xml -A assets --min-sdk-version 24 --target-sdk-version 34 build\res.zip
run "$jdk\bin\javac.exe" --release 11 -nowarn -classpath $jar -d build\classes (Get-ChildItem src -Recurse -Filter *.java).FullName
run "$bt\d8.bat" --release --lib $jar --min-api 24 --output build\dex (Get-ChildItem build\classes -Recurse -Filter *.class).FullName
Push-Location build\dex; run "$bt\aapt.exe" add ..\base.apk classes.dex; Pop-Location
run "$bt\zipalign.exe" -f 4 build\base.apk build\aligned.apk

# Keep the same key across builds so updates install over the old version.
if (-not (Test-Path vala.keystore)) {
  run "$jdk\bin\keytool.exe" -genkeypair -keystore vala.keystore -alias vala -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=Vala"
}
run "$bt\apksigner.bat" sign --ks vala.keystore --ks-pass pass:android --out balorant.apk build\aligned.apk
Write-Host "Built balorant.apk" -ForegroundColor Green
