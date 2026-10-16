package com.wms.platform.modules.au.domain;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令哈希（PBKDF2WithHmacSHA256，JDK 自带，零新增依赖）。
 *
 * <p><b>为什么不用 MD5/SHA1/SHA256 直接哈希</b>：这些都太快了，一张显卡每秒能试上亿次口令；
 * 而员工口令普遍很短很弱。PBKDF2 靠**迭代次数**把单次校验成本抬高几个数量级，
 * 让离线爆破变得不划算（这是"哈希要慢"这条原则的本意）。
 *
 * <p><b>为什么引入零依赖自实现而不是加 Spring Security</b>：一期只需"存口令 + 校验口令"这一件事；
 * 引入整个安全框架会带来登录过滤器链、默认过滤规则等一大堆需要理解的隐式行为，
 * 反而更容易配错（例如默认放行/默认表单登录）。用 JDK 的 {@code SecretKeyFactory} + 随机盐
 * 只需 60 行，且每行都能讲清楚。
 *
 * <p>存储格式：{@code pbkdf2$<算法码>$<迭代次数>$<盐 Base64>$<哈希 Base64>}。
 * **把迭代次数写进密文**是为了以后能平滑升级强度：老口令按老参数校验通过后重新加密即可，
 * 不需要"全员改密码"。
 */
public final class PasswordHasher {

    /** 存储前缀（用于识别格式与未来升级）。 */
    private static final String PREFIX = "pbkdf2";

    /** 算法码（写进密文，便于换算法时兼容旧值）。 */
    private static final String ALGORITHM_CODE = "H256";

    /** JDK 算法名。 */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** 迭代次数（2026 年的合理下限；写在密文里，未来可升）。 */
    private static final int ITERATIONS = 210_000;

    /** 盐长度（字节）。 */
    private static final int SALT_BYTES = 16;

    /** 派生密钥长度（位）。 */
    private static final int KEY_BITS = 256;

    /** 随机源（口令盐必须用密码学安全随机，不能用 Random）。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /**
     * 生成口令密文。
     *
     * @param rawPassword 明文口令
     * @return 密文（含算法码、迭代次数与盐）
     * @throws BizException 口令为空或过短时抛 {@code 10001}
     */
    public static String hash(String rawPassword) {
        // 1. 强度下限：8 位。太短的口令即使哈希再强也扛不住字典
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new BizException(ErrorCode.PARAM_ERROR, "口令长度不得少于 8 位");
        }
        // 2. 每次都用新的随机盐（同一口令两人密文也不同，彩虹表失效）
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        // 3. 派生并编码
        byte[] hash = derive(rawPassword, salt, ITERATIONS);
        return PREFIX + "$" + ALGORITHM_CODE + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 校验口令。
     *
     * @param rawPassword 明文口令
     * @param stored      库中密文
     * @return 匹配返回 {@code true}
     */
    public static boolean matches(String rawPassword, String stored) {
        // 1. 空值一律不匹配（避免 null 让调用方误判为通过）
        if (rawPassword == null || stored == null) {
            return false;
        }
        // 2. 解析密文（格式不对则视为不匹配，不抛异常：脏数据不该让登录接口 500）
        String[] parts = stored.split("\\$");
        if (parts.length != 5 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[2]);
            byte[] salt = Base64.getDecoder().decode(parts[3]);
            byte[] expected = Base64.getDecoder().decode(parts[4]);
            byte[] actual = derive(rawPassword, salt, iterations);
            // 3. 常量时间比较（逐字节早退会被计时攻击推测出前缀）
            return constantTimeEquals(expected, actual);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 当前迭代次数（运维核对用）。
     *
     * @return 迭代次数
     */
    public static int iterations() {
        return ITERATIONS;
    }

    /**
     * PBKDF2 派生。
     *
     * @param rawPassword 明文
     * @param salt        盐
     * @param iterations  迭代次数
     * @return 派生密钥
     */
    private static byte[] derive(String rawPassword, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "口令哈希计算失败", ex);
        }
    }

    /**
     * 常量时间比较。
     *
     * @param left  左值
     * @param right 右值
     * @return 相等返回 {@code true}
     */
    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < left.length; i++) {
            diff |= left[i] ^ right[i];
        }
        return diff == 0;
    }
}
