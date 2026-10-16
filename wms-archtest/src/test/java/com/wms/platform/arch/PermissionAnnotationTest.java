package com.wms.platform.arch;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * 权限声明守护（W3 引入 RBAC 骨架时配套，W4 收口为"全覆盖"）。
 *
 * <p><b>为什么这条规则值得存在</b>：权限系统最典型的失效方式不是"判断写错了"，
 * 而是**新接口忘了加权限判断**。这类疏漏评审时几乎看不出来（代码看着挺正常），
 * 上线后就是一个越权入口。把"必须声明"变成 CI 阻断项之后，漏标会在**提交时就红**。
 *
 * <p><b>W4 收口（本次）</b>：规则从"只查写接口 + 白名单豁免"升级为
 * **全部接口都必须声明 {@code @RequiresPermission} 或 {@code @PublicApi}，白名单清空**。
 * 过渡期之所以先松后紧，是因为默认全拦截会打断尚未接登录的链路（PDA 补传、外部回调），
 * 逼人加白名单而白名单无人回收；现在所有接口都已显式声明，规则可以收紧到"零豁免"。
 *
 * <p>{@code @PublicApi} 必须写明原因：公开接口是**例外**，例外就该留下理由供评审。
 */
@AnalyzeClasses(packages = "com.wms.platform")
public class PermissionAnnotationTest {

    /**
     * 收口后**不再有任何白名单**（W4，2026-10-14）。
     *
     * <p>保留这个空列表是刻意的：它让"以后想加白名单"变成一个**显式的、会被 review 到的改动**，
     * 而不是悄悄在规则里加一个类名。
     */
    private static final List<String> WHITELIST = List.of();

    /** 全部接口都必须声明权限或公开原因（写接口 + 读接口）。 */
    @ArchTest
    static final ArchRule 接口必须声明权限或公开原因 =
            methods().that().areAnnotatedWith(GetMapping.class)
                    .or().areAnnotatedWith(PostMapping.class)
                    .or().areAnnotatedWith(PutMapping.class)
                    .or().areAnnotatedWith(PatchMapping.class)
                    .or().areAnnotatedWith(DeleteMapping.class)
                    .or().areAnnotatedWith(RequestMapping.class)
                    .and().areDeclaredInClassesThat().resideInAPackage("..modules..api..")
                    .should(havePermissionDeclaration())
                    .because("权限漏标就是越权入口；接口必须显式声明所需权限，或说明为何可以公开（01 §1.2 / 03 API-AUTH）");

    /**
     * 条件：方法或所在类上有 {@code @RequiresPermission} / {@code @PublicApi}，或在白名单中。
     *
     * @return ArchUnit 条件
     */
    private static ArchCondition<JavaMethod> havePermissionDeclaration() {
        return new ArchCondition<>("声明 @RequiresPermission 或 @PublicApi") {

            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String owner = method.getOwner().getSimpleName();
                if (WHITELIST.contains(owner)) {
                    return;
                }
                boolean annotated = method.isAnnotatedWith("com.wms.platform.common.security.RequiresPermission")
                        || method.isAnnotatedWith("com.wms.platform.common.security.PublicApi")
                        || method.getOwner().isAnnotatedWith("com.wms.platform.common.security.RequiresPermission")
                        || method.getOwner().isAnnotatedWith("com.wms.platform.common.security.PublicApi");
                if (!annotated) {
                    events.add(SimpleConditionEvent.violated(method, String.format(
                            "%s.%s 既没有 @RequiresPermission 也没有 @PublicApi"
                                    + "（漏标权限 = 越权入口；确属公开接口请加 @PublicApi(\"原因\") 并说明理由）",
                            owner, method.getName())));
                }
            }
        };
    }
}
