#!/usr/bin/perl
# 从 Kotlin 源码提取 usingEqStrings/usingStrings 的字符串字面量，正确处理转义。
# 输出：每个字面量一行（已反转义），去重排序。
use strict;
use warnings;

# 输出按 UTF-8 编码，避免 "Wide character in print" 警告
binmode(STDOUT, ':encoding(UTF-8)');

my @files = @ARGV;
my %seen;

for my $file (@files) {
    open(my $fh, '<:encoding(UTF-8)', $file) or next;
    local $/;
    my $src = <$fh>;
    close($fh);

    # 匹配 usingStrings(...) / usingEqStrings(...) 的整段参数
    while ($src =~ /using(?:Eq)?Strings\s*\(([^;]*?)\)/gs) {
        my $args = $1;
        # 从参数里逐个抓双引号字面量，处理 \" \\ \n \t 等转义
        while ($args =~ /"((?:[^"\\]|\\.)*)"/gs) {
            my $lit = $1;
            # 反转义
            $lit =~ s/\\"/"/g;
            $lit =~ s/\\\\/\\/g;
            $lit =~ s/\\n/\n/g;
            $lit =~ s/\\t/\t/g;
            $lit =~ s/\\r/\r/g;
            # Kotlin 里 $ 可写作 ${'$'}，需还原为字面美元符
            my $dollar = "\$" . "{'\$'}";
            $lit =~ s/\Q$dollar\E/\$/g;
            next if $lit eq '';
            $seen{$lit} = 1;
        }
    }
}

print "$_\n" for sort keys %seen;
