package com.github.zhizi01.ets.domain.model;

import lombok.Data;

/** 双边协商的一轮报价。 */
@Data
public class NegoRound {

    private int round;
    private String byAccountId;
    private String byAccountCode;
    private double price;
    private double quantity;
    private String message;
    /** PROPOSE / COUNTER / ACCEPT / REJECT / WITHDRAW */
    private String action;
    private long at;
}
