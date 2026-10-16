package com.wms.platform.config;

import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.storage.FileStoragePort;
import com.wms.platform.common.storage.impl.LocalFileStorage;
import com.wms.platform.common.storage.impl.MinioFileStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 对象存储装配（P5）：按 {@code wms.storage.mode} **互斥**选择实现。
 *
 * <p>默认 LOCAL：与资金通道 MOCK、消息 NONE、调度 enabled、缓存 enabled 同一套约定——
 * 默认不依赖外部组件，CI 与新人环境不被 MinIO 卡住。接 MinIO 只改配置。
 */
@Slf4j
@Configuration
public class StorageConfig {

    /**
     * MinIO 实现（{@code wms.storage.mode=MINIO}）。
     *
     * @param properties 配置
     * @return 存储端口
     */
    @Bean
    @ConditionalOnProperty(prefix = "wms.storage", name = "mode", havingValue = "MINIO", matchIfMissing = false)
    public FileStoragePort minioFileStorage(WmsProperties properties) {
        WmsProperties.Storage storage = properties.getStorage();
        log.info("[存储] 使用 MinIO 模式 endpoint={} bucket={} 预签名有效期={} 分钟",
                storage.getEndpoint(), storage.getBucket(), storage.getPresignedTtlMinutes());
        return new MinioFileStorage(storage.getEndpoint(), storage.getAccessKey(), storage.getSecretKey(),
                storage.getBucket());
    }

    /**
     * 本地磁盘实现（默认）。
     *
     * @param properties 配置
     * @return 存储端口
     */
    @Bean
    @ConditionalOnProperty(prefix = "wms.storage", name = "mode", havingValue = "LOCAL", matchIfMissing = true)
    public FileStoragePort localFileStorage(WmsProperties properties) {
        Path root = Path.of(properties.getStorage().getLocalRoot()).toAbsolutePath().normalize();
        log.info("[存储] 使用本地磁盘模式 root={}（不支持预签名 URL，下载走应用内接口）", root);
        return new LocalFileStorage(root);
    }
}
