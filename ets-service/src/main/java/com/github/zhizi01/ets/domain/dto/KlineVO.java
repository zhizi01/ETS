package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.model.Bar;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 单根 K 线。ECharts 直接消费 [ts, open, close, low, high] 顺序，这里给出全字段由前端自行取用。 */
@Data
public class KlineVO {

    private long ts;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private double amount;
    private int tradeCount;

    public static KlineVO of(Bar b) {
        if (b == null) {
            return null;
        }
        KlineVO v = new KlineVO();
        v.ts = b.getTs();
        v.open = b.getOpen();
        v.high = b.getHigh();
        v.low = b.getLow();
        v.close = b.getClose();
        v.volume = b.getVolume();
        v.amount = b.getAmount();
        v.tradeCount = b.getTradeCount();
        return v;
    }

    public static List<KlineVO> of(List<Bar> bars) {
        List<KlineVO> out = new ArrayList<>(bars == null ? 0 : bars.size());
        if (bars != null) {
            for (Bar b : bars) {
                KlineVO v = of(b);
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return out;
    }
}
