package com.toast.worldeditasync;

import java.util.Locale;

/**
 * 規格書第 3 節：三個固定檔位 + 自動模式。
 *
 * 固定檔位只控制「本引擎能用掉多少時間」，屬於硬上限，不隨伺服器當下負載變化
 * （規格書 3.1）。{@link #AUTO} 沒有固定值，實際每 tick 預算由
 * {@link AutoThrottleController} 動態決定（規格書 3.2）。
 */
public enum SpeedTier {
    SLOW(2_000_000L),
    MID(6_000_000L),
    FAST(15_000_000L),
    AUTO(-1L);

    private final long fixedBudgetNanos;

    SpeedTier(long fixedBudgetNanos) {
        this.fixedBudgetNanos = fixedBudgetNanos;
    }

    /** 僅適用於固定檔位；{@link #AUTO} 呼叫這個方法沒有意義，預算要問 {@link AutoThrottleController}。 */
    public long getFixedBudgetNanos() {
        return fixedBudgetNanos;
    }

    /** 未來指令介面（規格書第 4 節）解析檔位參數時共用；未填/不合法一律視為 mid（規格書 4.2）。 */
    public static SpeedTier parse(String s) {
        if (s == null) {
            return MID;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "slow" -> SLOW;
            case "mid" -> MID;
            case "fast" -> FAST;
            case "auto" -> AUTO;
            default -> MID;
        };
    }
}
