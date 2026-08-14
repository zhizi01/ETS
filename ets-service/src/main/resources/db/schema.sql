-- ============================================================
--  电力交易模拟器 ETS —— MySQL 归档库 DDL
--
--  运行时数据全部在 Redis；本库用于演练结束后的归档、复盘与报表。
--  MySQL 联通后：application.yml 里把 ets.mysql.enabled 改为 true，
--  填好 url / username / password，服务会异步批量写入下列表。
--
--  CREATE DATABASE ets DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
-- ============================================================

SET NAMES utf8mb4;

-- ── 账号 ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ets_user (
  id            VARCHAR(32)   NOT NULL COMMENT '用户ID',
  username      VARCHAR(64)   NOT NULL COMMENT '登录名',
  password      VARCHAR(160)  NOT NULL COMMENT '加盐SHA256散列',
  nickname      VARCHAR(64)            DEFAULT NULL COMMENT '昵称',
  org_name      VARCHAR(128)           DEFAULT NULL COMMENT '所属单位',
  phone         VARCHAR(32)            DEFAULT NULL,
  role          VARCHAR(16)   NOT NULL DEFAULT 'USER',
  frozen        TINYINT(1)    NOT NULL DEFAULT 0,
  created_at    BIGINT        NOT NULL DEFAULT 0,
  last_login_at BIGINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易用户';

CREATE TABLE IF NOT EXISTS ets_admin (
  id            VARCHAR(32)   NOT NULL,
  username      VARCHAR(64)   NOT NULL,
  password      VARCHAR(160)  NOT NULL,
  nickname      VARCHAR(64)            DEFAULT NULL,
  super_admin   TINYINT(1)    NOT NULL DEFAULT 0,
  created_at    BIGINT        NOT NULL DEFAULT 0,
  last_login_at BIGINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_admin_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员';

-- ── 模拟盘与场次 ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ets_sim (
  id             VARCHAR(32)  NOT NULL,
  name           VARCHAR(128) NOT NULL,
  description    VARCHAR(512)          DEFAULT NULL,
  state          VARCHAR(16)  NOT NULL COMMENT 'SETUP/RUNNING/PAUSED/ENDED',
  owner_admin_id VARCHAR(32)           DEFAULT NULL,
  clock_speed    DOUBLE       NOT NULL DEFAULT 1,
  base_date      VARCHAR(16)           DEFAULT NULL,
  cfg_json       TEXT COMMENT 'SimConfig 原样存 JSON',
  created_at     BIGINT       NOT NULL DEFAULT 0,
  started_at     BIGINT       NOT NULL DEFAULT 0,
  ended_at       BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_sim_state (state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模拟盘';

CREATE TABLE IF NOT EXISTS ets_trading_session (
  id             VARCHAR(32)  NOT NULL,
  sim_id         VARCHAR(32)  NOT NULL,
  name           VARCHAR(128)          DEFAULT NULL,
  variety        VARCHAR(16)  NOT NULL,
  period_type    VARCHAR(16)  NOT NULL DEFAULT 'ALL',
  trade_mode     VARCHAR(24)  NOT NULL,
  state          VARCHAR(16)  NOT NULL,
  delivery_start VARCHAR(16)           DEFAULT NULL,
  delivery_end   VARCHAR(16)           DEFAULT NULL,
  pre_open_time  BIGINT       NOT NULL DEFAULT 0,
  open_time      BIGINT       NOT NULL DEFAULT 0,
  close_time     BIGINT       NOT NULL DEFAULT 0,
  price_floor    DOUBLE       NOT NULL DEFAULT 0,
  price_cap      DOUBLE       NOT NULL DEFAULT 0,
  min_qty        DOUBLE       NOT NULL DEFAULT 0,
  max_qty        DOUBLE       NOT NULL DEFAULT 0,
  tick_size      DOUBLE       NOT NULL DEFAULT 0.01,
  lot_size       DOUBLE       NOT NULL DEFAULT 1,
  prev_close     DOUBLE       NOT NULL DEFAULT 0,
  open_price     DOUBLE       NOT NULL DEFAULT 0,
  high_price     DOUBLE       NOT NULL DEFAULT 0,
  low_price      DOUBLE       NOT NULL DEFAULT 0,
  last_price     DOUBLE       NOT NULL DEFAULT 0,
  avg_price      DOUBLE       NOT NULL DEFAULT 0,
  clear_price    DOUBLE       NOT NULL DEFAULT 0,
  total_volume   DOUBLE       NOT NULL DEFAULT 0,
  total_amount   DOUBLE       NOT NULL DEFAULT 0,
  created_at     BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_tsess_sim (sim_id, open_time),
  KEY idx_tsess_variety (sim_id, variety, trade_mode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易场次';

-- ── 交易账户 ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ets_trading_account (
  id            VARCHAR(32)  NOT NULL,
  sim_id        VARCHAR(32)  NOT NULL,
  user_id       VARCHAR(32)           DEFAULT NULL,
  code          VARCHAR(32)  NOT NULL COMMENT 'G0001/C0001/R0001',
  name          VARCHAR(128) NOT NULL,
  member_type   VARCHAR(16)  NOT NULL,
  source        VARCHAR(8)   NOT NULL DEFAULT 'HUMAN',
  robot_id      VARCHAR(32)           DEFAULT NULL,
  params_locked TINYINT(1)   NOT NULL DEFAULT 0,
  locked_at     BIGINT       NOT NULL DEFAULT 0,
  frozen        TINYINT(1)   NOT NULL DEFAULT 0,
  params_json   TEXT COMMENT 'AccountParams，开盘后不可变',
  fund_json     TEXT COMMENT 'FundInfo',
  stat_json     TEXT COMMENT 'AccountStat',
  created_at    BIGINT       NOT NULL DEFAULT 0,
  updated_at    BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_acct_code (sim_id, code),
  KEY idx_acct_user (user_id, sim_id),
  KEY idx_acct_sim (sim_id, member_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易账户';

CREATE TABLE IF NOT EXISTS ets_position (
  account_id     VARCHAR(32) NOT NULL,
  instrument_key VARCHAR(32) NOT NULL,
  buy_qty        DOUBLE      NOT NULL DEFAULT 0,
  sell_qty       DOUBLE      NOT NULL DEFAULT 0,
  buy_amount     DOUBLE      NOT NULL DEFAULT 0,
  sell_amount    DOUBLE      NOT NULL DEFAULT 0,
  net_qty        DOUBLE      NOT NULL DEFAULT 0,
  avg_price      DOUBLE      NOT NULL DEFAULT 0,
  last_price     DOUBLE      NOT NULL DEFAULT 0,
  unrealized_pnl DOUBLE      NOT NULL DEFAULT 0,
  realized_pnl   DOUBLE      NOT NULL DEFAULT 0,
  updated_at     BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (account_id, instrument_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓';

CREATE TABLE IF NOT EXISTS ets_fund_flow (
  id            VARCHAR(32) NOT NULL,
  sim_id        VARCHAR(32) NOT NULL,
  account_id    VARCHAR(32) NOT NULL,
  type          VARCHAR(16) NOT NULL,
  amount        DOUBLE      NOT NULL DEFAULT 0,
  balance_after DOUBLE      NOT NULL DEFAULT 0,
  ref_id        VARCHAR(32)          DEFAULT NULL,
  remark        VARCHAR(255)         DEFAULT NULL,
  created_at    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_flow_acct (account_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资金流水';

-- ── 委托与成交 ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ets_order (
  id             VARCHAR(32) NOT NULL,
  sim_id         VARCHAR(32) NOT NULL,
  session_id     VARCHAR(32) NOT NULL,
  account_id     VARCHAR(32) NOT NULL,
  user_id        VARCHAR(32)          DEFAULT NULL,
  source         VARCHAR(8)  NOT NULL DEFAULT 'HUMAN',
  variety        VARCHAR(16) NOT NULL,
  period_type    VARCHAR(16) NOT NULL DEFAULT 'ALL',
  trade_mode     VARCHAR(24) NOT NULL,
  side           VARCHAR(8)  NOT NULL,
  time_in_force  VARCHAR(8)  NOT NULL DEFAULT 'GFD',
  price          DOUBLE      NOT NULL DEFAULT 0,
  quantity       DOUBLE      NOT NULL DEFAULT 0,
  filled_qty     DOUBLE      NOT NULL DEFAULT 0,
  avg_fill_price DOUBLE      NOT NULL DEFAULT 0,
  frozen_amount  DOUBLE      NOT NULL DEFAULT 0,
  status         VARCHAR(16) NOT NULL,
  reject_reason  VARCHAR(255)         DEFAULT NULL,
  seq            BIGINT      NOT NULL DEFAULT 0,
  created_at     BIGINT      NOT NULL DEFAULT 0,
  updated_at     BIGINT      NOT NULL DEFAULT 0,
  canceled_at    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_order_acct (account_id, created_at),
  KEY idx_order_sess (session_id, seq),
  KEY idx_order_status (sim_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='申报单';

CREATE TABLE IF NOT EXISTS ets_trade (
  id                VARCHAR(32) NOT NULL,
  sim_id            VARCHAR(32) NOT NULL,
  session_id        VARCHAR(32) NOT NULL,
  variety           VARCHAR(16) NOT NULL,
  period_type       VARCHAR(16) NOT NULL DEFAULT 'ALL',
  trade_mode        VARCHAR(24) NOT NULL,
  buy_order_id      VARCHAR(32)          DEFAULT NULL,
  sell_order_id     VARCHAR(32)          DEFAULT NULL,
  buy_account_id    VARCHAR(32) NOT NULL,
  sell_account_id   VARCHAR(32) NOT NULL,
  buy_account_code  VARCHAR(32)          DEFAULT NULL,
  sell_account_code VARCHAR(32)          DEFAULT NULL,
  price             DOUBLE      NOT NULL DEFAULT 0,
  quantity          DOUBLE      NOT NULL DEFAULT 0,
  amount            DOUBLE      NOT NULL DEFAULT 0,
  buy_fee           DOUBLE      NOT NULL DEFAULT 0,
  sell_fee          DOUBLE      NOT NULL DEFAULT 0,
  taker_side        VARCHAR(8)           DEFAULT NULL,
  traded_at         BIGINT      NOT NULL DEFAULT 0,
  seq               BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_trade_sess (session_id, traded_at),
  KEY idx_trade_buy (buy_account_id, traded_at),
  KEY idx_trade_sell (sell_account_id, traded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成交';

-- ── 挂牌摘牌 ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ets_listing (
  id            VARCHAR(32)  NOT NULL,
  sim_id        VARCHAR(32)  NOT NULL,
  session_id    VARCHAR(32)  NOT NULL,
  account_id    VARCHAR(32)  NOT NULL,
  account_code  VARCHAR(32)           DEFAULT NULL,
  account_name  VARCHAR(128)          DEFAULT NULL,
  user_id       VARCHAR(32)           DEFAULT NULL,
  source        VARCHAR(8)   NOT NULL DEFAULT 'HUMAN',
  variety       VARCHAR(16)  NOT NULL,
  period_type   VARCHAR(16)  NOT NULL DEFAULT 'ALL',
  side          VARCHAR(8)   NOT NULL,
  price         DOUBLE       NOT NULL DEFAULT 0,
  total_qty     DOUBLE       NOT NULL DEFAULT 0,
  remain_qty    DOUBLE       NOT NULL DEFAULT 0,
  min_take_qty  DOUBLE       NOT NULL DEFAULT 0,
  allow_partial TINYINT(1)   NOT NULL DEFAULT 1,
  valid_until   BIGINT       NOT NULL DEFAULT 0,
  status        VARCHAR(16)  NOT NULL,
  title         VARCHAR(128)          DEFAULT NULL,
  remark        VARCHAR(512)          DEFAULT NULL,
  created_at    BIGINT       NOT NULL DEFAULT 0,
  updated_at    BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_listing_sess (session_id, created_at),
  KEY idx_listing_acct (account_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂牌';

CREATE TABLE IF NOT EXISTS ets_take_order (
  id           VARCHAR(32) NOT NULL,
  listing_id   VARCHAR(32) NOT NULL,
  sim_id       VARCHAR(32) NOT NULL,
  session_id   VARCHAR(32) NOT NULL,
  account_id   VARCHAR(32) NOT NULL,
  account_code VARCHAR(32)          DEFAULT NULL,
  user_id      VARCHAR(32)          DEFAULT NULL,
  source       VARCHAR(8)  NOT NULL DEFAULT 'HUMAN',
  quantity     DOUBLE      NOT NULL DEFAULT 0,
  price        DOUBLE      NOT NULL DEFAULT 0,
  amount       DOUBLE      NOT NULL DEFAULT 0,
  fee          DOUBLE      NOT NULL DEFAULT 0,
  trade_id     VARCHAR(32)          DEFAULT NULL,
  contract_id  VARCHAR(32)          DEFAULT NULL,
  created_at   BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_take_listing (listing_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='摘牌';

-- ── 双边协商 ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ets_negotiation (
  id                       VARCHAR(32)  NOT NULL,
  sim_id                   VARCHAR(32)  NOT NULL,
  session_id               VARCHAR(32)           DEFAULT NULL,
  variety                  VARCHAR(16)  NOT NULL,
  period_type              VARCHAR(16)  NOT NULL DEFAULT 'ALL',
  initiator_account_id     VARCHAR(32)  NOT NULL,
  initiator_account_code   VARCHAR(32)           DEFAULT NULL,
  initiator_user_id        VARCHAR(32)           DEFAULT NULL,
  counterparty_account_id  VARCHAR(32)  NOT NULL,
  counterparty_account_code VARCHAR(32)          DEFAULT NULL,
  counterparty_user_id     VARCHAR(32)           DEFAULT NULL,
  initiator_side           VARCHAR(8)   NOT NULL,
  price                    DOUBLE       NOT NULL DEFAULT 0,
  quantity                 DOUBLE       NOT NULL DEFAULT 0,
  delivery_start           VARCHAR(16)           DEFAULT NULL,
  delivery_end             VARCHAR(16)           DEFAULT NULL,
  status                   VARCHAR(16)  NOT NULL,
  current_turn_account_id  VARCHAR(32)           DEFAULT NULL,
  expire_at                BIGINT       NOT NULL DEFAULT 0,
  rounds_json              TEXT COMMENT '报价轮次数组',
  trade_id                 VARCHAR(32)           DEFAULT NULL,
  contract_id              VARCHAR(32)           DEFAULT NULL,
  created_at               BIGINT       NOT NULL DEFAULT 0,
  updated_at               BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_nego_initiator (initiator_account_id, created_at),
  KEY idx_nego_counterparty (counterparty_account_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='双边协商';

-- ── 日前现货 ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ets_spot_bid (
  id             VARCHAR(32) NOT NULL,
  sim_id         VARCHAR(32) NOT NULL,
  session_id     VARCHAR(32) NOT NULL,
  account_id     VARCHAR(32) NOT NULL,
  account_code   VARCHAR(32)          DEFAULT NULL,
  user_id        VARCHAR(32)          DEFAULT NULL,
  source         VARCHAR(8)  NOT NULL DEFAULT 'HUMAN',
  side           VARCHAR(8)  NOT NULL,
  trade_date     VARCHAR(16)          DEFAULT NULL,
  segments_json  TEXT COMMENT '24 点报价曲线',
  status         VARCHAR(16) NOT NULL,
  cleared_qty    DOUBLE      NOT NULL DEFAULT 0,
  cleared_amount DOUBLE      NOT NULL DEFAULT 0,
  created_at     BIGINT      NOT NULL DEFAULT 0,
  updated_at     BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_spot_sess (session_id, created_at),
  KEY idx_spot_acct (account_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日前现货报价';

-- ── 合同与结算 ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ets_contract (
  id                VARCHAR(32)  NOT NULL,
  sim_id            VARCHAR(32)  NOT NULL,
  trade_id          VARCHAR(32)           DEFAULT NULL,
  session_id        VARCHAR(32)           DEFAULT NULL,
  variety           VARCHAR(16)  NOT NULL,
  period_type       VARCHAR(16)  NOT NULL DEFAULT 'ALL',
  trade_mode        VARCHAR(24)  NOT NULL,
  buy_account_id    VARCHAR(32)  NOT NULL,
  buy_account_code  VARCHAR(32)           DEFAULT NULL,
  buy_account_name  VARCHAR(128)          DEFAULT NULL,
  sell_account_id   VARCHAR(32)  NOT NULL,
  sell_account_code VARCHAR(32)           DEFAULT NULL,
  sell_account_name VARCHAR(128)          DEFAULT NULL,
  price             DOUBLE       NOT NULL DEFAULT 0,
  quantity          DOUBLE       NOT NULL DEFAULT 0,
  amount            DOUBLE       NOT NULL DEFAULT 0,
  delivery_start    VARCHAR(16)           DEFAULT NULL,
  delivery_end      VARCHAR(16)           DEFAULT NULL,
  status            VARCHAR(16)  NOT NULL,
  delivered_qty     DOUBLE       NOT NULL DEFAULT 0,
  signed_at         BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_contract_buy (buy_account_id, signed_at),
  KEY idx_contract_sell (sell_account_id, signed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购售电合同';

CREATE TABLE IF NOT EXISTS ets_bill (
  id                VARCHAR(32)  NOT NULL,
  sim_id            VARCHAR(32)  NOT NULL,
  account_id        VARCHAR(32)  NOT NULL,
  account_code      VARCHAR(32)           DEFAULT NULL,
  account_name      VARCHAR(128)          DEFAULT NULL,
  period            VARCHAR(16)  NOT NULL COMMENT '2026-08 或 FINAL',
  contract_qty      DOUBLE       NOT NULL DEFAULT 0,
  contract_amount   DOUBLE       NOT NULL DEFAULT 0,
  actual_qty        DOUBLE       NOT NULL DEFAULT 0,
  deviation_qty     DOUBLE       NOT NULL DEFAULT 0,
  deviation_penalty DOUBLE       NOT NULL DEFAULT 0,
  spot_qty          DOUBLE       NOT NULL DEFAULT 0,
  spot_amount       DOUBLE       NOT NULL DEFAULT 0,
  fee_total         DOUBLE       NOT NULL DEFAULT 0,
  revenue           DOUBLE       NOT NULL DEFAULT 0,
  cost              DOUBLE       NOT NULL DEFAULT 0,
  profit            DOUBLE       NOT NULL DEFAULT 0,
  rank_no           INT          NOT NULL DEFAULT 0,
  settled_at        BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bill (sim_id, account_id, period),
  KEY idx_bill_period (sim_id, period, profit)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结算单';

-- ── 机器人 / 事件 / 公告 / 审计 ─────────────────────────────
CREATE TABLE IF NOT EXISTS ets_robot (
  id           VARCHAR(32)  NOT NULL,
  sim_id       VARCHAR(32)  NOT NULL,
  account_id   VARCHAR(32)           DEFAULT NULL,
  account_code VARCHAR(32)           DEFAULT NULL,
  name         VARCHAR(128)          DEFAULT NULL,
  member_type  VARCHAR(16)           DEFAULT NULL,
  strategy     VARCHAR(24)  NOT NULL,
  enabled      TINYINT(1)   NOT NULL DEFAULT 1,
  cfg_json     TEXT,
  order_count  INT          NOT NULL DEFAULT 0,
  trade_count  INT          NOT NULL DEFAULT 0,
  cancel_count INT          NOT NULL DEFAULT 0,
  last_act_at  BIGINT       NOT NULL DEFAULT 0,
  created_at   BIGINT       NOT NULL DEFAULT 0,
  updated_at   BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_robot_sim (sim_id, strategy)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易机器人';

CREATE TABLE IF NOT EXISTS ets_market_event (
  id                  VARCHAR(32)  NOT NULL,
  sim_id              VARCHAR(32)  NOT NULL,
  type                VARCHAR(32)  NOT NULL,
  magnitude           DOUBLE       NOT NULL DEFAULT 1,
  title               VARCHAR(128)          DEFAULT NULL,
  description         VARCHAR(512)          DEFAULT NULL,
  effective_at        BIGINT       NOT NULL DEFAULT 0,
  expire_at           BIGINT       NOT NULL DEFAULT 0,
  created_by_admin_id VARCHAR(32)           DEFAULT NULL,
  created_at          BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_event_sim (sim_id, effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场事件';

CREATE TABLE IF NOT EXISTS ets_notice (
  id                  VARCHAR(32)  NOT NULL,
  sim_id              VARCHAR(32)  NOT NULL,
  title               VARCHAR(128) NOT NULL,
  content             TEXT,
  level               VARCHAR(16)  NOT NULL DEFAULT 'INFO',
  created_by_admin_id VARCHAR(32)           DEFAULT NULL,
  created_at          BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_notice_sim (sim_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='市场公告';

CREATE TABLE IF NOT EXISTS ets_quote (
  sim_id          VARCHAR(32)  NOT NULL,
  instrument_key  VARCHAR(32)  NOT NULL,
  last_price      DOUBLE       NOT NULL DEFAULT 0,
  open_price      DOUBLE       NOT NULL DEFAULT 0,
  high_price      DOUBLE       NOT NULL DEFAULT 0,
  low_price       DOUBLE       NOT NULL DEFAULT 0,
  prev_close      DOUBLE       NOT NULL DEFAULT 0,
  volume          DOUBLE       NOT NULL DEFAULT 0,
  amount          DOUBLE       NOT NULL DEFAULT 0,
  updated_at      BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (sim_id, instrument_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行情快照归档';

CREATE TABLE IF NOT EXISTS ets_kline (
  sim_id          VARCHAR(32)  NOT NULL,
  instrument_key  VARCHAR(32)  NOT NULL,
  interval_code   VARCHAR(8)   NOT NULL COMMENT '1m/5m/15m/1h/1d',
  bar_ts          BIGINT       NOT NULL,
  open_price      DOUBLE       NOT NULL DEFAULT 0,
  high_price      DOUBLE       NOT NULL DEFAULT 0,
  low_price       DOUBLE       NOT NULL DEFAULT 0,
  close_price     DOUBLE       NOT NULL DEFAULT 0,
  volume          DOUBLE       NOT NULL DEFAULT 0,
  amount          DOUBLE       NOT NULL DEFAULT 0,
  PRIMARY KEY (sim_id, instrument_key, interval_code, bar_ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='K线归档';

CREATE TABLE IF NOT EXISTS ets_fundamental (
  sim_id           VARCHAR(32) NOT NULL,
  coal_price_idx   DOUBLE      NOT NULL DEFAULT 1,
  gas_price_idx    DOUBLE      NOT NULL DEFAULT 1,
  load_factor      DOUBLE      NOT NULL DEFAULT 1,
  wind_factor      DOUBLE      NOT NULL DEFAULT 1,
  solar_factor     DOUBLE      NOT NULL DEFAULT 1,
  outage_ratio     DOUBLE      NOT NULL DEFAULT 0,
  benchmark_price  DOUBLE      NOT NULL DEFAULT 380,
  updated_at       BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (sim_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='基本面快照归档';

CREATE TABLE IF NOT EXISTS ets_audit_log (
  id         VARCHAR(32)  NOT NULL,
  sim_id     VARCHAR(32)           DEFAULT NULL,
  actor_type VARCHAR(16)           DEFAULT NULL,
  actor_id   VARCHAR(32)           DEFAULT NULL,
  actor_name VARCHAR(64)           DEFAULT NULL,
  action     VARCHAR(64)           DEFAULT NULL,
  target     VARCHAR(128)          DEFAULT NULL,
  detail     TEXT,
  ip         VARCHAR(64)           DEFAULT NULL,
  created_at BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_audit_time (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志';
