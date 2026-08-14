package com.github.zhizi01.ets.service.trade;

import com.github.zhizi01.ets.api.CreateListingCmd;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.domain.enums.ListingStatus;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.Listing;
import com.github.zhizi01.ets.domain.model.TakeOrder;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.engine.ListingEngine;
import com.github.zhizi01.ets.repo.ListingRepo;
import com.github.zhizi01.ets.repo.TakeOrderRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 挂牌摘牌的查询与编排层，成交语义全部交给 {@link ListingEngine}。
 * 过期挂牌采用惰性收尾：查询或摘牌时发现已过 validUntil 就地置 EXPIRED 并退还冻结，
 * 这样不必再挂一个定时器去扫全量挂牌。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ListingService {

    private final ListingRepo listingRepo;
    private final TakeOrderRepo takeOrderRepo;
    private final ListingEngine listingEngine;

    @Lazy
    @Autowired
    private SimPort simPort;

    public Listing create(CreateListingCmd cmd) {
        return listingEngine.createListing(cmd);
    }

    public void withdraw(String listingId, String accountId) {
        listingEngine.withdrawListing(listingId, accountId);
    }

    public TakeOrder take(String listingId, String accountId, double quantity, OrderSource source) {
        return listingEngine.takeListing(listingId, accountId, quantity, source);
    }

    public Listing detail(String listingId) {
        Listing l = listingRepo.require(listingId, ErrorCode.LISTING_NOT_FOUND);
        return sweep(l);
    }

    public List<TakeOrder> takes(String listingId) {
        return takeOrderRepo.listByListing(listingId);
    }

    /**
     * 挂牌列表。sessionId 优先；只给 simId 时把该盘全部场次的挂牌汇总起来。
     * mine=true 时只看 accountId 自己挂的牌。
     */
    public List<Listing> query(String simId, String sessionId, Side side, ListingStatus status,
                               boolean mine, String accountId) {
        List<Listing> src = new ArrayList<>();
        if (mine && accountId != null && !accountId.isBlank()) {
            src.addAll(listingRepo.listByAccount(accountId));
        } else if (sessionId != null && !sessionId.isBlank()) {
            src.addAll(listingRepo.listBySession(sessionId));
        } else if (simId != null && !simId.isBlank()) {
            for (TradingSession s : simPort.listSessions(simId)) {
                src.addAll(listingRepo.listBySession(s.getId()));
            }
        }

        List<Listing> out = new ArrayList<>();
        for (Listing l : src) {
            if (l == null) {
                continue;
            }
            if (sessionId != null && !sessionId.isBlank() && !sessionId.equals(l.getSessionId())) {
                continue;
            }
            if (simId != null && !simId.isBlank() && !simId.equals(l.getSimId())) {
                continue;
            }
            if (side != null && l.getSide() != side) {
                continue;
            }
            Listing swept = sweep(l);
            if (status != null && swept.getStatus() != status) {
                continue;
            }
            out.add(swept);
        }
        out.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return out;
    }

    /** 场次闭市：把仍可摘的挂牌全部置为过期并退还挂牌方冻结。 */
    public int expireSessionListings(String sessionId) {
        int n = 0;
        for (Listing l : listingRepo.listBySession(sessionId)) {
            if (l != null && l.getStatus().isTakeable()) {
                listingEngine.closeListing(l, ListingStatus.EXPIRED);
                n++;
            }
        }
        return n;
    }

    private Listing sweep(Listing l) {
        if (l.getStatus().isTakeable() && l.expired(System.currentTimeMillis())) {
            try {
                listingEngine.closeListing(l, ListingStatus.EXPIRED);
            } catch (Exception e) {
                log.warn("挂牌过期收尾失败 listing={}: {}", l.getId(), e.getMessage());
            }
        }
        return l;
    }
}
