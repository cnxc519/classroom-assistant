param(
    [string]$AndroidSdk = 'D:\SDK\Android',
    [string]$JavaHome = 'D:\JDK',
    [string]$DependencyDir = (Join-Path $PSScriptRoot '..\asr-upgrade'),
    [string]$BuildDir = (Join-Path $PSScriptRoot '..\build-public-v030'),
    [string]$OutputApk = (Join-Path $PSScriptRoot '..\..\outputs\Classroom-Assistant-v0.3.1.apk')
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'assets\personal-setup.json')) { throw 'Public builds must not contain a personal preset.' }
$DependencyDir = [IO.Path]::GetFullPath($DependencyDir)
$BuildDir = [IO.Path]::GetFullPath($BuildDir)
$OutputApk = [IO.Path]::GetFullPath($OutputApk)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$tools = Join-Path $AndroidSdk 'build-tools\35.0.0'
$androidJar = Join-Path $AndroidSdk 'platforms\android-35\android.jar'
foreach ($path in @($androidJar, (Join-Path $JavaHome 'bin\javac.exe'), (Join-Path $tools 'aapt2.exe'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing build tool: $path" }
}
function Run-Native([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build command failed: $Exe (exit $LASTEXITCODE)" }
}
New-Item -ItemType Directory -Force -Path $DependencyDir,$BuildDir,(Split-Path $OutputApk),"$BuildDir\classes","$BuildDir\gen","$BuildDir\dex","$PSScriptRoot\assets" | Out-Null
$deps=@(
    @('sherpa-jvm.jar','https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-jvm-1.13.8.jar'),
    @('sherpa-android.tar.bz2','https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android.tar.bz2'),
    @('sensevoice-mandarin-int8.tar.bz2','https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2'),
    @('silero_vad.onnx','https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx')
)
$expected=Get-Content -LiteralPath "$PSScriptRoot\dependency-hashes.json" -Raw | ConvertFrom-Json
foreach ($dep in $deps) {
    $file=Join-Path $DependencyDir $dep[0]
    if (-not (Test-Path -LiteralPath $file)) { Invoke-WebRequest -Uri $dep[1] -OutFile $file -TimeoutSec 600 }
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash -ne $expected.($dep[0])) { throw "Dependency checksum mismatch: $file" }
}
if (-not (Test-Path -LiteralPath "$DependencyDir\jniLibs\arm64-v8a\libsherpa-onnx-jni.so")) { Run-Native 'tar.exe' @('-xjf',"$DependencyDir\sherpa-android.tar.bz2",'-C',$DependencyDir) }
$modelRoot=Join-Path $DependencyDir 'sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17'
if (-not (Test-Path -LiteralPath "$modelRoot\model.int8.onnx")) { Run-Native 'tar.exe' @('-xjf',"$DependencyDir\sensevoice-mandarin-int8.tar.bz2",'-C',$DependencyDir) }
$modelZip=Join-Path $PSScriptRoot 'assets\sensevoice-model.zip'
if (-not (Test-Path -LiteralPath $modelZip)) {
    $zip=[IO.Compression.ZipFile]::Open($modelZip,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in @('model.int8.onnx','tokens.txt')) { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$modelRoot\$name",$name,[IO.Compression.CompressionLevel]::Optimal) | Out-Null }
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$DependencyDir\silero_vad.onnx",'silero_vad.onnx',[IO.Compression.CompressionLevel]::Optimal) | Out-Null
    } finally { $zip.Dispose() }
}
Run-Native "$tools\aapt2.exe" @('compile','--dir',"$PSScriptRoot\res",'-o',"$BuildDir\resources.zip")
Run-Native "$tools\aapt2.exe" @('link','-o',"$BuildDir\resources.apk",'-I',$androidJar,'--manifest',"$PSScriptRoot\AndroidManifest.xml",'--java',"$BuildDir\gen",'-A',"$PSScriptRoot\assets","$BuildDir\resources.zip")
$javaFiles = @(Get-ChildItem -LiteralPath "$PSScriptRoot\src","$BuildDir\gen" -Filter '*.java' -Recurse | ForEach-Object FullName)
$sourceList = Join-Path $BuildDir 'sources.txt'
$javaFiles | ForEach-Object { '"' + $_.Replace('\','/') + '"' } | Set-Content -LiteralPath $sourceList -Encoding utf8
Run-Native "$JavaHome\bin\javac.exe" @('-J-Duser.language=en','-encoding','UTF-8','-source','8','-target','8','-classpath',"$androidJar;$DependencyDir\sherpa-jvm.jar",'-d',"$BuildDir\classes","@$sourceList")
Run-Native "$JavaHome\bin\jar.exe" @('cf',"$BuildDir\app.jar",'-C',"$BuildDir\classes",'.')
Run-Native "$JavaHome\bin\java.exe" @('-cp',"$tools\lib\d8.jar",'com.android.tools.r8.D8','--min-api','26','--lib',$androidJar,'--output',"$BuildDir\dex","$BuildDir\app.jar","$DependencyDir\sherpa-jvm.jar")
$resourceArchive = [IO.Compression.ZipFile]::OpenRead("$BuildDir\resources.apk")
$apkStream = [IO.File]::Create("$BuildDir\unsigned.apk")
$archive = [IO.Compression.ZipArchive]::new($apkStream,[IO.Compression.ZipArchiveMode]::Create)
try {
    # Windows aapt2 can emit backslashes in nested asset local headers. Re-create every
    # entry with canonical ZIP paths; changing only the central directory is insufficient.
    foreach ($resource in $resourceArchive.Entries) {
        $compression=if($resource.FullName -eq 'resources.arsc' -or $resource.CompressedLength -eq $resource.Length){[IO.Compression.CompressionLevel]::NoCompression}else{[IO.Compression.CompressionLevel]::Optimal}
        $entry=$archive.CreateEntry($resource.FullName.Replace('\','/'),$compression)
        $input=$resource.Open();$output=$entry.Open()
        try {$input.CopyTo($output)} finally {$input.Dispose();$output.Dispose()}
    }
    foreach ($dex in (Get-ChildItem -LiteralPath "$BuildDir\dex" -Filter '*.dex')) {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $dex.FullName, $dex.Name, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
    foreach ($abi in @('arm64-v8a','x86_64')) {
        foreach ($library in @('libonnxruntime.so','libsherpa-onnx-jni.so')) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,"$DependencyDir\jniLibs\$abi\$library","lib/$abi/$library",[IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    }
} finally { $archive.Dispose();$resourceArchive.Dispose();$apkStream.Dispose() }
Run-Native "$tools\zipalign.exe" @('-P','16','-f','4',"$BuildDir\unsigned.apk","$BuildDir\aligned.apk")
$keystore = Join-Path $BuildDir 'local-test.keystore'
if (-not (Test-Path -LiteralPath $keystore)) {
    Run-Native "$JavaHome\bin\keytool.exe" @('-genkeypair','-keystore',$keystore,'-storepass','android','-keypass','android','-alias','classroom-local','-dname','CN=Classroom Local Test','-keyalg','RSA','-keysize','2048','-validity','3650')
}
Run-Native "$JavaHome\bin\java.exe" @('-jar',"$tools\lib\apksigner.jar",'sign','--ks',$keystore,'--ks-key-alias','classroom-local','--ks-pass','pass:android','--key-pass','pass:android','--out',$OutputApk,"$BuildDir\aligned.apk")
Run-Native "$JavaHome\bin\java.exe" @('-jar',"$tools\lib\apksigner.jar",'verify','--verbose',$OutputApk)
Run-Native "$JavaHome\bin\javac.exe" @('-encoding','UTF-8','-d',"$BuildDir\tests","$PSScriptRoot\src\cn\classroom\assistant\RecentBuffer.java","$PSScriptRoot\tests\RecentBufferTest.java")
Run-Native "$JavaHome\bin\java.exe" @('-cp',"$BuildDir\tests",'cn.classroom.assistant.RecentBufferTest')
Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256
