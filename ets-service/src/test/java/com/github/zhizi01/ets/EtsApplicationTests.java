package com.github.zhizi01.ets;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 需要可达的 Redis，默认跳过；核心算法覆盖见 NumsTest / ClearPriceCalculatorTest。 */
@Disabled("需要可达的 Redis，本地与 CI 默认跳过")
@SpringBootTest
class EtsApplicationTests {

	@Test
	void contextLoads() {
	}

}
