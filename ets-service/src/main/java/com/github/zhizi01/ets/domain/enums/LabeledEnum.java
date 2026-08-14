package com.github.zhizi01.ets.domain.enums;

/**
 * 带中文标签的枚举。/api/pub/meta/enums 会反射本包下所有实现类生成前端字典。
 */
public interface LabeledEnum {

    String name();

    String getLabel();
}
