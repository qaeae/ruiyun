package com.wms.platform.common.idempotent;

import com.wms.platform.common.api.BizException;
import com.wms.platform.common.api.ErrorCode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 请求体规范化哈希（{@code 03-接口定义.md} §1.3）。
 *
 * <p>目的：判定"同一幂等键 + 同一请求"（允许重放）还是"同一幂等键 + 不同请求"（返回 409 + 10002）。
 * 因此哈希必须**与字段顺序、无意义空白无关**，否则同一请求两次调用会算出不同哈希。
 *
 * <p>做法：解析 JSON → 递归按 key 排序重建 → 序列化 → SHA-256 十六进制。
 * 非 JSON 入参按字符串去除首尾空白后哈希（退化路径）。
 */
public final class RequestHasher {

    /// 规范化用的 ObjectMapper。
    ///
    /// <p><b>必须 findAndRegisterModules()（2026-10-14 执行 PoC-3 时修复）</b>：裸 new ObjectMapper()
    /// 不认识 java.time 类型，任何带时间字段的请求体（如 PDA 补传的 scannedAt）在哈希时都会抛异常，
    /// 接口以 500 结束。该缺陷此前潜伏——因为之前的请求 DTO 没有时间字段；一旦有，
    /// 所有带时间的写接口会整体不可用，且报错信息（哈希序列化失败）看起来与时间毫无关系。
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private RequestHasher() {
    }

    /** 对 JSON 字符串计算规范化哈希。 */
    public static String hash(String json) {
        if (json == null) {
            return hashPlain("");
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return hashPlain(canonicalize(node));
        } catch (Exception ex) {
            // 非 JSON（或空体）：退化为纯文本哈希，仍保证确定性
            return hashPlain(json.trim());
        }
    }

    /** 对 DTO 计算规范化哈希（内部先序列化为 JSON）。 */
    public static String hash(Object dto) {
        if (dto == null) {
            return hashPlain("");
        }
        try {
            return hash(MAPPER.writeValueAsString(dto));
        } catch (Exception ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "幂等哈希序列化失败", ex);
        }
    }

    private static String canonicalize(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
            StringBuilder sb = new StringBuilder("{");
            int i = 0;
            for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
                if (i++ > 0) {
                    sb.append(',');
                }
                sb.append('"').append(e.getKey()).append("\":").append(canonicalize(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (node.isArray()) {
            List<String> items = new ArrayList<>();
            node.forEach(child -> items.add(canonicalize(child)));
            return "[" + String.join(",", items) + "]";
        }
        if (node.isNull()) {
            return "null";
        }
        // 数值与字符串统一按原样输出（字符串已含引号）
        return node.isTextual() ? "\"" + node.asText() + "\"" : node.asText();
    }

    private static String hashPlain(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0x0F, 16));
                sb.append(Character.forDigit(b & 0x0F, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "当前 JVM 不支持 SHA-256", ex);
        }
    }
}
