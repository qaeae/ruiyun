#!/usr/bin/env perl
# 从 Controller 代码导出「接口契约清单」（给前端）。
#
# 为什么要有这个脚本：接口清单若靠手抄（03 文档 → 前端），几轮迭代后必然与代码漂移，
# 而前端拿到过期契约的代价是"联调时逐条试错"。这里直接从 @XxxMapping + 权限注解生成，
# 保证**清单与代码同源**；每次后端加接口，重跑一次即可。
#
# 扫描范围（2026-10-16 修复）：**全部模块**的 src/main/java，而不是只扫 wms-core。
#   为什么必须修：P0 把每个业务域拆成独立 Maven 模块后，`wms-core/src/main/java` 下
#   已不再有任何 Controller（它只放启动类、横切配置与迁移脚本），脚本却仍在扫那个目录
#   → 生成结果变成「共 0 个接口」，与本文件提交的清单永久不一致，CI 的
#   「接口契约清单是否与代码一致」这一道门会**每次推送都失败**（而且是"看起来像忘了重生成"，
#   极易被误判为懒人问题，实际是扫描根写错了）。
#
# 收录口径：只收录 `/api/**` 的业务接口。网关自身的 /healthz、Actuator 的 /actuator/**
#   都不是前端契约面，混进来只会让清单噪声变大（前端按清单写代码时无从判断该不该调）。
#
# 用法：
#   perl scripts/export-api-contract.pl > docs/frontend/api-contract.md
use strict;
use warnings;
use File::Find;

# 1. 扫描根：所有模块的 src/main/java（排除 target 产物目录）
my @roots = grep { -d $_ } glob('wms-*/src/main/java');
die "未找到任何 wms-*/src/main/java 目录（请在仓库根目录执行本脚本）\n" unless @roots;

my @files;
for my $root (@roots) {
    find(sub { push @files, $File::Find::name if /Controller\.java$/ }, $root);
}

my @rows;
for my $file (sort @files) {
    open(my $fh, '<:encoding(UTF-8)', $file) or die "无法读取 $file: $!\n";
    my @lines = <$fh>;
    close $fh;

    # 2. 类级基础路径
    my $base = '';
    for my $l (@lines) {
        if ($l =~ /\@RequestMapping\(\s*"([^"]+)"/) { $base = $1; last; }
    }

    # 3. 类级权限声明：只在**第一个方法映射之前**的注解块里找
    #    （否则类上没有声明时会误把第一个方法的权限当成类级默认值，掩盖"该方法漏标"）
    my $classPerm = '';
    for my $l (@lines) {
        last if $l =~ /\@(Get|Post|Put|Patch|Delete)Mapping/;
        # 注意：`@PublicApi` 里的 @ 在 perl 双引号/正则会触发数组插值，必须写成 \@PublicApi；
        #      这个字面量在文件里应当保持"反斜杠 + @PublicApi"的形态（勿被 sed 吃掉反斜杠）。
        if ($l =~ /\@RequiresPermission\((PermissionCodes\.[A-Z_0-9]+)\)/) { $classPerm = $1; last; }
        if ($l =~ /\@PublicApi\(/) { $classPerm = 'PublicApi'; last; }
    }

    # 4. 逐个方法：向上回溯最近的权限注解
    for my $i (0 .. $#lines) {
        my $line = $lines[$i];
        next unless $line =~ /\@(Get|Post|Put|Patch|Delete)Mapping/;
        my ($verb) = ($1);
        my $sub = '';
        if ($line =~ /\@\w+Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/) { $sub = $1; }

        # 5. 找该方法**注解块**里的权限声明。
        #    为什么不能只向上回溯：本仓库两种写法都存在——
        #      a) @RequiresPermission 在 @PostMapping **之前**（多数）；
        #      b) @RequiresPermission 在 @PostMapping **之后**（PricingController / ShipmentProofController）。
        #    只向上找会把 (b) 判成「（未声明）」，而清单是给前端看的：一个"其实有权限"的接口
        #    被标成未声明，前端会以为不需要授权、评审会以为漏标——两边都被误导。
        #    做法：先向下定位方法签名行（注解块到此结束），再在 [签名行-12, 签名行) 区间内找。
        my $sig = $i;
        while ($sig <= $#lines && $lines[$sig] !~ /^\s*(public|private|protected)\s/) {
            $sig++;
        }
        my $from = $sig - 12 < 0 ? 0 : $sig - 12;
        my $perm = '';
        for my $j (reverse ($from .. ($sig <= $#lines ? $sig - 1 : $#lines))) {
            if ($lines[$j] =~ /\@RequiresPermission\((PermissionCodes\.[A-Z_0-9]+)\)/) {
                $perm = $1; last;
            }
            if ($lines[$j] =~ /\@PublicApi\(/) { $perm = 'PublicApi'; last; }
        }
        $perm = $classPerm if $perm eq '';
        $perm = '（未声明）' if $perm eq '';

        my $path = $base . $sub;
        $path =~ s{//+}{/}g;
        # 只收录面向调用方的业务接口面（前端契约）
        next unless $path =~ m{^/api/};
        push @rows, [ uc($verb), $path, $perm ];
    }
}

# 6. 输出：去重 + 按路径排序。
#    为什么要排序：生成的产物必须**确定性**——若顺序取决于文件扫描顺序，加一个模块就可能让
#    整份清单行序大挪，CI 的 diff 检查会红一大片，真实漂移反而被淹没。
#    去重同理：同一路径重复声明会让前端误以为是两个接口。
my %seen;
@rows = grep { !$seen{"$_->[0] $_->[1]"}++ } @rows;
@rows = sort { $a->[1] cmp $b->[1] || $a->[0] cmp $b->[0] } @rows;

print "# 接口契约清单（自动生成，请勿手改）\n\n";
print "> 生成命令：`perl scripts/export-api-contract.pl > docs/frontend/api-contract.md`\n";
print "> 来源：各模块 `wms-*/src/main/java/**/*Controller.java` 的映射注解与权限注解，仅收录 `/api/**` 业务接口。\n";
print "> 字段口径（请求头/成功码/错误码/时间格式/分页）见 `W5-FRONTEND-KICKOFF.md`。\n\n";
print "| 方法 | 路径 | 权限码 |\n|---|---|---|\n";
for my $r (@rows) {
    print "| $r->[0] | `$r->[1]` | `$r->[2]` |\n";
}
print "\n共 " . scalar(@rows) . " 个接口。\n";
