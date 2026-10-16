package com.wms.platform.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护规则自身的守护：确认 ArchUnit **真的能看到被守护的类**。
 *
 * <p>为什么需要这个测试：ArchUnit 的规则在 {@code that()} 子句匹配不到任何类时会报
 * "failed to check any classes"；若用 {@code allowEmptyShould(true)} 关闭该行为，规则会**静默通过**。
 * 一旦 {@code wms-archtest} 的依赖作用域/扫描根包写错，边界规则会全部"绿灯"——
 * CI 是绿的，但架构其实无人守护。这是最危险的假绿，必须在 CI 里显式证伪。
 *
 * <p>断言失败信息里附带**当前 classpath 中的 wms 条目**，便于直接定位是依赖作用域问题
 * 还是扫描范围问题，而不是靠猜。
 */
class ArchUnitSetupTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.wms.platform");

    @Test
    @DisplayName("common 类可见（否则第 1 条规则形同虚设）")
    void commonClassesMustBeVisible() {
        long count = countOf("com.wms.platform.common");
        assertThat(count)
                .as("wms-common 的类必须被 ArchUnit 扫描到\n%s", classpathWmsEntries())
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("业务模块类可见（否则跨域/分层规则全部静默通过）")
    void moduleClassesMustBeVisible() {
        long count = countOf("com.wms.platform.modules");
        assertThat(count)
                .as("业务模块的类必须被 ArchUnit 扫描到；为 0 说明依赖作用域或扫描根包配置有误\n%s",
                        classpathWmsEntries())
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("模块边界规则依赖的包命名约定成立：api 层被识别")
    void layerPackagesMustMatchConvention() {
        boolean hasApiLayer = CLASSES.stream()
                .anyMatch(c -> c.getPackageName().contains(".modules.")
                        && c.getPackageName().endsWith(".api"));
        assertThat(hasApiLayer)
                .as("至少应存在一个 api 层实现（当前为 health.api 的健康检查接口），否则分层规则无法生效\n%s",
                        classpathWmsEntries())
                .isTrue();
    }

    private long countOf(String prefix) {
        return CLASSES.stream().filter(c -> c.getPackageName().startsWith(prefix)).count();
    }

    /** 列出 classpath 中与本项目相关的条目，作为断言失败时的现场证据。 */
    private static String classpathWmsEntries() {
        String entries = Arrays.stream(System.getProperty("java.class.path", "").split(File.pathSeparator))
                .filter(p -> p.contains("wms-"))
                .collect(Collectors.joining("\n  "));
        return "classpath 中的 wms 条目:\n  " + (entries.isEmpty() ? "<空>" : entries);
    }
}
