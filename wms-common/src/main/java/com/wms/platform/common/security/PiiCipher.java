package com.wms.platform.common.security;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 收件人 PII 加解密与脱敏（{@code 项目一方案.md} §9.2、{@code 01} §3.2）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>算法 AES-256-GCM；每次加密使用 12 字节随机 IV，**同一明文两次密文不同**（防密文比对推断）；</li>
 *   <li>存储格式 {@code IV(12B) || 密文 || Tag(16B)}，整体 Base64 后写入 {@code *_enc} 列；</li>
 *   <li>库中同时保存 {@code *_masked} 明文列，供列表展示与检索；**列表接口不得返回密文或明文原值**；</li>
 *   <li>密钥由 KMS/配置注入（一期以 Base64 配置承载，生产环境走 KMS，禁止硬编码）。</li>
 * </ul>
 */
public class PiiCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey secretKey;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param base64Key Base64 编码的 32 字节密钥
     */
    public PiiCipher(String base64Key) {
        // 1. 解码密钥（B5：非法长度抛带状态码的业务异常，便于统一错误码体系与告警分流）
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length != KEY_BYTES) {
            throw new BizException(ErrorCode.PARAM_ERROR, "PII 密钥必须为 32 字节（AES-256），实际 " + key.length);
        }
        // 2. 构建 AES 密钥
        this.secretKey = new SecretKeySpec(key, "AES");
    }

    /** 加密并返回 Base64（写入 {@code *_enc} 列，列类型 VARBINARY 时请自行 {@code getBytes(UTF_8)}）。 */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(cipherText, 0, payload, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "PII 加密失败", ex);
        }
    }

    /** 解密；输入为 {@link #encrypt} 的产物。密文被篡改时 GCM 校验失败会抛异常（这是预期行为）。 */
    public String decrypt(String base64Cipher) {
        if (base64Cipher == null) {
            return null;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(base64Cipher);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(payload, IV_LENGTH, payload.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "PII 解密失败（密钥不匹配或密文被篡改）", ex);
        }
    }

    /** 姓名脱敏：张三 → 张*；欧阳修 → 欧**（保留姓）。 */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() == 1) {
            return "*";
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /** 手机脱敏：13812345678 → 138****5678；长度不足时整体打码。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        if (phone.length() < 8) {
            return "*".repeat(phone.length());
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 地址脱敏：优先保留到最后一个行政区标记（省/市/区/县），其后打码。
     *
     * <p>为什么不是简单"保留前 6 字"：中文地址的 省+市+区 常为 6–9 字，
     * 固定截断会把"区"切掉（如「上海市浦东新**」），而**仓库分派必须知道区**；
     * 反过来保留到区又不会泄露门牌号，是"可用性"与"最小披露"的平衡点。
     *
     * <p>无行政区标记时退化为保留前 6 字；地址本身短于保留长度时原样返回
     * （短地址无法有效打码，且此类输入通常是脏数据而非完整投递地址）。
     */
    public static String maskAddress(String address) {
        if (address == null || address.isEmpty()) {
            return address;
        }
        int cut = lastAdministrativeMarkerEnd(address);
        if (cut <= 0 || cut >= address.length()) {
            cut = Math.min(6, address.length());
        }
        if (cut >= address.length()) {
            return address;
        }
        return address.substring(0, cut) + "****";
    }

    /** 返回最后一个 省/市/区/县 之后的位置（仅在前 15 字内查找，避免把街道名里的"市"字误当行政区）。 */
    private static int lastAdministrativeMarkerEnd(String address) {
        int limit = Math.min(address.length(), 15);
        int last = -1;
        for (int i = 0; i < limit; i++) {
            char c = address.charAt(i);
            if (c == '省' || c == '市' || c == '区' || c == '县') {
                last = i + 1;
            }
        }
        return last;
    }
}
