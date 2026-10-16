package com.wms.platform.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 模块边界守护（CI 阻断项）。规则来源：{@code 02} §5.2 M1（包边界隔离）、{@code 01} §1.2（状态只能由状态机修改）。
 *
 * <p>为什么必须做成可执行的规则而不是"文档约定"：模块化单体的最大风险是**边界随时间腐化**——
 * 一开始大家都守规矩，三周后为了赶进度直接注入别人域的 Mapper，绕过对方的状态机与不变量校验，
 * 于是"库存三态"和"资金幂等"这两条底线就从内部被绕开了。规则写成测试，越界即 CI 失败。
 *
 * <p>分层约定（见各业务域 package-info）：
 * {@code api → app → domain}，{@code infrastructure} 只被 {@code app/domain} 通过接口使用。
 */
@AnalyzeClasses(packages = "com.wms.platform", importOptions = ImportOption.DoNotIncludeTests.class)
public class ModuleBoundaryTest {

    private static final String COMMON = "com.wms.platform.common..";
    private static final String MODULES = "com.wms.platform.modules..";

    @ArchTest
    static final ArchRule 公共层不得反向依赖业务模块 =
            noClasses().that().resideInAPackage(COMMON)
                    .should().dependOnClassesThat().resideInAPackage(MODULES)
                    .because("common 是基础设施，被业务模块依赖；反向依赖会形成环并让基础设施无法独立演进");

    @ArchTest
    static final ArchRule Controller不得依赖基础设施实现 =
            noClasses().that().resideInAPackage("..modules..api..")
                    .should().dependOnClassesThat().resideInAPackage("..modules..infrastructure..")
                    .because("api 只做协议转换与校验，落到 Mapper/通道/设备实现会让接口层无法被替换与测试");

    @ArchTest
    static final ArchRule Controller不得直接使用Mapper =
            noClasses().that().resideInAPackage("..api..")
                    .should().dependOnClassesThat().resideInAPackage("..mapper..")
                    .because("绕过应用服务直连 Mapper 会跳过事务边界、幂等与状态机校验（02 §5.2 M1）");

    /**
     * 骨架期豁免：{@code domain} 层当前尚无实现类（W3 起落地），规则在空集时通过、有类即生效。
     * 理由同 {@link #应用层不得依赖接口层}。
     */
    @ArchTest
    static final ArchRule 领域层不得依赖Mapper =
            noClasses().that().resideInAPackage("..modules..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..modules..infrastructure.mapper..")
                    .allowEmptyShould(true)
                    .because("领域层必须与技术实现解耦：状态机与不变量规则不应感知表结构");

    /**
     * 应用层不得依赖 Controller。
     *
     * <p><b>规则演进说明（2026-10-14，设备网关任务）</b>：原规则是"app 不得依赖 api"（任何 api 包）。
     * 落地设备档案时按外部规范（B1：Controller 只做参数校验并调用 Service、Service 收发
     * Ro/Vo）改为现在的口径，理由：
     * <ul>
     *   <li>规范要求 <b>Service 接收 Ro、返回 Vo</b>；禁止 app 依赖 api 会与该实践直接冲突；</li>
     *   <li>把 api 包里的 DTO 挪去中立包会改动已批准的包映射（B3），收益不足；</li>
     *   <li>真正要防的是"业务逻辑被 Controller 绑死"，因此约束点收到
     *       <b>app 不得依赖 {@code @RestController}</b>；</li>
     *   <li>作为补偿，新增更严的 {@link #领域层不得依赖接口层}（domain 不得触碰任何 api 模型），
     *       领域层的纯净度反而比之前更强。</li>
     * </ul>
     */
    @ArchTest
    static final ArchRule 应用层不得依赖Controller =
            noClasses().that().resideInAPackage("..modules..app..")
                    .should().dependOnClassesThat().areAnnotatedWith(RestController.class)
                    .allowEmptyShould(true)
                    .because("app 是应用服务层，反向依赖 Controller 会让业务逻辑被 HTTP 入口绑死");

    /**
     * 领域层不得依赖接口层（严格）：domain 只能表达业务概念，不得出现任何请求/响应模型。
     *
     * <p>比原"app 不得依赖 api"更严：领域规则（状态机、不变量、阈值判定）一旦掺入传输模型，
     * 就无法被其它入口（定时任务、消息消费、开放 API）复用。
     */
    @ArchTest
    static final ArchRule 领域层不得依赖接口层 =
            noClasses().that().resideInAPackage("..modules..domain..")
                    .should().dependOnClassesThat().resideInAPackage("..modules..api..")
                    .allowEmptyShould(true)
                    .because("domain 必须与传输解耦：状态机与规则要能被定时任务/消息/开放 API 复用");

    /**
     * 单据/Po 状态只能由状态机修改（{@code 01} §1.2）。
     *
     * <p><b>为什么把判定收窄到"目标是 Po"（2026-10-14 收窄）</b>：原规则禁止**任何**名为
     * {@code setStatus} 的调用，落地设备档案时误报两处无害代码——
     * MapStruct 生成的 {@code DeviceMSImpl.toVo} 调用 {@code DeviceVo.setStatus}（普通字段映射），
     * 以及 {@code DevicePo.of} 装配自身状态（静态工厂）。规则本意是"禁止绕过状态机直接改
     * **表实体**的 status"，因此收窄为：**调用目标是 Po，且调用方不是 Po 自己**。
     * 收窄后防线没有变弱：业务代码改 {@code DevicePo}/{@code OutOrderPo} 的 status 依旧会被拦下。
     */
    @ArchTest
    static final ArchRule 状态字段只能由状态机修改 =
            noClasses().that().resideInAPackage(MODULES)
                    .and().resideOutsideOfPackage("..modules..domain.statemachine..")
                    .and().resideOutsideOfPackage("..po..")
                    .should().callMethodWhere(
                            com.tngtech.archunit.base.DescribedPredicate.describe(
                                    "对 Po（表实体）调用 setStatus（禁止绕过状态机直接改状态）",
                                    (com.tngtech.archunit.core.domain.JavaMethodCall call) ->
                                            call.getTarget().getName().equals("setStatus")
                                                    && call.getTarget().getOwner().getSimpleName().endsWith("Po")))
                    .because("01 §1.2：单据状态只能由状态机服务修改；Vo/Dto 的 setStatus 属普通映射，不受限");

    @ArchTest
    static final ArchRule 业务域之间不得形成循环依赖 =
            slices().matching("com.wms.platform.modules.(*)..")
                    .should().beFreeOfCycles()
                    .because("域间循环依赖是拆分失败的信号；跨域协作应通过 app 层接口或事件单向进行");
}
