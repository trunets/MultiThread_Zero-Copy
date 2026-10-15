# Run this script from the MultiThread_Zero-Copy project root.
# Requires the server to be running and PowerShell on Windows.
$ErrorActionPreference = "Stop"

$files = 1..10 | ForEach-Object { "test{0:D2}.bin" -f $_ }
New-Item -ItemType Directory -Force -Path "output" | Out-Null

$processes = foreach ($file in $files) {
    $destination = Join-Path "output" $file
    Start-Process -FilePath "java" `
        -ArgumentList @("-cp", "src", "Client", $file, $destination, "1", "NIO") `
        -WorkingDirectory (Get-Location).Path `
        -NoNewWindow `
        -PassThru
}

$processes | Wait-Process

Write-Host "`nAll 10 download processes have finished."
Write-Host "Downloaded files are in .\output\"
