package com.wms.platform.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.regex.Pattern;

/**
 * JSON 时间格式对齐（**对外契约**，2026-10-14 执行 PoC-3 时补）。
 *
 * <p><b>为什么必须显式配置</b>：{@code 03-接口定义.md} §七 的 PDA 补传示例用的是
 * {@code "scannedAt": "2026-12-15 09:31:02.120"}（**空格分隔**），
 * 而 Jackson 默认的 {@code LocalDateTime} 只认 ISO-8601 的 {@code T} 分隔。
 * 结果：**完全照文档实现的客户端全部解析失败**——PoC-3 第一次跑真实 HTTP 链路就撞上了这一点
 * （体感是"接口 500"，排查起来会以为是服务端 bug）。
 *
 * <p>因此这里把对外格式**显式钉成文档里的形态**，并**宽容接收**两种写法（有点/无点、空格/T 分隔），
 * 让客户端无论按文档还是按 ISO 实现都能工作：
 * <ul>
 *   <li>反序列化：{@code yyyy-MM-dd HH:mm:ss[.SSS]} 与 {@code yyyy-MM-ddTHH:mm:ss[.SSS]} 均可；</li>
 *   <li>序列化：统一输出 {@code yyyy-MM-dd HH:mm:ss.SSS}（与 03 示例一致，便于对接方比对报文）。</li>
 * </ul>
 *
 * <p>为什么不用 {@code spring.jackson.date-format} 配置项：它只影响 {@code java.util.Date}，
 * 对 {@code LocalDateTime}（本项目的统一时间类型）无效——这是最容易"配了但没生效"的坑之一。
 */
@Configuration
public class JsonTimeConfig {

    /** 对外时间格式（与 03 §七 示例一致）。 */
    private static final String PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 输出格式：带毫秒（业务时间精度到毫秒，DATETIME(3)）。 */
    private static final DateTimeFormatter SERIALIZER =
            DateTimeFormatter.ofPattern(PATTERN + ".SSS");

    /** 输入格式：毫秒可选、分隔符兼容 'T'。 */
    private static final DateTimeFormatter PARSER = new DateTimeFormatterBuilder()
            .appendPattern(PATTERN)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();

    /** ISO 分隔符（'T'）检测。 */
    private static final Pattern ISO_SEPARATOR = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T");

    /**
     * 注册日期时间序列化/反序列化器。
     *
     * @return Jackson 定制器
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer wmsJsonTimeCustomizer() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(SERIALIZER));
            builder.serializerByType(LocalDate.class, new com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd")));
            builder.serializerByType(LocalTime.class, new com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer(
                    DateTimeFormatter.ofPattern("HH:mm:ss")));
            // ===== 雪花 ID 必须序列化成**字符串**（2026-10-14 联调抓出的真实缺陷）=====
            //
            // 现象：`POST /auth/roles` 返回的 id 是 103992197828837376，但前端（含 Node 脚本、浏览器）
            // 用 JSON.parse 得到的是 ...380 —— **最后两位被四舍五入**，于是拿这个 id 去授权，
            // 后端回"角色不存在"。这类问题的表现是"明明刚创建却说找不到"，极难联想到精度。
            //
            // 根因：雪花 ID 是 19 位整数，超过 JS 的 Number.MAX_SAFE_INTEGER（2^53-1，约 9e15）。
            // JS 无法精确表示，解析时就已经错了——**不是前端写错，是契约没有约定**。
            //
            // 因此约定：**所有 Long（包装类型）一律序列化为字符串**。前端把 ID 当不透明字符串传递即可。
            // 注意只注册 Long.class（包装类型，ID 都用它），不动 int/long（计数、序号）——
            // 若把计数也变成字符串，前端 `row.successCount === 0` 之类的比较会静默失效。
            builder.serializerByType(Long.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance);
            // 宽容解析：把 ISO 的 'T' 归一成空格后再按对外格式解析
            builder.deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(
                    new DateTimeFormatterBuilder().appendPattern(PATTERN)
                            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
                            .toFormatter()) {
                @Override
                public LocalDateTime deserialize(com.fasterxml.jackson.core.JsonParser parser,
                                                 com.fasterxml.jackson.databind.DeserializationContext context)
                        throws java.io.IOException {
                    String text = parser.getText();
                    if (text != null && ISO_SEPARATOR.matcher(text.trim()).find()) {
                        // 输入是 ISO 形态：归一成对外格式（把第 11 位的 T 换成空格）
                        return LocalDateTime.parse(text.trim().replace('T', ' '), PARSER);
                    }
                    return super.deserialize(parser, context);
                }
            });
        };
    }
}
