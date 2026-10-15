param([string]$AndroidSdk='D:\SDK\Android',[string]$JavaHome='D:\JDK',
      [string]$BuildDir=(Join-Path $PSScriptRoot '..\..\..\ui-test-build'),
      [string]$SigningKey=(Join-Path $PSScriptRoot '..\..\..\build-public-v030\local-test.keystore'))
$ErrorActionPreference='Stop'
$BuildDir=[IO.Path]::GetFullPath($BuildDir)
$tools=Join-Path $AndroidSdk 'build-tools\35.0.0'
$android=Join-Path $AndroidSdk 'platforms\android-35\android.jar'
function Run([string]$exe,[string[]]$arguments){& $exe @arguments;if($LASTEXITCODE -ne 0){throw "Test build failed: $exe"}}
New-Item -ItemType Directory -Force -Path "$BuildDir\classes","$BuildDir\dex" | Out-Null
Run "$tools\aapt2.exe" @('link','-o',"$BuildDir\resources.apk",'-I',$android,'--manifest',"$PSScriptRoot\AndroidManifest.xml")
Run "$JavaHome\bin\javac.exe" @('-J-Duser.language=en','-encoding','UTF-8','-source','8','-target','8','-classpath',$android,'-d',"$BuildDir\classes","$PSScriptRoot\UiSmoke.java")
Run "$JavaHome\bin\jar.exe" @('cf',"$BuildDir\tests.jar",'-C',"$BuildDir\classes",'.')
Run "$JavaHome\bin\java.exe" @('-cp',"$tools\lib\d8.jar",'com.android.tools.r8.D8','--min-api','26','--lib',$android,'--output',"$BuildDir\dex","$BuildDir\tests.jar")
Copy-Item -LiteralPath "$BuildDir\resources.apk" -Destination "$BuildDir\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open("$BuildDir\unsigned.apk",[IO.Compression.ZipArchiveMode]::Update)
try{[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$BuildDir\dex\classes.dex",'classes.dex',[IO.Compression.CompressionLevel]::Optimal)|Out-Null}finally{$zip.Dispose()}
Run "$tools\zipalign.exe" @('-f','4',"$BuildDir\unsigned.apk","$BuildDir\aligned.apk")
Run "$JavaHome\bin\java.exe" @('-jar',"$tools\lib\apksigner.jar",'sign','--ks',$SigningKey,'--ks-key-alias','classroom-local','--ks-pass','pass:android','--key-pass','pass:android','--out',"$BuildDir\ui-tests.apk","$BuildDir\aligned.apk")
