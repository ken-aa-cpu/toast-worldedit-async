package com.toast.worldeditasync;

import java.util.Locale;

/**
 * 規格書第 3 節：三個固定檔位 + 自動模式。
 *
 * 固定檔位只控制「本引擎能用掉多少時間」，屬於硬上限，不隨伺服器當下負載變化
 * （規格書 3.1）。{@link #AUTO} 沒有固定值，實際每 tick 預算由
 * {@link AutoThrottleController} 動態決定（規格書 3.2）。
 *
 * <p>三個固定檔位的實際毫秒數改成可由 {@code config.yml} 的 {@code speed-tiers} 覆寫
 * （{@link #configureBudgets}，由 {@link WorldEditAsyncPlugin#onEnable} 在建立任何會讀
 * 這個值的物件（{@link AutoThrottleController}/{@link UndoManager}/{@link PasteExecutor}）
 * 之前呼叫一次）——沒有呼叫過就維持這裡的預設值，行為跟改版前完全一樣。
 */
public enum SpeedTier {
    SLOW,
    MID,
    FAST,
    AUTO;

    private static volatile long slowBudgetNanos = 2_000_000L;
    private static volatile long midBudgetNanos = 6_000_000L;
    private static volatile long fastBudgetNanos = 15_000_000L;

    /** 只能在建立任何 {@link AutoThrottleController} 之前呼叫，之後改值不會回溯套用到已快取的上限。 */
    static void configureBudgets(long slowNanos, long midNanos, long fastNanos) {
        slowBudgetNanos = slowNanos;
        midBudgetNanos = midNanos;
        fastBudgetNanos = fastNanos;
    }

    /** 僅適用於固定檔位；{@link #AUTO} 呼叫這個方法沒有意義，預算要問 {@link AutoThrottleController}。 */
    public long getFixedBudgetNanos() {
        return switch (this) {
            case SLOW -> slowBudgetNanos;
            case MID -> midBudgetNanos;
            case FAST -> fastBudgetNanos;
            case AUTO -> throw new IllegalStateException("AUTO 檔位沒有固定預算，請改問 AutoThrottleController");
        };
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
