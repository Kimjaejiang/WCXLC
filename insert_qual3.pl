use utf8;
use strict;
my $f = "app/src/main/java/com/Johnny/wcx/features/items/system/ForceTabletMode.kt";
open my $in, "<:encoding(UTF-8)", $f or die "read fail: $!";
local $/;
my $s = <$in>;
close $in;

my $anchor1 = "            usingEqStrings(\"MicroMsg.CgiCheckLoginAsPad\", \"/cgi-bin/micromsg-bin/checkloginaspad\")\n        }\n    }\n";
my $insert1 = $anchor1 .
"    // DexKit \x{e8}\x{bf}\x{9b}\x{e7}\x{a8}\x{8b}\x{e5}\x{86}\x{85}\x{e6}\x{a3}\x{80}\x{e7}\x{b4}\x{a2}\x{22}\x{e8}\x{af}\x{a5}\x{e8}\x{b4}\x{a6}\x{e6}\x{88}\x{b7}\x{e5}\x{b0}\x{9a}\x{e6}\x{9c}\x{aa}\x{e8}\x{8e}\x{b7}\x{e5}\x{8f}\x{96}\x{e4}\x{bd}\x{93}\x{e9}\x{aa}\x{8c}\x{e8}\x{b5}\x{84}\x{e6}\x{a0}\x{bc}\x{22}\x{e6}\x{8f}\x{90}\x{e7}\x{a4}\x{ba}\x{e6}\x{96}\x{b9}\x{e6}\x{b3}\x{95}\x{ef}\x{bc}\x{88}\x{e5}\x{be}\x{ae}\x{e4}\x{bf}\x{a1}\x{e6}\x{b7}\x{b7}\x{e6}\x{b7}\x{86}\x{ef}\x{bc}\x{8c}\x{e4}\x{b8}\x{8d}\x{e7}\x{a1}\x{ac}\x{e7}\x{bc}\x{96}\x{e7}\x{a0}\x{81}\x{e7}\x{b1}\x{bb}\x{e5}\x{90}\x{8d}\x{ef}\x{bc}\x{89}\x{ef}\x{bc}\x{8c}\r\n" .
"    // hook \x{e8}\x{b7}\x{b3}\x{e8}\x{bf}\x{87}\x{e4}\x{bb}\x{a5}\x{e9}\x{81}\x{bf}\x{e5}\x{85}\x{8d}\x{e5}\x{b9}\x{b3}\x{e6}\x{9d}\x{bf}\x{e6}\x{a8}\x{a1}\x{e5}\x{bc}\x{8f}\x{e4}\x{b8}\x{8b}\x{e6}\x{97}\x{a0}\x{e8}\x{b5}\x{84}\x{e6}\x{a0}\x{bc}\x{e6}\x{8f}\x{90}\x{e7}\x{a4}\x{ba}\x{e6}\x{89}\x{93}\x{e6}\x{96}\x{ad}\x{e4}\x{bd}\x{bf}\x{e7}\x{94}\x{a8}\x{e3}\x{80}\x{82}allowFailure \x{e4}\x{bf}\x{9d}\x{e8}\x{af}\x{81}\x{e6}\x{a3}\x{80}\x{e7}\x{b4}\x{a2}\x{e5}\x{a4}\x{b1}\x{e8}\x{b4}\x{a5}\x{e6}\x{97}\x{b6}\x{e9}\x{9d}\x{99}\x{e9}\x{bb}\x{98}\x{e9}\x{99}\x{8d}\x{e7}\x{ba}\x{a7}\x{e3}\x{80}\x{82}\n" .
"    private val methodNoQualificationTip by dexMethod(allowFailure = true) {\n" .
"        matcher {\n" .
"            usingEqStrings(\"\x{e8}\x{af}\x{a5}\x{e8}\x{b4}\x{a6}\x{e6}\x{88}\x{b7}\x{e5}\x{b0}\x{9a}\x{e6}\x{9c}\x{aa}\x{e8}\x{8e}\x{b7}\x{e5}\x{8f}\x{96}\x{e4}\x{bd}\x{93}\x{e9}\x{aa}\x{8c}\x{e8}\x{b5}\x{84}\x{e6}\x{a0}\x{bc}\"\x{ef}\x{bc}\x{89}\n" .
"        }\n" .
"    }\n";
my $pos = index($s, $anchor1);
if ($pos < 0) { die "anchor1 not found"; }
substr($s, $pos, length($anchor1), $insert1);

my $anchor2 = "        // methodCgiCheckLoginAsPad.hookBefore { result = true }\n";
my $insert2 = $anchor2 .
"\n" .
"        methodNoQualificationTip.hookBefore {\n" .
"            result = null\n" .
"        }\n";
$pos = index($s, $anchor2);
if ($pos < 0) { die "anchor2 not found"; }
substr($s, $pos, length($anchor2), $insert2);

open my $out, ">:encoding(UTF-8)", $f or die "write fail: $!";
print $out $s;
close $out;
print "ok\n";
