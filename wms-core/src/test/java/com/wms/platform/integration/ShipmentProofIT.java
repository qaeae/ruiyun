package com.wms.platform.integration;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.context.TenantContext;
import com.wms.platform.common.storage.FileStoragePort;
import com.wms.platform.modules.logistics.app.ShipmentProofService;
import com.wms.platform.modules.logistics.domain.ProofBizType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 影像上传/下载集成测试（P5，真实 MySQL + S3 兼容对象存储）。
 *
 * <p><b>验证四件事</b>：
 * <ol>
 *   <li>上传后：对象真的进了存储（用存储端 API 读回并比对字节），登记表有记录；</li>
 *   <li>**预签名 URL 真的能下载**（不是"生成了一个字符串"就算）；</li>
 *   <li>应用内下载走鉴权且校验单号归属（不能拿别的单的 ID 钓文件）；</li>
 *   <li>元数据（sha256/大小/类型）与实际内容一致。</li>
 * </ol>
 *
 * <p><b>存储不可达则跳过</b>（与 Redis/MySQL 的用例同一策略）：CI 未必有对象存储，
 * 但本地/联调有——用 assumption 而不是让构建失败。
 */
@SpringBootTest
@DisplayName("P5 影像：上传落对象存储、预签名可直读、归属校验、哈希一致")
class ShipmentProofIT {

    /** 测试租户。 */
    private static final long TENANT = 1L;

    /** 影像服务。 */
    @Autowired
    private ShipmentProofService proofService;

    /** 存储端口（用于独立读回校验，不经过业务代码）。 */
    @Autowired
    private FileStoragePort fileStorage;

    /**
     * 配置：MySQL + 对象存储（S3 兼容）。
     *
     * @param registry 属性注册器
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getProperty("poc.mysql.url", System.getenv("POC_MYSQL_URL"));
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getProperty("poc.mysql.user", "root"));
            registry.add("spring.datasource.password", () -> System.getProperty("poc.mysql.password", ""));
        }
        registry.add("spring.flyway.enabled", () -> true);
        // 存储：默认本地磁盘（一定能跑）；如需验证 S3 兼容存储，传 -Dpoc.s3.endpoint=...
        String s3 = System.getProperty("poc.s3.endpoint", System.getenv("POC_S3_ENDPOINT"));
        if (s3 != null && !s3.isBlank()) {
            registry.add("wms.storage.mode", () -> "MINIO");
            registry.add("wms.storage.endpoint", () -> s3);
            registry.add("wms.storage.access-key", () -> System.getProperty("poc.s3.access-key", "test"));
            registry.add("wms.storage.secret-key", () -> System.getProperty("poc.s3.secret-key", "test"));
            registry.add("wms.storage.bucket", () -> System.getProperty("poc.s3.bucket", "wms-proofs"));
        }
    }

    /**
     * S3 模式要求存储可达。
     */
    @BeforeAll
    static void requireStorage() {
        String s3 = System.getProperty("poc.s3.endpoint", System.getenv("POC_S3_ENDPOINT"));
        if (s3 != null && !s3.isBlank()) {
            assumeTrue(reachable(s3), "对象存储不可达，跳过：" + s3);
        }
    }

    @Test
    @DisplayName("上传→存储里能读回同样内容→登记表有记录→预签名 URL 可下载（S3 模式）")
    void uploadStoresObjectAndPresignedUrlWorks() throws Exception {
        TenantContext.set(TENANT, 1L, Set.of(), null);
        String shipmentNo = "SHP-P5-" + System.nanoTime();
        byte[] content = ("回单影像-" + shipmentNo).getBytes(StandardCharsets.UTF_8);

        // 1. 上传
        var view = proofService.upload(shipmentNo, ProofBizType.POD, "回单.jpg", "image/jpeg",
                content.length, new ByteArrayInputStream(content), "P5 集成验证");

        // 2. 元数据与内容一致
        assertThat(view.sha256()).hasSize(64);
        assertThat(view.sizeBytes()).isEqualTo(content.length);
        assertThat(view.objectKey()).contains("proofs/" + TENANT + "/").contains(shipmentNo);

        // 3. **不经过业务代码**直接从存储读回，比对字节（证明对象真的落到了存储里）
        assertThat(fileStorage.exists(view.objectKey())).isTrue();
        byte[] readBack = fileStorage.get(view.objectKey()).readAllBytes();
        assertThat(readBack).isEqualTo(content);

        // 4. 列表能查到
        List<ShipmentProofService.ProofView> list = proofService.list(shipmentNo, null);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).objectKey()).isEqualTo(view.objectKey());

        // 5. 预签名 URL：S3 模式必须真的能下载（不是"生成了字符串"就算）
        if (fileStorage.supportsPresignedUrl()) {
            assertThat(view.downloadUrl()).startsWith("http");
            HttpURLConnection conn = (HttpURLConnection) new URL(view.downloadUrl()).openConnection();
            conn.setRequestMethod("GET");
            assertThat(conn.getResponseCode()).as("预签名 URL 应可直接下载").isEqualTo(200);
            assertThat(conn.getInputStream().readAllBytes()).isEqualTo(content);
            print("预签名直读", "S3 模式：URL 可直接下载且字节一致（" + view.downloadUrl().length() + " 字符 URL）");
        } else {
            assertThat(view.downloadUrl()).as("本地模式不应给出用不了的 URL").isNull();
            assertThat(view.downloadHint()).contains("应用内下载");
            print("本地模式", "不返回预签名 URL，改为提示走应用内下载：" + view.downloadHint());
        }

        // 6. 应用内下载：内容与哈希一致
        var download = proofService.download(shipmentNo, view.id());
        assertThat(download.content().readAllBytes()).isEqualTo(content);
        assertThat(download.sha256()).isEqualTo(view.sha256());

        // 7. 归属校验：用别的单号下载同一个 ID 必须被拒绝
        assertThatThrownBy(() -> proofService.download("SHP-OTHER", view.id()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不属于该发货单");

        print("影像全流程", "上传→存储读回一致→登记可查→预签名/应用内下载一致→跨单号访问被拒");
    }

    @Test
    @DisplayName("不合规文件在**上传前**就被拒绝（不产生存储垃圾）")
    void invalidFileRejectedBeforeUpload() {
        TenantContext.set(TENANT, 1L, Set.of(), null);

        assertThatThrownBy(() -> proofService.upload("SHP-X", ProofBizType.POD, "evil.jsp", "text/plain",
                10, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的文件类型");

        print("上传前校验", "扩展名白名单生效，不合规文件不会进入对象存储");
    }

    /**
     * 探测存储可达性。
     *
     * @param endpoint 端点，如 http://127.0.0.1:4566
     * @return 可达返回 true
     */
    private static boolean reachable(String endpoint) {
        try {
            URL url = new URL(endpoint);
            int port = url.getPort() > 0 ? url.getPort() : (url.getProtocol().equals("https") ? 443 : 80);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(url.getHost(), port), 1000);
                return true;
            }
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 打印结论（用例输出即证据）。
     *
     * @param title  标题
     * @param detail 细节
     */
    private void print(String title, String detail) {
        System.out.println("[P5] " + title + " | " + detail);
    }
}
