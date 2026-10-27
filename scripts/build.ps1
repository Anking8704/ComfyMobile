param([switch]$TestOnly)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
$jdk = (Get-ChildItem -LiteralPath "$root\.tools\jdk" -Directory | Select-Object -First 1).FullName
if (!$jdk) { throw '请先运行 scripts/setup-tools.ps1' }
$javac = "$jdk\bin\javac.exe"
$java = "$jdk\bin\java.exe"
$tools = "$root\.tools\unpacked\build-tools\android-15"
$android = "$root\.tools\unpacked\platform\android-35\android.jar"
New-Item -ItemType Directory -Force -Path build/tests,build/classes,build/generated,build/dex,output | Out-Null
function Check-Exit([string]$step) { if ($LASTEXITCODE -ne 0) { throw "$step 失败 ($LASTEXITCODE)" } }
$core = 'ServerAddress','ServerConnection','PromptText','MediaRef','DraftState','SubmissionState','PresetMigration' | ForEach-Object { "app/src/main/java/local/comfymobile/$_.java" }
$tests = Get-ChildItem tests -Filter '*.java' | ForEach-Object FullName
& $javac -encoding UTF-8 -d build/tests @core @tests
Check-Exit '编译主机端测试'
foreach ($test in 'ServerAddressTest','ServerConnectionTest','PromptTextTest','MediaRefTest','DraftStateTest','SubmissionStateTest','PresetMigrationTest') {
    & $java '-Dfile.encoding=UTF-8' -cp build/tests "local.comfymobile.$test"
    Check-Exit $test
}
$jsTests=Get-ChildItem tests -Filter '*.test.cjs' | ForEach-Object FullName
& node --test @jsTests
Check-Exit '工作流适配测试'
if ($TestOnly) { exit 0 }
if (!(Test-Path -LiteralPath $android)) { throw 'Android SDK 不完整，请运行 scripts/setup-tools.ps1' }
& "$tools\aapt2.exe" compile --dir app/src/main/res -o build/resources.zip
Check-Exit '资源编译'
& "$tools\aapt2.exe" link -o build/resources.apk -I $android --manifest app/src/main/AndroidManifest.xml --java build/generated --min-sdk-version 29 --target-sdk-version 35 --version-code 2 --version-name '0.2.0' -A app/src/main/assets build/resources.zip
Check-Exit '资源链接'
$sources = Get-ChildItem app/src/main/java,build/generated -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
[System.IO.File]::WriteAllLines((Join-Path $root 'build/sources.txt'),[string[]]$sources,(New-Object System.Text.UTF8Encoding($false)))
& $javac -encoding UTF-8 --release 8 -classpath $android -d build/classes '@build/sources.txt'
Check-Exit 'Android Java 编译'
& "$jdk\bin\jar.exe" cf build/classes.jar -C build/classes .
Check-Exit '类打包'
& $java -cp "$tools\lib\d8.jar" com.android.tools.r8.D8 --min-api 29 --lib $android --output build/dex build/classes.jar
Check-Exit 'DEX 编译'
Copy-Item -LiteralPath build/resources.apk -Destination build/unsigned.apk -Force
& "$jdk\bin\jar.exe" uf build/unsigned.apk -C build/dex classes.dex
Check-Exit 'APK 打包'
& "$tools\zipalign.exe" -f 4 build/unsigned.apk build/aligned.apk
Check-Exit 'APK 对齐'
$keystore = "$root\.tools\comfymobile-dev.keystore"
if (!(Test-Path -LiteralPath $keystore)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $keystore -storepass android -keypass android -alias comfymobile -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=ComfyMobile Local Development'
    Check-Exit '创建本地开发签名'
}
& $java -jar "$tools\lib\apksigner.jar" sign --ks $keystore --ks-key-alias comfymobile --ks-pass pass:android --key-pass pass:android --out output/ComfyMobile-0.2.0.apk build/aligned.apk
Check-Exit 'APK 签名'
& $java -jar "$tools\lib\apksigner.jar" verify --verbose output/ComfyMobile-0.2.0.apk
Check-Exit '签名验证'
Get-FileHash -Algorithm SHA256 -LiteralPath output/ComfyMobile-0.2.0.apk | Format-List
