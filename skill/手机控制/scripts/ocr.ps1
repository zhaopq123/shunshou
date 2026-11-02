param(
  [Parameter(Mandatory = $true)][string]$Path,
  [Parameter(Mandatory = $true)][string]$Out
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
  })[0]

function Await($WinRtTask, $ResultType) {
  $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
  $netTask = $asTask.Invoke($null, @($WinRtTask))
  $netTask.Wait(-1) | Out-Null
  $netTask.Result
}

[Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null
[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null

$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
$stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if ($null -eq $engine) { throw 'NO_OCR_ENGINE' }
$result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])

$lines = @()
foreach ($line in $result.Lines) {
  $words = @($line.Words)
  if ($words.Count -eq 0) { continue }
  $x1 = [double]::MaxValue; $y1 = [double]::MaxValue; $x2 = 0; $y2 = 0
  foreach ($w in $words) {
    $r = $w.BoundingRect
    if ($r.X -lt $x1) { $x1 = $r.X }
    if ($r.Y -lt $y1) { $y1 = $r.Y }
    if (($r.X + $r.Width) -gt $x2) { $x2 = $r.X + $r.Width }
    if (($r.Y + $r.Height) -gt $y2) { $y2 = $r.Y + $r.Height }
  }
  $lines += [pscustomobject]@{
    text = $line.Text
    x1   = [int]$x1
    y1   = [int]$y1
    x2   = [int]$x2
    y2   = [int]$y2
    cx   = [int](($x1 + $x2) / 2)
    cy   = [int](($y1 + $y2) / 2)
  }
}

$payload = [pscustomobject]@{
  width  = [int]$bitmap.PixelWidth
  height = [int]$bitmap.PixelHeight
  lines  = $lines
}
$json = $payload | ConvertTo-Json -Depth 4 -Compress
[IO.File]::WriteAllText($Out, $json, [Text.UTF8Encoding]::new($false))
