# Android araç zincirini kurar: JDK 17, Android SDK (cmdline-tools, platform, build-tools), Gradle.
# İndirmeler C:\Android\downloads altına, kurulum C:\Android altına yapılır.
$ErrorActionPreference = "Stop"

$root = "C:\Android"
$dl = Join-Path $root "downloads"
New-Item -ItemType Directory -Force -Path $dl | Out-Null

function Expand-Zip($zip, $dest) {
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Expand-Archive -Path $zip -DestinationPath $dest -Force
}

# ---- JDK 17
if (-not (Test-Path "$root\jdk17")) {
    if (-not (Test-Path "$dl\jdk17.zip")) {
        curl.exe -L --retry 3 -o "$dl\jdk17.zip" "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
    }
    Expand-Zip "$dl\jdk17.zip" "$root\jdk17"
}
$jdk = (Get-ChildItem "$root\jdk17" -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"
Write-Host "JAVA_HOME=$jdk"
& java -version

# ---- Android cmdline-tools
if (-not (Test-Path "$root\sdk\cmdline-tools\latest\bin\sdkmanager.bat")) {
    if (-not (Test-Path "$dl\cmdline-tools.zip")) {
        curl.exe -L --retry 3 -o "$dl\cmdline-tools.zip" "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
    }
    Expand-Zip "$dl\cmdline-tools.zip" "$root\sdk\cmdline-tools-tmp"
    New-Item -ItemType Directory -Force -Path "$root\sdk\cmdline-tools\latest" | Out-Null
    Move-Item "$root\sdk\cmdline-tools-tmp\cmdline-tools\*" "$root\sdk\cmdline-tools\latest\" -Force
    Remove-Item "$root\sdk\cmdline-tools-tmp" -Recurse -Force
}
$env:ANDROID_HOME = "$root\sdk"
$sdkmanager = "$root\sdk\cmdline-tools\latest\bin\sdkmanager.bat"

# ---- SDK paketleri
& $sdkmanager --licenses | Out-Null
& $sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# ---- Gradle
if (-not (Test-Path "$root\gradle")) {
    if (-not (Test-Path "$dl\gradle.zip")) {
        curl.exe -L --retry 3 -o "$dl\gradle.zip" "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
    }
    Expand-Zip "$dl\gradle.zip" "$root\gradle"
}
$gradle = (Get-ChildItem "$root\gradle" -Directory | Select-Object -First 1).FullName

# ---- Proje ayarları
$project = Split-Path $PSScriptRoot -Parent
@"
sdk.dir=$($root -replace '\\','\\')
"@ | Set-Content -Encoding utf8 (Join-Path $project "local.properties")

Push-Location $project
& "$gradle\bin\gradle.bat" wrapper --gradle-version 8.11.1
Pop-Location

Write-Host ""
Write-Host "Kurulum tamam. Derlemek için: .\gradlew.bat assembleDebug"
Write-Host "Kalıcı ortam değişkenleri (yeni kabuklarda gerekli):"
Write-Host "  setx JAVA_HOME `"$jdk`""
Write-Host "  setx ANDROID_HOME `"$root\sdk`""