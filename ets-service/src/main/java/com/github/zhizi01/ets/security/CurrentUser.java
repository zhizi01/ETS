package com.github.zhizi01.ets.security;

import java.lang.annotation.*;

/**
 * 注入当前登录主体。用在 Controller 方法参数上：
 * <pre>public R&lt;?&gt; list(@CurrentUser LoginUser me) { ... }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
