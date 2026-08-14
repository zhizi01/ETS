package com.github.zhizi01.ets.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * 全局 JSON 工具，Redis 中所有实体均以此序列化。
 * Spring Boot 4 已切换到 Jackson 3（tools.jackson 包），注解仍在 com.fasterxml.jackson.annotation。
 * Java 8 时间类型的支持在 Jackson 3 中已内置，无需额外注册模块。
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonUtils() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SERVER_ERROR, "JSON 序列化失败: " + e.getMessage());
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SERVER_ERROR, "JSON 反序列化失败: " + e.getMessage());
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new BizException(ErrorCode.SERVER_ERROR, "JSON 反序列化失败: " + e.getMessage());
        }
    }

    public static <T> List<T> toList(String json, Class<T> element) {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, element));
        } catch (Exception e) {
            throw new BizException(ErrorCode.SERVER_ERROR, "JSON 反序列化失败: " + e.getMessage());
        }
    }

    /** 对象 -> Map，用于拼装动态响应。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object obj) {
        return MAPPER.convertValue(obj, Map.class);
    }

    public static <T> T fromMap(Map<String, ?> map, Class<T> type) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        return MAPPER.convertValue(map, type);
    }

    /** 深拷贝，避免共享可变对象。 */
    @SuppressWarnings("unchecked")
    public static <T> T copy(T obj) {
        return obj == null ? null : (T) fromJson(toJson(obj), obj.getClass());
    }
}
