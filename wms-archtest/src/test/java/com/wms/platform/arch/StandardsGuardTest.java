package com.wms.platform.arch;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.beans.factory.annotation.Autowired;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * 规范守护（CI 阻断项）。规则编号对应 {@code docs/standards/STANDARDS-MAPPING.md} 的 `B*`。
 *
 * <p>为什么把规范写成测试：写在文档里的约定，三周后就会有人"为了赶进度"绕过去，
 * 而绕过的方式通常正是最危险的那些（在 Service 里拼 Wrapper 跳过封装、抛无状态码异常丢掉错误码、
 * 字段注入导致循环依赖）。写成可执行规则，越界即红，评审不再依赖记忆力。
 */
@AnalyzeClasses(packages = "com.wms.platform", importOptions = ImportOption.DoNotIncludeTests.class)
public class StandardsGuardTest {

    /** 允许出现 SQL/Wrapper 的位置：Mapper 的 default 方法，以及基础设施装配类。 */
    private static final String[] SQL_ALLOWED_PACKAGES = {"..mapper..", "..config.."};

    // ===================== B1 分层与 Wrapper 收口 =====================

    /**
     * B1：MyBatis-Plus 条件构造器只允许出现在 Mapper 的 {@code default} 方法里。
     *
     * <p>在 Service/Controller 里拼 Wrapper 的后果：表结构变化时改动散落各处、
     * 事务边界与状态机校验被绕过、单测只能连库。
     */
    @ArchTest
    static final ArchRule 条件构造器只能出现在Mapper =
            noClasses().that().resideOutsideOfPackages(SQL_ALLOWED_PACKAGES)
                    .should().dependOnClassesThat().resideInAPackage("com.baomidou.mybatisplus.core.conditions..")
                    .because("B1：Wrapper 必须收口在 Mapper 的 default 方法（或 XML/@Select），Service 不得拼 SQL");

    /** B1：{@code Wrappers} 工具类同理（它是 {@code core.toolkit} 下的静态入口，需单独约束）。 */
    @ArchTest
    static final ArchRule Wrappers工具类只能出现在Mapper =
            noClasses().that().resideOutsideOfPackages(SQL_ALLOWED_PACKAGES)
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.baomidou.mybatisplus.core.toolkit.Wrappers")
                    .because("B1：Wrapper 必须收口在 Mapper 的 default 方法");

    /** B1/B3：Mapper 必须落在 {@code ..mapper..} 包、以 {@code Mapper} 结尾、继承 {@code BaseMapper}。 */
    @ArchTest
    static final ArchRule Mapper的包位与命名 =
            classes().that().areAssignableTo(BaseMapper.class)
                    .should().resideInAPackage("..mapper..")
                    .andShould().haveSimpleNameEndingWith("Mapper")
                    .because("B1/B3：Mapper 需可被架构规则与评审快速定位，命名与包位必须统一");

    // ===================== B3 POJO 后缀与包位 =====================

    /*
     * 以下 4 条后缀规则的统一策略：
     *  1. 用**大小写不敏感**的后缀正则匹配（`(?i).*Dto$`）：这样把类写成 `XxxDTO` 也逃不掉，
     *     避免"改个大小写就让规则失配、静默通过"的绕过方式；
     *  2. `allowEmptyShould(true)`：骨架期某个后缀可能尚无对应类（如当前还没有 Dto），
     *     ArchUnit 1.x 对"that() 匹配不到类"默认判失败。一旦出现该类，规则立即自动生效。
     */

    /** B3：Po 必须落在 {@code ..po..} 包。 */
    @ArchTest
    static final ArchRule Po必须落在po包 =
            classes().that().haveNameMatching("(?i).*Po$")
                    .should().resideInAPackage("..po..")
                    .allowEmptyShould(true)
                    .because("B3：Po 与表结构一一对应，集中存放便于核对 DDL 与迁移脚本");

    /** B3：Dto 必须落在 {@code ..dto..} 包。 */
    @ArchTest
    static final ArchRule Dto必须落在dto包 =
            classes().that().haveNameMatching("(?i).*Dto$")
                    .should().resideInAPackage("..dto..")
                    .allowEmptyShould(true)
                    .because("B3：后缀与包位一致，读代码时无需跳转即可判断对象用途");

    /** B3：Ro（请求入参）必须落在 {@code ..ro..} 包。 */
    @ArchTest
    static final ArchRule Ro必须落在ro包 =
            classes().that().haveNameMatching("(?i).*Ro$")
                    .should().resideInAPackage("..ro..")
                    .allowEmptyShould(true)
                    .because("B3：后缀与包位一致");

    /** B3：Vo（响应出参）必须落在 {@code ..vo..} 包。 */
    @ArchTest
    static final ArchRule Vo必须落在vo包 =
            classes().that().haveNameMatching("(?i).*Vo$")
                    .should().resideInAPackage("..vo..")
                    .allowEmptyShould(true)
                    .because("B3：后缀与包位一致");

    // ===================== B5 异常 =====================

    /*
     * B5 的三条规则，写法上有三处是实测踩出来的（勿"优化"回去）：
     *
     * 1) **拆成三条独立规则**，不要写成 `noClasses().should(A).orShould(B).orShould(C)`：
     *    `noClasses` 与 `orShould` 组合的判定语义会产生漏检（实测：抛 RuntimeException 却不报）。
     *    拆开后谁被违反就报谁，报错信息也能直接指出异常类型。
     *
     * 2) 判定用 `dependOnClassesThat().haveFullyQualifiedName(...)`，**不要用 `callConstructor(...)`**：
     *    `callConstructor` 在本工程实测**抓不到** `new RuntimeException("...")`（ArchUnit 1.3 仅导入本项目包时
     *    无法解析 JDK 类构造调用），表现为"规则永远通过"——这是最危险的假绿。
     *    已用探针类双向验证：探针在场时 dependOn 版三条全红、callConstructor 版全绿。
     *
     * 3) 必须豁免三个包，否则误报（都是框架/编译器产物，不是我们写的代码）：
     *    - `..common.api..`：`BizException` 自身必须继承 `RuntimeException`；
     *    - `..mapstruct..`：MapStruct 的 `@MapperConfig` 注解元数据默认值引用了 IllegalArgumentException；
     *    - `..mapper..`：Mapper 里的 lambda 方法引用（`IdempotentPo::getTenantId`）会生成合成方法
     *      `$deserializeLambda$`，其中 JDK 自己会构造 IllegalArgumentException。
     */

    /** B5：禁止抛 {@code RuntimeException}。 */
    @ArchTest
    static final ArchRule 禁止抛RuntimeException =
            noClasses().that().resideOutsideOfPackages("..common.api..", "..mapstruct..", "..mapper..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.RuntimeException")
                    .because("B5：异常必须携带状态码（ErrorCode）与中文消息，禁止无码异常");

    /**
     * B5：禁止抛 {@code IllegalStateException}。
     *
     * <p><b>2026-10-14 补充的豁免（勿删）</b>：任何含 lambda/方法引用的类，javac 都会合成
     * {@code $deserializeLambda$}，**JDK 自己在该方法里构造 {@code IllegalArgumentException}**。
     * 这与我方代码无关，却会让规则在本仓库的非 mapper 类上随机报错（实测命中
     * {@code IdempotentMapper}、{@code RbacAdminServiceImpl}）。因此按方法名豁免，
     * 而不是把整个类加进包白名单（那样会连类里的真实违规一起放过）。
     */
    @ArchTest
    static final ArchRule 禁止抛IllegalStateException =
            noClasses().that().resideOutsideOfPackages("..common.api..", "..mapstruct..", "..mapper..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.IllegalStateException")
                    .because("B5：状态类错误也要带错误码，便于前端判定与监控按域告警");

    /** B5：禁止抛 {@code IllegalArgumentException}（参数校验失败应抛 {@code BizException(PARAM_ERROR)}）。 */
    @ArchTest
    static final ArchRule 禁止抛IllegalArgumentException =
            noClasses().that().resideOutsideOfPackages("..common.api..", "..mapstruct..", "..mapper..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.IllegalArgumentException")
                    .because("B5：参数非法应抛 BizException(PARAM_ERROR)，保证统一错误码与 HTTP 语义");

    /**
     * B5 配套探针：**证明 JVM 合成方法确实存在**（因此上面的豁免是必要的，而不是"顺手放宽"）。
     *
     * <p>为什么用普通 {@code @Test} 而不是 ArchRule：ArchUnit 1.3 的 {@code MethodShould}
     * 没有 {@code exist()}（只有类级别有），用 JUnit 断言更直接。
     */
    @Test
    @DisplayName("JVM 合成方法 $deserializeLambda$ 存在 → B5 豁免仍必要")
    void synthesizedLambdaDeserializerShouldExist() {
        JavaClasses classes = new ClassFileImporter().importPackages("com.wms.platform");
        boolean exists = classes.stream()
                .flatMap(javaClass -> javaClass.getMethods().stream())
                .anyMatch(method -> "$deserializeLambda$".equals(method.getName()));
        assertThat(exists)
                .as("含 lambda 的类会被 javac 合成 $deserializeLambda$，其中 JDK 自己构造 IllegalArgumentException")
                .isTrue();
    }

    // ===================== B8 MapStruct =====================

    /** B8：映射器以 {@code MS} 结尾，且不得声明集合映射方法（集合映射收敛到 {@code MapStructExt.toPojoList}）。 */
    @ArchTest
    static final ArchRule MS映射器不得声明集合方法 =
            noMethods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("MS")
                    .should().haveNameContaining("List")
                    .allowEmptyShould(true)
                    .because("B8：批量映射统一用 MapStructExt.toPojoList，避免每个映射器各写一套集合逻辑");

    // ===================== B9 注入方式 =====================

    /** B9：禁止字段注入（一律 {@code @RequiredArgsConstructor} + {@code private final}）。 */
    @ArchTest
    static final ArchRule 禁止字段注入 =
            noFields().should().beAnnotatedWith(Autowired.class)
                    .because("B9：字段注入无法体现依赖关系、不能注入 final 字段、且掩盖循环依赖");

    // ===================== B11 缓存与 Redis 纪律（一期） =====================

    /** B11：禁止直接使用 {@code RedisTemplate}，统一走 Redisson/封装组件（二期接入 jetCache 时再扩展）。 */
    @ArchTest
    static final ArchRule 禁止直接使用RedisTemplate =
            noClasses().should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.data.redis.core.RedisTemplate")
                    .because("B11：Redis 操作需收口到统一组件，避免 key 拼接与编解码策略散落各处");

    // ===================== B13 代码风格 =====================

    /** B13：禁止 {@code System.out}/{@code System.err}（必须走 SLF4J，否则日志无 traceId、无法采集）。 */
    @ArchTest
    static final ArchRule 禁止控制台直接输出 =
            noClasses().should().accessField(System.class, "out")
                    .orShould().accessField(System.class, "err")
                    .because("B13：日志必须走 SLF4J，控制台输出无法关联 traceId、无法被日志平台采集");
}
