package com.wms.platform.common.storage.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.storage.FileStoragePort;
import com.wms.platform.common.storage.StoredFile;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 对象存储实现（{@code wms.storage.mode=MINIO}）。
 *
 * <p><b>预签名 URL 是本实现的核心价值</b>：回单影像由浏览器/APP **直连对象存储**下载，
 * 不经过应用进程——省带宽、省内存、不占用 Tomcat 线程。代价是 URL 必须**有过期时间**
 * （泄露风险窗口可控），且桶要按需限制匿名访问（这里默认私有，只通过预签名访问）。
 *
 * <p><b>启动时确保桶存在</b>：桶不存在时首次上传才报错，属于"部署漏配直到业务触发才发现"。
 * 启动即检查并创建，把一个迟到的运行时错误变成一次启动期的明确日志。
 *
 * <p><b>对象键的租户隔离</b>：键由业务侧拼（含 tenantId），本类不做隐式加前缀——
 * 显式比隐式好排障（在 MinIO 控制台里能直接按业务路径找到文件）。
 */
@Slf4j
public class MinioFileStorage implements FileStoragePort {

    /** MinIO 客户端。 */
    private final MinioClient client;

    /** 桶名。 */
    private final String bucket;

    /** 服务地址（仅用于日志/健康描述）。 */
    private final String endpoint;

    /**
     * 构造并确保桶存在。
     *
     * @param endpoint  服务地址，如 {@code http://127.0.0.1:9000}
     * @param accessKey 访问键
     * @param secretKey 密钥
     * @param bucket    桶名
     */
    public MinioFileStorage(String endpoint, String accessKey, String secretKey, String bucket) {
        this.bucket = bucket;
        this.endpoint = endpoint;
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        ensureBucket();
    }

    @Override
    public StoredFile put(String objectKey, InputStream content, long size, String contentType) {
        try {
            // 边写边算 SHA-256：影像被质疑"改过"时可自证一致性
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long[] written = new long[1];
            try (DigestInputStream in = new DigestInputStream(content, digest)) {
                // 不传 size（传 -1 走分片上传）：multipart 的 size 由框架统计，避免调用方给错导致截断
                client.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(objectKey)
                        .stream(in, -1, 10L * 1024 * 1024)
                        .contentType(contentType)
                        .build());
                written[0] = -1;
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            long actualSize = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build()).size();
            log.info("[存储] 已保存（MinIO）bucket={} key={} size={} type={} sha256={}",
                    bucket, objectKey, actualSize, contentType, sha256);
            return new StoredFile(objectKey, actualSize, contentType, sha256, describe());
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "MinIO 上传失败：" + objectKey, ex);
        }
    }

    @Override
    public String presignedUrl(String objectKey, Duration ttl) {
        try {
            int seconds = (int) Math.max(1, ttl.toSeconds());
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(seconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "生成预签名 URL 失败：" + objectKey, ex);
        }
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception ex) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件不存在或不可读：" + objectKey, ex);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public boolean supportsPresignedUrl() {
        return true;
    }

    @Override
    public String describe() {
        return "MINIO:" + endpoint + "/" + bucket;
    }

    /**
     * 确保桶存在（不存在则创建）。
     *
     * <p>创建失败**不阻断启动**：存储故障不应该让整个服务起不来（其它功能仍可用），
     * 但必须打 ERROR 让运维立刻看到——否则等到用户上传回单时才暴露。
     */
    private void ensureBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[存储] 已创建桶 bucket={} endpoint={}", bucket, endpoint);
            } else {
                log.info("[存储] 已连接 MinIO endpoint={} bucket={}", endpoint, bucket);
            }
        } catch (Exception ex) {
            log.error("[存储] 无法确认桶是否存在（存储功能将不可用，其它功能不受影响）bucket={} endpoint={} 原因={}",
                    bucket, endpoint, ex.getMessage());
        }
    }
}
