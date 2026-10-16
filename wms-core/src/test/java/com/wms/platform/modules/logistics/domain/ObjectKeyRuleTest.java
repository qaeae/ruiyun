package com.wms.platform.modules.logistics.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.storage.impl.LocalFileStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 对象键规则与本地存储单测（P5）。
 *
 * <p><b>重点在安全与"多次上传不覆盖"</b>：
 * ① 键里必须带租户与日期（对象存储里可按业务路径找/按前缀归档）；
 * ② 同单多次上传必须生成**不同键**（否则第二次会覆盖第一次的证据——回单是最典型的举证材料）；
 * ③ 扩展名白名单挡住"任意文件上传"；
 * ④ 本地实现必须挡住**目录穿越**（对象键被污染时不能写到根目录之外）。
 */
@DisplayName("P5 对象存储：键规则、扩展名白名单、目录穿越防护")
class ObjectKeyRuleTest {

    @Test
    @DisplayName("键形态：proofs/{tenant}/{yyyyMMdd}/{单号}/{用途}-{唯一片段}.{ext}")
    void keyShape() {
        String key = ObjectKeyRule.build(7L, "SHP202610150001", ProofBizType.POD, "回单照片.JPG",
                "abc123", java.time.LocalDate.of(2026, 10, 15));

        assertThat(key).isEqualTo("proofs/7/20261015/SHP202610150001/pod-abc123.jpg");
    }

    @Test
    @DisplayName("同一单多次上传：键必须不同（否则会覆盖第一次上传的证据）")
    void sameShipmentDoesNotOverwrite() {
        String first = ObjectKeyRule.build(1L, "SHP-A", ProofBizType.POD, "a.jpg", "id1",
                java.time.LocalDate.of(2026, 10, 15));
        String second = ObjectKeyRule.build(1L, "SHP-A", ProofBizType.POD, "a.jpg", "id2",
                java.time.LocalDate.of(2026, 10, 15));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("扩展名白名单：非图片/PDF 一律拒绝（挡任意文件上传）")
    void extensionWhitelist() {
        assertThatThrownBy(() -> ObjectKeyRule.build(1L, "SHP-A", ProofBizType.POD, "shell.jsp", "x",
                java.time.LocalDate.now()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的文件类型");

        assertThatThrownBy(() -> ObjectKeyRule.assertUploadable(100, "payload.exe"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ObjectKeyRule.assertUploadable(0, "empty.jpg"))
                .as("空文件也要拒绝（多半是前端上传组件出了问题，早拒早发现）")
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ObjectKeyRule.assertUploadable(ObjectKeyRule.MAX_SIZE_BYTES + 1, "big.jpg"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("文件过大");
    }

    @Test
    @DisplayName("单号里的路径字符被安全化（不能借单号做目录穿越）")
    void shipmentNoIsSanitized() {
        String key = ObjectKeyRule.build(1L, "../../etc/passwd", ProofBizType.POD, "a.jpg", "x",
                java.time.LocalDate.of(2026, 10, 15));

        assertThat(key).doesNotContain("..").doesNotContain("/etc/");
    }

    @Test
    @DisplayName("本地存储：写入可读回、sha256 与内容一致；目录穿越对象键被拒绝")
    void localStorageSafeAgainstTraversal(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        LocalFileStorage storage = new LocalFileStorage(dir);
        byte[] content = "回单影像内容".getBytes(StandardCharsets.UTF_8);

        // 1. 正常写入并读回
        var stored = storage.put("proofs/1/20261015/SHP-A/pod-x.jpg",
                new ByteArrayInputStream(content), content.length, "image/jpeg");
        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(storage.exists(stored.objectKey())).isTrue();
        assertThat(storage.get(stored.objectKey()).readAllBytes()).isEqualTo(content);

        // 2. sha256 必须与实际内容一致（日后自证"没被改过"的依据）
        String actual = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        assertThat(stored.sha256()).isEqualTo(actual);

        // 3. 目录穿越必须被拒绝（否则可以覆盖服务器任意文件）
        assertThatThrownBy(() -> storage.put("../../evil.txt",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "text/plain"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("非法");

        // 4. 本地实现不支持预签名 URL：必须显式报错，而不是返回一个用不了的地址
        assertThat(storage.supportsPresignedUrl()).isFalse();
        assertThatThrownBy(() -> storage.presignedUrl("proofs/1/a.jpg", java.time.Duration.ofMinutes(5)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持预签名 URL");

        assertThat(Files.exists(dir.resolve("proofs/1/20261015/SHP-A/pod-x.jpg"))).isTrue();
    }
}
