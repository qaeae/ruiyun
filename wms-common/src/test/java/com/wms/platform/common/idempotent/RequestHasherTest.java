package com.wms.platform.common.idempotent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求体规范化哈希自检（03 §1.3）。
 *
 * <p>这组断言直接决定幂等的正确性边界：
 * <ul>
 *   <li>同一请求的不同序列化形态（字段顺序、空白差异）**必须算出同一哈希**，
 *       否则调用方正常重试会被误判成"同键不同体"并收到 409；</li>
 *   <li>不同请求**必须算出不同哈希**，否则"用错幂等键"会被静默放过，掩盖前端缺陷。</li>
 * </ul>
 */
class RequestHasherTest {

    @Test
    @DisplayName("字段顺序不同 → 同一哈希")
    void fieldOrderShouldNotMatter() {
        String a = "{\"skuCode\":\"SKU-001\",\"qty\":\"2.0000\"}";
        String b = "{\"qty\":\"2.0000\",\"skuCode\":\"SKU-001\"}";
        assertThat(RequestHasher.hash(a)).isEqualTo(RequestHasher.hash(b));
    }

    @Test
    @DisplayName("空白与换行不同 → 同一哈希")
    void whitespaceShouldNotMatter() {
        String a = "{\"skuCode\":\"SKU-001\",\"qty\":\"2.0000\"}";
        String b = "{ \"skuCode\" : \"SKU-001\" ,\n  \"qty\":\"2.0000\" }";
        assertThat(RequestHasher.hash(a)).isEqualTo(RequestHasher.hash(b));
    }

    @Test
    @DisplayName("嵌套对象同样按 key 规范化")
    void nestedObjectShouldBeCanonicalized() {
        String a = "{\"receiver\":{\"city\":\"上海\",\"name\":\"张三\"}}";
        String b = "{\"receiver\":{\"name\":\"张三\",\"city\":\"上海\"}}";
        assertThat(RequestHasher.hash(a)).isEqualTo(RequestHasher.hash(b));
    }

    @Test
    @DisplayName("数组保持顺序敏感（顺序不同视为不同请求）")
    void arrayOrderShouldMatter() {
        String a = "{\"items\":[{\"sku\":\"A\"},{\"sku\":\"B\"}]}";
        String b = "{\"items\":[{\"sku\":\"B\"},{\"sku\":\"A\"}]}";
        assertThat(RequestHasher.hash(a)).isNotEqualTo(RequestHasher.hash(b));
    }

    @Test
    @DisplayName("内容不同 → 不同哈希")
    void differentContentShouldProduceDifferentHash() {
        assertThat(RequestHasher.hash("{\"qty\":\"2.0000\"}"))
                .isNotEqualTo(RequestHasher.hash("{\"qty\":\"3.0000\"}"));
    }

    @Test
    @DisplayName("DTO 与字符串两条入口得到同一哈希；空值稳定")
    void dtoAndStringEntryPoints() {
        record Cmd(String skuCode, String qty) {
        }
        String json = "{\"skuCode\":\"SKU-001\",\"qty\":\"2.0000\"}";
        assertThat(RequestHasher.hash(new Cmd("SKU-001", "2.0000"))).isEqualTo(RequestHasher.hash(json));
        assertThat(RequestHasher.hash((String) null)).isEqualTo(RequestHasher.hash(""));
    }

    @Test
    @DisplayName("非 JSON 入参退化为纯文本哈希，仍保持确定性")
    void nonJsonFallback() {
        assertThat(RequestHasher.hash("plain-text")).isEqualTo(RequestHasher.hash("plain-text"));
        assertThat(RequestHasher.hash("  plain-text  ")).isEqualTo(RequestHasher.hash("plain-text"));
    }
}
