$ErrorActionPreference = 'Stop'
$root = 'C:\Users\Dell\MonkeyCode\WXPRO\app\src\main\java\com\Johnny\wcx'
$files = Get-ChildItem -Path $root -Recurse -Filter *.kt | Where-Object { $_.FullName -notmatch '\\build\\' -and $_.Name -ne '_audit_matchers.ps1' }
$out = @()
foreach ($f in $files) {
    $lines = Get-Content $f.FullName
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match 'by dex(Class|Method|Field|Constructor)') {
            $decl = $lines[$i].Trim()
            $end = [Math]::Min($i + 16, $lines.Count - 1)
            $block = $lines[$i..$end] -join "`n"
            $allow = $block -match 'allowFailure\s*=\s*true'
            # extract key strings from the block
            $strings = [System.Collections.Generic.List[string]]::new()
            foreach ($m in [regex]::Matches($block, '(?:usingEqStrings|usingStrings|usingExactString|usingFieldName|usingMethodName|usingInvoke|usingUniqueMethod)\(([^)]*)\)')) {
                $strings.Add($m.Groups[1].Value)
            }
            foreach ($m in [regex]::Matches($block, 'searchPackages\("([^"]+)"\)')) {
                $strings.Add('pkg:' + $m.Groups[1].Value)
            }
            foreach ($m in [regex]::Matches($block, 'declaredClass\([^)]*method\.declaringClass\)')) {
                $strings.Add('declaredClassOf:method')
            }
            if ($strings.Count -gt 0) {
                $ks = ($strings | Select-Object -Unique) -join ' | '
            } else {
                $ks = '(no strings)'
            }
            $rel = $f.FullName.Substring($root.Length + 1)
            $out += "$rel`t$($i+1)`t$allow`t$decl`t$ks"
        }
    }
}
$noallow = $out | Where-Object { $_ -match "`tFalse`t" }
$noallow | Out-File -FilePath 'C:\Users\Dell\MonkeyCode\WXPRO\app\src\main\java\com\Johnny\wcx\_audit_noallow.tsv' -Encoding utf8
Write-Output "total=$($out.Count) noallow=$($noallow.Count)"
