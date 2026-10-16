package com.wms.platform.modules.au.domain;

import com.wms.platform.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 口令哈希测试。
 *
 * <p>每条对应对一类真实事故：明文/弱哈希落库、同口令同密文（彩虹表）、
 * 改不了强度（无法升级迭代次数）、用 {@code ==} 比较被计时攻击。
 */
@DisplayName("口令哈希（PasswordHasher）")
class PasswordHasherTest {

    @Test
    @DisplayName("哈希 + 校验往返成功，且密文里不含明文")
    void hashAndMatch() {
        String hash = PasswordHasher.hash("Wms@2026!");

        assertThat(PasswordHasher.matches("Wms@2026!", hash)).isTrue();
        assertThat(hash).doesNotContain("Wms@2026!");
        assertThat(hash).startsWith("pbkdf2$H256$");
    }

    @Test
    @DisplayName("同一口令两次哈希结果不同（随机盐）；但都能校验通过")
    void saltShouldDiffer() {
        String first = PasswordHasher.hash("SamePass123");
        String second = PasswordHasher.hash("SamePass123");

        assertThat(first).as("随机盐 ⇒ 密文不同，彩虹表失效").isNotEqualTo(second);
        assertThat(PasswordHasher.matches("SamePass123", first)).isTrue();
        assertThat(PasswordHasher.matches("SamePass123", second)).isTrue();
    }

    @Test
    @DisplayName("错误口令、空值、脏密文一律不匹配（且不抛异常，避免登录接口 500）")
    void wrongInputShouldNotMatch() {
        String hash = PasswordHasher.hash("CorrectPass1");

        assertThat(PasswordHasher.matches("wrong", hash)).isFalse();
        assertThat(PasswordHasher.matches("CorrectPass1", null)).isFalse();
        assertThat(PasswordHasher.matches(null, hash)).isFalse();
        assertThat(PasswordHasher.matches("CorrectPass1", "not-a-hash")).as("脏数据不 500").isFalse();
        assertThat(PasswordHasher.matches("CorrectPass1", "pbkdf2$H256$abc$xx$yy")).isFalse();
    }

    @Test
    @DisplayName("口令强度下限：少于 8 位直接拒绝（弱口令再强的哈希也扛不住字典）")
    void weakPasswordShouldBeRejected() {
        assertThatThrownBy(() -> PasswordHasher.hash("short"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不得少于 8 位");
    }

    @Test
    @DisplayName("迭代次数写进密文：未来可平滑升级强度（老口令按老参数校验）")
    void iterationsEmbeddedInHash() {
        String hash = PasswordHasher.hash("UpgradeMe123");

        assertThat(hash.split("\\$")).hasSize(5);
        assertThat(hash.split("\\$")[2]).isEqualTo(String.valueOf(PasswordHasher.iterations()));
        assertThat(PasswordHasher.iterations()).as("迭代次数应为 20 万级").isGreaterThan(100_000);
    }
}
