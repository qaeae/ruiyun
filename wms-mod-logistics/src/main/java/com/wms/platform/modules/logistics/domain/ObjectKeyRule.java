package com.wms.platform.modules.logistics.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;

/**
 * 对象键规则（纯函数，可单测）。
 *
 * <p><b>键的形态</b>：{@code proofs/{tenantId}/{yyyyMMdd}/{shipmentNo}/{bizType}-{uuid}.{ext}}
 *
 * <p><b>为什么这样拼</b>：
 * <ul>
 *   <li>**按租户、日期分段**：对象存储里能直接按业务路径浏览与批量归档/清理（生命周期策略按前缀配）；</li>
 *   <li>**含单号**：出事时在控制台搜单号就能定位影像，不必先查库拿键；</li>
 *   <li>**带随机 uuid**：同一单可能多次上传（补拍、白天/夜间照片），不能互相覆盖——
 *       这是最容易犯的错：用单号做文件名，第二次上传直接覆盖第一次的证据。</li>
 * </ul>
 *
 * <p><b>扩展名白名单</b>：只允许图片与 PDF。不做白名单的话，上传路径会变成"任意文件上传"——
 * 一个经典的高危漏洞（传 .jsp/.exe 之类）。这里只按扩展名挡第一层，
 * 生产还应校验真实文件头（magic number）。
 */
public final class ObjectKeyRule {

    /** 允许的扩展名（小写）。 */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp", "pdf");

    /** 单文件最大字节数（20MB：手机原图与扫描件都够用）。 */
    public static final long MAX_SIZE_BYTES = 20L * 1024 * 1024;

    /** 日期分段格式。 */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 生成对象键。
     *
     * @param tenantId    租户
     * @param shipmentNo  发货单号
     * @param bizType     用途
     * @param originalName 原始文件名（仅用于取扩展名）
     * @param uniqueId    唯一片段（雪花 ID 或 uuid，避免同单多次上传互相覆盖）
     * @param today       业务日期（传入而非取系统时间：便于测试与复算）
     * @return 对象键
     */
    public static String build(long tenantId, String shipmentNo, ProofBizType bizType, String originalName,
                               String uniqueId, LocalDate today) {
        // 1. 单号必须存在：键里没有单号就没法在对象存储里按业务定位
        if (shipmentNo == null || shipmentNo.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "发货单号不能为空");
        }
        // 2. 扩展名必须白名单内（挡住任意文件上传）
        String ext = extensionOf(originalName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "不支持的文件类型：." + ext + "（允许 " + ALLOWED_EXT + "）");
        }
        // 3. 拼键（单号做安全化处理：去掉路径分隔符等可能破坏键结构的字符）
        String safeNo = safeSegment(shipmentNo);
        return "proofs/" + tenantId + "/" + today.format(DAY) + "/" + safeNo + "/"
                + bizType.name().toLowerCase(Locale.ROOT) + "-" + uniqueId + "." + ext;
    }

    /**
     * 取小写扩展名。
     *
     * @param fileName 文件名
     * @return 扩展名（无扩展名返回空串）
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 校验大小与类型（上传入口调用，避免把不合规文件先传到存储再拒绝）。
     *
     * @param size 字节数
     * @param fileName 文件名
     */
    public static void assertUploadable(long size, String fileName) {
        if (size <= 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件内容为空");
        }
        if (size > MAX_SIZE_BYTES) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "文件过大：" + size + " 字节（上限 " + MAX_SIZE_BYTES + " 字节 / 20MB）");
        }
        String ext = extensionOf(fileName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "不支持的文件类型：." + ext + "（允许 " + ALLOWED_EXT + "）");
        }
    }

    /**
     * 安全化路径片段（去掉 / \ .. 等）。
     *
     * @param raw 原值
     * @return 安全值
     */
    private static String safeSegment(String raw) {
        // 用字符替换而不是正则：正则里的反斜杠转义极易写错
        // （第一版写成 "[/\\]" 被 Java 解析成未闭合字符类 [/\]，直接 PatternSyntaxException——
        //   这里换成 replace(char,char) 就没有转义问题，意图也更清楚）
        String cleaned = raw.trim().replace('/', '_').replace('\\', '_').replace("..", "_");
        if (cleaned.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "发货单号不合法");
        }
        return cleaned;
    }

    private ObjectKeyRule() {
    }
}
