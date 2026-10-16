package com.wms.platform.config;

import com.wms.platform.common.config.WmsProperties;
import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;
import com.wms.platform.common.security.token.Hs256TokenCodec;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 启动期安全自检（**"以为配了其实没配"必须当场失败**）。
 *
 * <p>为什么不做成"打一条 warn 日志"：warn 在滚动日志里没人看，而默认密钥/模拟通道上线后
 * 表现是"功能全正常"——直到有人用它伪造令牌或对商户真实收款却用模拟通道。
 * 这类问题**唯一的廉价防线就是启动即失败**（部署时立刻暴露，而不是上线几天后才发现）。
 *
 * <p>一期检查两项：
 * <ol>
 *   <li>令牌签名密钥未使用开发默认值（{@link Hs256TokenCodec#DEV_DEFAULT_SECRET}）；</li>
 *   <li>PII 加密密钥已配置（未配置则收件人与联系人字段无法加密）。</li>
 * </ol>
 *
 * <p>仅在 {@code prod} profile 下强制；本地/测试允许默认值，否则开发跑不起来。
 */
@Component
@RequiredArgsConstructor
public class StartupSecurityCheck {

    /** 日志。 */
    private static final Logger log = LoggerFactory.getLogger(StartupSecurityCheck.class);

    /** 生产 profile 名。 */
    private static final String PROD_PROFILE = "prod";

    /** 配置。 */
    private final WmsProperties properties;

    /** 环境。 */
    private final Environment environment;

    /**
     * 启动自检。
     */
    @PostConstruct
    public void check() {
        // 1. 是否生产
        List<String> profiles = Arrays.asList(environment.getActiveProfiles());
        boolean prod = profiles.contains(PROD_PROFILE);
        // 2. 令牌密钥
        String secret = properties.getSecurity().getTokenSecret();
        boolean defaultSecret = secret == null || secret.isBlank()
                || Hs256TokenCodec.DEV_DEFAULT_SECRET.equals(secret);
        if (defaultSecret) {
            // 2.1 生产：启动即失败
            if (prod) {
                throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "生产环境禁止使用默认令牌密钥：请配置 wms.security.token-secret（由 KMS/密钥管理注入，长度建议 ≥ 32 字符）");
            }
            // 2.2 非生产：显式 warn（不是静默）
            log.warn("当前使用开发默认令牌密钥，仅允许用于本地/测试；生产将以启动失败拦截");
        }
        // 3. PII 密钥
        String piiKey = properties.getSecurity().getPiiKeyBase64();
        if (piiKey == null || piiKey.isBlank()) {
            if (prod) {
                throw new BizException(ErrorCode.SYSTEM_ERROR, "生产环境未配置 PII 加密密钥：wms.security.pii-key-base64");
            }
            log.warn("未配置 PII 加密密钥，联系人/收件人字段将无法加密（仅允许本地/测试）");
        }
        // 4. 资金通道：生产禁止 MOCK
        //    设计（04 §4）早就写明"生产必须 REAL"，但此前没有任何代码强制——只写在注释里的红线等于没有红线。
        //    这条检查的价值在于：它会**在启动时**挡住"带模拟通道上线"，而不是等第一笔真实结算静默失败。
        String channelMode = properties.getPayment().getChannel().getMode();
        if (prod && !"REAL".equalsIgnoreCase(channelMode)) {
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "生产环境禁止使用模拟资金通道：wms.payment.channel.mode 必须为 REAL，当前为 " + channelMode
                            + "（带 MOCK 上线会出现"
                            + "「以为在真扣款、其实只是打日志」的形态，对账单却显示已结算）");
        }
        if (!prod) {
            log.warn("资金通道模式={}（非生产环境允许 MOCK；生产将以启动失败拦截）", channelMode);
        }
        // 5. 通过
        log.info("启动期安全自检通过（profile={}）", profiles.isEmpty() ? "default" : String.join(",", profiles));
    }
}
