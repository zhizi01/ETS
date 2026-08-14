package com.github.zhizi01.ets.controller.user;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.AcctPortfolioVO;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.AccountStat;
import com.github.zhizi01.ets.domain.model.Position;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.repo.PositionRepo;
import com.github.zhizi01.ets.repo.TradeRepo;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.service.account.FundServiceImpl;
import com.github.zhizi01.ets.service.account.TradingAccountServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 组合分析：品种分布、买卖均价对比、盈亏曲线。 */
@RestController
@RequestMapping("/api/user/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    /** 盈亏曲线最多回传的点数，超出则等间隔抽稀，保证前端图表不被拖垮 */
    private static final int MAX_CURVE_POINTS = 500;

    private final TradingAccountServiceImpl accountService;
    private final FundServiceImpl fundService;
    private final PositionRepo positionRepo;
    private final TradeRepo tradeRepo;

    @GetMapping
    public R<AcctPortfolioVO> portfolio(@CurrentUser LoginUser me, @RequestParam String accountId) {
        TradingAccount a = accountService.requireOwned(accountId, me.getId());
        AcctPortfolioVO vo = new AcctPortfolioVO();
        vo.setAccountId(a.getId());
        vo.setCode(a.getCode());
        vo.setName(a.getName());

        fillPositions(vo, accountId);
        fillPnlCurve(vo, a);

        AccountStat s = a.getStat() == null ? new AccountStat() : a.getStat();
        vo.setTotalBuyMwh(Nums.qty(s.getBuyMwh()));
        vo.setTotalSellMwh(Nums.qty(s.getSellMwh()));
        vo.setTotalBuyAmount(Nums.amount(s.getBuyAmount()));
        vo.setTotalSellAmount(Nums.amount(s.getSellAmount()));
        vo.setRealizedPnl(Nums.amount(s.getRealizedPnl()));
        vo.setUnrealizedPnl(Nums.amount(s.getUnrealizedPnl()));
        vo.setTotalPnl(Nums.amount(s.getTotalPnl()));
        return R.ok(vo);
    }

    private void fillPositions(AcctPortfolioVO vo, String accountId) {
        List<Position> positions = positionRepo.listByAccount(accountId);
        positions.sort(Comparator.comparing(Position::getInstrumentKey, Comparator.nullsLast(String::compareTo)));
        for (Position p : positions) {
            String label = labelOf(p.getInstrumentKey());

            AcctPortfolioVO.VarietySlice slice = new AcctPortfolioVO.VarietySlice();
            slice.setInstrumentKey(p.getInstrumentKey());
            slice.setLabel(label);
            slice.setBuyQty(Nums.qty(p.getBuyQty()));
            slice.setSellQty(Nums.qty(p.getSellQty()));
            slice.setNetQty(Nums.qty(p.getNetQty()));
            slice.setAmount(Nums.amount(p.getBuyAmount() + p.getSellAmount()));
            slice.setUnrealizedPnl(Nums.amount(p.getUnrealizedPnl()));
            vo.getVarietyDist().add(slice);

            AcctPortfolioVO.PriceCompare cmp = new AcctPortfolioVO.PriceCompare();
            cmp.setInstrumentKey(p.getInstrumentKey());
            cmp.setLabel(label);
            cmp.setAvgBuyPrice(Nums.price(p.avgBuyPrice()));
            cmp.setAvgSellPrice(Nums.price(p.avgSellPrice()));
            cmp.setLastPrice(Nums.price(p.getLastPrice()));
            vo.getPriceCompare().add(cmp);
        }
    }

    /** 逐笔重算盈亏而不是读快照，这样曲线与账户的 realizedPnl 用的是同一套主体口径。 */
    private void fillPnlCurve(AcctPortfolioVO vo, TradingAccount a) {
        List<Trade> trades = tradeRepo.listByAccount(a.getId());
        trades.sort(Comparator.comparingLong(Trade::getTradedAt));

        List<AcctPortfolioVO.PnlPoint> points = new ArrayList<>(trades.size());
        double cumulative = 0;
        for (Trade t : trades) {
            boolean isBuyer = a.getId().equals(t.getBuyAccountId());
            Side side = isBuyer ? Side.BUY : Side.SELL;
            double fee = isBuyer ? t.getBuyFee() : t.getSellFee();
            double pnl = fundService.realizedPnlOf(a, side, t.getPrice(), t.getQuantity(), fee);
            cumulative += pnl;

            AcctPortfolioVO.PnlPoint point = new AcctPortfolioVO.PnlPoint();
            point.setTs(t.getTradedAt());
            point.setTradeId(t.getId());
            point.setPrice(Nums.price(t.getPrice()));
            point.setQuantity(Nums.qty(t.getQuantity()));
            point.setPnl(Nums.amount(pnl));
            point.setCumulativePnl(Nums.amount(cumulative));
            points.add(point);
        }
        vo.setPnlCurve(thin(points));
    }

    private static List<AcctPortfolioVO.PnlPoint> thin(List<AcctPortfolioVO.PnlPoint> points) {
        if (points.size() <= MAX_CURVE_POINTS) {
            return points;
        }
        int stride = (int) Math.ceil((double) points.size() / MAX_CURVE_POINTS);
        List<AcctPortfolioVO.PnlPoint> out = new ArrayList<>(MAX_CURVE_POINTS + 1);
        for (int i = 0; i < points.size(); i += stride) {
            out.add(points.get(i));
        }
        // 末点必须保留，否则曲线终值与账户盈亏对不上
        AcctPortfolioVO.PnlPoint last = points.get(points.size() - 1);
        if (out.isEmpty() || !out.get(out.size() - 1).getTradeId().equals(last.getTradeId())) {
            out.add(last);
        }
        return out;
    }

    /** MONTHLY_ALL -> 月度全时段 */
    private static String labelOf(String instrumentKey) {
        if (instrumentKey == null) {
            return null;
        }
        int split = instrumentKey.lastIndexOf('_');
        if (split <= 0) {
            return instrumentKey;
        }
        try {
            Variety v = Variety.valueOf(instrumentKey.substring(0, split));
            PeriodType p = PeriodType.valueOf(instrumentKey.substring(split + 1));
            return v.getLabel() + p.getLabel();
        } catch (IllegalArgumentException e) {
            return instrumentKey;
        }
    }
}
