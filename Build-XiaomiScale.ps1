$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$sdk = $env:ANDROID_SDK_ROOT
$javaHome = $env:JAVA_HOME
$signer = $env:UBER_APK_SIGNER_JAR

if (-not $sdk) { throw 'ANDROID_SDK_ROOT is not set.' }
if (-not $javaHome) { throw 'JAVA_HOME is not set.' }
if (-not $signer -or -not (Test-Path -LiteralPath $signer)) { throw 'UBER_APK_SIGNER_JAR is not set or does not exist.' }

$androidJar = Join-Path $sdk 'platforms\android-27\android.jar'
if (-not (Test-Path -LiteralPath $androidJar)) { throw "Android Platform 27 is missing: $androidJar" }

$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { [version]($_.Name -replace '[^0-9.]', '') } -Descending |
    Select-Object -First 1
if (-not $buildTools) { throw 'Android Build Tools were not found.' }

$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
$d8Jar = Join-Path $buildTools.FullName 'lib\d8.jar'
$javac = Join-Path $javaHome 'bin\javac.exe'
$java = Join-Path $javaHome 'bin\java.exe'
$jar = Join-Path $javaHome 'bin\jar.exe'
$build = Join-Path $root 'build'

Remove-Item -LiteralPath $build -Recurse -Force -ErrorAction SilentlyContinue
$gen = New-Item -ItemType Directory -Force -Path (Join-Path $build 'gen')
$classes = New-Item -ItemType Directory -Force -Path (Join-Path $build 'classes')
$dex = New-Item -ItemType Directory -Force -Path (Join-Path $build 'dex')
$testClasses = New-Item -ItemType Directory -Force -Path (Join-Path $build 'test')
$out = New-Item -ItemType Directory -Force -Path (Join-Path $build 'out')

& $aapt package -f -m -J $gen.FullName -M (Join-Path $root 'AndroidManifest.xml') -S (Join-Path $root 'res') -I $androidJar -F (Join-Path $build 'resources.ap_')
if ($LASTEXITCODE) { throw 'aapt failed.' }

$sources = Get-ChildItem -LiteralPath (Join-Path $root 'src') -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
$rJava = Join-Path $gen.FullName 'com\codex\xiaomiscale\R.java'
& $javac -source 8 -target 8 '-Xlint:-options' -encoding UTF-8 -classpath $androidJar -d $classes.FullName @sources $rJava
if ($LASTEXITCODE) { throw 'javac failed.' }

$parser = Join-Path $root 'src\com\codex\xiaomiscale\MiScaleParser.java'
$parserTest = Join-Path $root 'test\com\codex\xiaomiscale\MiScaleParserTest.java'
& $javac -source 8 -target 8 '-Xlint:-options' -encoding UTF-8 -d $testClasses.FullName $parser $parserTest
if ($LASTEXITCODE) { throw 'Parser test compilation failed.' }
& $java -classpath $testClasses.FullName com.codex.xiaomiscale.MiScaleParserTest
if ($LASTEXITCODE) { throw 'Parser tests failed.' }

$classesJar = Join-Path $build 'classes.jar'
& $jar cf $classesJar -C $classes.FullName .
& $java -cp $d8Jar com.android.tools.r8.D8 --min-api 21 --lib $androidJar --output $dex.FullName $classesJar
if ($LASTEXITCODE) { throw 'D8 failed.' }

$unsigned = Join-Path $build 'unsigned.apk'
Copy-Item -LiteralPath (Join-Path $build 'resources.ap_') -Destination $unsigned
Push-Location $dex.FullName
try { & $aapt add $unsigned 'classes.dex' | Out-Null } finally { Pop-Location }
if ($LASTEXITCODE) { throw 'Unable to add classes.dex.' }

$aligned = Join-Path $build 'aligned.apk'
& $zipalign -f 4 $unsigned $aligned
if ($LASTEXITCODE) { throw 'zipalign failed.' }
& $java -jar $signer -a $aligned --out $out.FullName --allowResign
if ($LASTEXITCODE) { throw 'APK signing failed.' }

Write-Host "Build completed: $($out.FullName)"
