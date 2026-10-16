#!/usr/bin/env perl
# 从 Controller 代码导出「接口契约清单」（给前端）。
#
# 为什么要有这个脚本：接口清单若靠手抄（03 文档 → 前端），几轮迭代后必然与代码漂移，
# 而前端拿到过期契约的代价是"联调时逐条试错"。这里直接从 @XxxMapping + 权限注解生成，
# 保证**清单与代码同源**；每次后端加接口，重跑一次即可。
#
# 用法：
#   perl scripts/export-api-contract.pl > docs/frontend/api-contract.md
use strict;
use warnings;
use File::Find;

my $root = 'wms-core/src/main/java';
my @files;
find(sub { push @files, $File::Find::name if /Controller\.java$/ }, $root);

my @rows;
for my $file (sort @files) {
    open(my $fh, '<:encoding(UTF-8)', $file) or die "无法读取 $file: $!";
    my @lines = <$fh>;
    close $fh;

    # 1. 类级基础路径
    my $base = '';
    for my $l (@lines) {
        if ($l =~ /\@RequestMapping\(\s*"([^"]+)"/) { $base = $1; last; }
    }
    # 类级权限声明（作用于该 Controller 全部接口）
    # 注意：`@PublicApi` 里的 @ 在 perl 双引号/正则会触发数组插值，必须写成 \@PublicApi；
    #      这个字面量在文件里应当保持"反斜杠 + @PublicApi"的形态（勿被 sed 吃掉反斜杠）。
    my $classPerm = '';
    for my $l (@lines) {
        if ($l =~ /\@RequiresPermission\((PermissionCodes\.[A-Z_0-9]+)\)/) { $classPerm = $1; last; }
        if ($l =~ /\@PublicApi\(/) { $classPerm = 'PublicApi'; last; }
    }

    # 2. 逐个方法：向上回溯最近的权限注解
    for my $i (0 .. $#lines) {
        my $line = $lines[$i];
        next unless $line =~ /\@(Get|Post|Put|Patch|Delete)Mapping/;
        my ($verb) = ($1);
        my $sub = '';
        if ($line =~ /\@\w+Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/) { $sub = $1; }

        # 回溯 8 行找权限注解（@RequiresPermission / @PublicApi）
        my $perm = '';
        for my $j (reverse (($i - 8 < 0 ? 0 : $i - 8) .. $i)) {
            if ($lines[$j] =~ /\@RequiresPermission\((PermissionCodes\.[A-Z_0-9]+)\)/) {
                $perm = $1; last;
            }
            if ($lines[$j] =~ /\@PublicApi\(/) { $perm = 'PublicApi'; last; }
        }
        $perm = $classPerm if $perm eq '';
        $perm = '（未声明）' if $perm eq '';

        my $path = $base . $sub;
        $path =~ s{//+}{/}g;
        push @rows, [ uc($verb), $path, $perm ];
    }
}

print "# 接口契约清单（自动生成，请勿手改）\n\n";
print "> 生成命令：`perl scripts/export-api-contract.pl > docs/frontend/api-contract.md`\n";
print "> 来源：`wms-core/src/main/java/**/*Controller.java` 的映射注解与权限注解。\n";
print "> 字段口径（请求头/成功码/错误码/时间格式/分页）见 `W5-FRONTEND-KICKOFF.md`。\n\n";
print "| 方法 | 路径 | 权限码 |\n|---|---|---|\n";
for my $r (@rows) {
    print "| $r->[0] | `$r->[1]` | `$r->[2]` |\n";
}
print "\n共 " . scalar(@rows) . " 个接口。\n";
