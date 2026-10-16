package com.wms.platform.common.storage;

import java.io.InputStream;
import java.time.Duration;

/**
 * 对象存储端口（P5）：面单、回单影像、异常照片等**二进制文件**的存放。
 *
 * <p><b>为什么不把图片存进数据库</b>：影像动辄几百 KB 到几 MB，
 * 存库会同时拖垮备份体积、主从复制与查询缓存；而对象存储天生适合大文件 +
 * 支持**预签名 URL 直读**（浏览器/APP 直接从 MinIO 取，不经过应用，省带宽与内存）。
 *
 * <p><b>数据库里存什么</b>：只存**对象键 + 元数据**（见 {@link StoredFile}）——
 * 键是"文件在哪"的唯一真相；影像本身丢了还能追溯（谁、何时、多大、什么类型、校验值）。
 *
 * <p><b>为什么要有这层端口</b>：与设备/支付/消息同一套做法——默认用**本地磁盘**实现，
 * 不依赖任何外部组件即可跑通开发与测试；要接 MinIO 只改配置（{@code wms.storage.mode}）。
 * 业务代码永远只依赖本接口，将来换 S3/OSS/COS 也只替换实现类。
 */
public interface FileStoragePort {

    /**
     * 保存文件。
     *
     * @param objectKey   对象键（业务侧生成，需保证租户内唯一，如 {@code proofs/1/SHP123/xxx.jpg}）
     * @param content     内容流（方法内部负责关闭）
     * @param size        字节数
     * @param contentType MIME 类型
     * @return 存储结果（键、大小、SHA-256）
     */
    StoredFile put(String objectKey, InputStream content, long size, String contentType);

    /**
     * 生成预签名下载 URL（浏览器/APP 直接访问对象存储，不经过应用）。
     *
     * @param objectKey 对象键
     * @param ttl       有效期
     * @return 预签名 URL
     */
    String presignedUrl(String objectKey, Duration ttl);

    /**
     * 读取文件内容（供服务端校验/下载；大文件请优先用预签名 URL）。
     *
     * @param objectKey 对象键
     * @return 内容流
     */
    InputStream get(String objectKey);

    /**
     * 对象是否存在。
     *
     * @param objectKey 对象键
     * @return 存在返回 true
     */
    boolean exists(String objectKey);

    /**
     * 当前实现是否支持预签名 URL（本地磁盘实现返回 false，调用方据此改用应用内下载接口）。
     *
     * @return 支持返回 true
     */
    boolean supportsPresignedUrl();

    /**
     * 存储介质描述（日志与健康检查展示，如 {@code MINIO:http://127.0.0.1:9000/wms-proofs}）。
     *
     * @return 描述
     */
    String describe();
}
