package com.github.zhizi01.ets.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumsTest {

    @Test
    void roundsPriceQtyAmount() {
        assertEquals(380.13, Nums.price(380.125));
        assertEquals(12.346, Nums.qty(12.3456));
        assertEquals(100.01, Nums.amount(100.005));
    }

    @Test
    void nanAndInfinityBecomeZero() {
        assertEquals(0d, Nums.price(Double.NaN));
        assertEquals(0d, Nums.qty(Double.POSITIVE_INFINITY));
    }

    @Test
    void alignTickAndClamp() {
        assertEquals(380.5, Nums.alignTick(380.49, 0.5));
        assertEquals(400, Nums.clamp(500, 100, 400));
        assertEquals(100, Nums.clamp(50, 100, 400));
    }

    @Test
    void comparisonsAndSafeDiv() {
        assertTrue(Nums.gt(1, 0.999999));
        assertTrue(Nums.eq(1, 1 + 1e-12));
        assertTrue(Nums.isPositive(0.001));
        assertFalse(Nums.isPositive(0));
        assertEquals(0d, Nums.div(10, 0));
        assertEquals(2.5, Nums.div(5, 2));
    }
}
