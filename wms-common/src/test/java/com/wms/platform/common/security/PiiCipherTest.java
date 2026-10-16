package com.wms.platform.common.security;

import com.wms.platform.common.api.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PII 加解密与脱敏自检（方案 §9.2 合规项）。
 *
 * <p>关键断言有两个容易漏的点：
 * ① **同一明文两次密文必须不同**（随机 IV）——否则库内可通过密文比对推断"是不是同一个人"；
 * ② **密文被篡改必须解密失败**——GCM 的完整性校验不能被静默降级。
 */
class PiiCipherTest {

    private static PiiCipher cipher() {
        String key = Base64.getEncoder().encodeToString("local-dev-pii-key-0123456789abcd".getBytes());
        return new PiiCipher(key);
    }

    @Test
    @DisplayName("加密后可解出原文（姓名/电话/地址）")
    void encryptThenDecrypt() {
        PiiCipher cipher = cipher();
        for (String plain : new String[]{"张三", "13812345678", "上海市浦东新区张江路 1 号 2 室"}) {
            String encrypted = cipher.encrypt(plain);
            assertThat(encrypted).isNotEqualTo(plain);
            assertThat(cipher.decrypt(encrypted)).isEqualTo(plain);
        }
    }

    @Test
    @DisplayName("同一明文两次加密结果不同（随机 IV，防密文比对）")
    void samePlaintextProducesDifferentCiphertext() {
        PiiCipher cipher = cipher();
        assertThat(cipher.encrypt("13812345678")).isNotEqualTo(cipher.encrypt("13812345678"));
    }

    @Test
    @DisplayName("密文被篡改时解密失败，不静默返回错误内容")
    void tamperedCiphertextShouldFail() {
        PiiCipher cipher = cipher();
        String encrypted = cipher.encrypt("13812345678");
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01;   // 翻转最后一字节
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    @DisplayName("密钥长度不足 32 字节时构造失败（B5：抛带状态码的业务异常，防止误用弱密钥）")
    void wrongKeyLengthShouldFailFast() {
        String shortKey = Base64.getEncoder().encodeToString("too-short-key".getBytes());
        assertThatThrownBy(() -> new PiiCipher(shortKey))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    @DisplayName("脱敏：姓名保留姓、手机保留前 3 后 4、地址保留到区")
    void masking() {
        assertThat(PiiCipher.maskName("张三")).isEqualTo("张*");
        assertThat(PiiCipher.maskName("欧阳修")).isEqualTo("欧**");
        assertThat(PiiCipher.maskName("A")).isEqualTo("*");
        assertThat(PiiCipher.maskPhone("13812345678")).isEqualTo("138****5678");
        assertThat(PiiCipher.maskPhone("1234567")).isEqualTo("*******");
        assertThat(PiiCipher.maskName(null)).isNull();
    }

    @Test
    @DisplayName("地址脱敏必须保留到「区」：否则仓库分派看不到区，脱敏就成了业务障碍")
    void addressMaskingShouldKeepDistrict() {
        assertThat(PiiCipher.maskAddress("上海市浦东新区张江路 1 号")).isEqualTo("上海市浦东新区****");
        assertThat(PiiCipher.maskAddress("北京市朝阳区建国路 8 号")).isEqualTo("北京市朝阳区****");
        assertThat(PiiCipher.maskAddress("浙江省杭州市西湖区文三路 1 号")).isEqualTo("浙江省杭州市西湖区****");
        // 无行政区标记（脏数据）→ 退化为保留前 6 字
        assertThat(PiiCipher.maskAddress("某地无名路 5 号")).startsWith("某地无名路").endsWith("****");
        // 门牌号与详细地址绝不出现
        assertThat(PiiCipher.maskAddress("上海市浦东新区张江路 1 号")).doesNotContain("1 号").doesNotContain("张江路");
        assertThat(PiiCipher.maskAddress(null)).isNull();
    }
}
