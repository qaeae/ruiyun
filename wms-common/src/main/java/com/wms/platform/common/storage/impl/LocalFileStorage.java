package com.wms.platform.common.storage.impl;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.storage.FileStoragePort;
import com.wms.platform.common.storage.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 本地磁盘存储（**默认实现**）。
 *
 * <p><b>为什么默认是它</b>：与资金通道 {@code MOCK}、消息 {@code NONE}、调度 {@code enabled}、
 * 缓存 {@code enabled}、存储 {@code LOCAL} 一样——**默认不依赖外部组件**，
 * 开发与 CI 不需要起 MinIO 就能把"上传回单"这条路走通。
 *
 * <p><b>不支持预签名 URL</b>：本地磁盘没有"直接对外发 URL"的能力，
 * 因此 {@link #supportsPresignedUrl()} 返回 false，调用方据此走应用内下载接口。
 * 这个差异必须显式暴露，否则业务层会以为拿到的 URL 能用（实际 404）。
 */
@Slf4j
@RequiredArgsConstructor
public class LocalFileStorage implements FileStoragePort {

    /** 本地根目录。 */
    private final Path root;

    @Override
    public StoredFile put(String objectKey, InputStream content, long size, String contentType) {
        try {
            Path target = resolve(objectKey);
            Files.createDirectories(target.getParent());
            // 边写边算 SHA-256（避免读完一遍再读一遍，大文件开销翻倍）
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream in = new DigestInputStream(content, digest)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            long actualSize = Files.size(target);
            log.info("[存储] 已保存（本地）key={} size={} type={} sha256={}",
                    objectKey, actualSize, contentType, sha256);
            return new StoredFile(objectKey, actualSize, contentType, sha256, describe());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "本地存储写入失败：" + objectKey, ex);
        }
    }

    @Override
    public String presignedUrl(String objectKey, java.time.Duration ttl) {
        // 本地存储无法直接对外发 URL：明确报错，让调用方走应用内下载（而不是返回一个 404 的地址）
        throw new BizException(ErrorCode.SYSTEM_ERROR,
                "本地存储模式不支持预签名 URL，请使用应用内下载接口（或切换为 MINIO 模式）");
    }

    @Override
    public InputStream get(String objectKey) {
        try {
            return Files.newInputStream(resolve(objectKey));
        } catch (IOException ex) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件不存在：" + objectKey, ex);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.exists(resolve(objectKey));
    }

    @Override
    public boolean supportsPresignedUrl() {
        return false;
    }

    @Override
    public String describe() {
        return "LOCAL:" + root.toAbsolutePath();
    }

    /**
     * 解析本地路径，**防止目录穿越**（对象键里出现 {@code ../} 时不允许写到根目录之外）。
     *
     * @param objectKey 对象键
     * @return 本地路径
     */
    private Path resolve(String objectKey) {
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root.normalize())) {
            // 安全边界：对象键由业务生成，但一旦被污染（如来自外部参数拼接），
            // 目录穿越可以覆盖服务器任意文件——必须在这里挡住
            throw new BizException(ErrorCode.PARAM_ERROR, "非法的对象键（越出存储根目录）：" + objectKey);
        }
        return target;
    }
}
