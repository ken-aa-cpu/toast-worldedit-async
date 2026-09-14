package com.toast.worldeditasync;

import java.util.logging.Logger;

/**
 * 規格書 3.2 節「自動模式」的 AIMD（加法增、乘法減）控制迴圈。
 *
 * <p><b>2026-09-13 委託人明確要求且必須遵守的設計約束</b>：決定要不要加預算/砍預算的
 * 回饋訊號，必須是伺服器整個 tick 的真實 MSPT（由呼叫端從
 * {@code MinecraftServer#getTickTimesNanos()} 讀出、經 {@link #advanceTick} 餵入），
 * 不能只是包住我們自己寫方塊那段迴圈的私有計時器。
 *
 * <p>理由：上一輪（規格書第 4 節封包分類廣播查證）已經反解確認 vanilla 會在每個 tick
 * 尾端（{@code ServerChunkCache#tickChunks()} → {@code ChunkHolder#broadcastChanges}）
 * 把我們寫的方塊自動打包成 {@code multi-block-change} 封包送出——這個打包動作跟收尾的
 * relight 一樣，都在主執行緒上花真實時間，但都不是我們自己的寫入迴圈能量到的時間。
 * 如果自動模式只拿「自己寫方塊那段的耗時」當回饋訊號，這塊開銷會變成完全看不到的
 * 資訊死角：系統以為自己很閒、實際上伺服器沒那麼閒，於是繼續往上加預算，最終在真正
 * 吃緊的時候才後知後覺地反應。
 *
 * <p>{@link #recordOwnContribution} 回報的「我們自己這個 tick 花了多少時間」不是拿來
 * 觸發 AIMD 加/減的訊號——那個角色完全由 {@link #advanceTick} 傳入的真實 MSPT 擔任——
 * 而是次要用途：算出「扣掉我們自己之後，伺服器本來就在忙的部分」，用來滿足規格書 3.2
 * 「不管當下量測到多少空閒時間，最多只能用掉其中 70~80%」這條硬性邊界。就算完全不做
 * 這個次要計算，AIMD 本身的加/減判斷依然完全正確，只是無法精準抓「理論上限」。
 */
final class AutoThrottleController {

    private static final long TICK_TARGET_NANOS = 50_000_000L; // 20 TPS 目標
    private static final long SAFE_MSPT_NANOS = 48_000_000L;   // 規格書建議的安全警戒線（留 2ms 緩衝）
    private static final int WINDOW_SIZE = 8;                  // 規格書建議「最近 5~10 個 tick」
    private static final int CONSECUTIVE_SAFE_TICKS_FOR_INCREASE = 5;
    private static final double INCREASE_FACTOR = 1.05;        // 加法增：+5%
    private static final double DECREASE_FACTOR = 0.5;         // 乘法減：砍半
    private static final double IDLE_USAGE_FRACTION = 0.75;    // 規格書 70~80% 區間取中點
    private static final long MIN_BUDGET_NANOS = 1_000_000L;   // 1ms 地板，避免高負載下完全停擺
    private static final long MAX_BUDGET_NANOS = SpeedTier.FAST.getFixedBudgetNanos(); // auto 不超過 fast 檔

    private final Logger logger;
    private final long[] tickMsptWindow = new long[WINDOW_SIZE];
    private final long[] ourContributionWindow = new long[WINDOW_SIZE];
    private int windowIndex = 0;
    private int windowFilled = 0;
    private int consecutiveSafeTicks = 0;

    // 種子值＝mid 檔預算，避免剛啟動、視窗還沒填滿時忽快忽慢。
    private volatile long currentBudgetNanos = SpeedTier.MID.getFixedBudgetNanos();
    private long pendingOwnContributionNanos = 0L; // 只在 UndoManager 的單一排程執行緒上被讀寫，不需要額外同步

    AutoThrottleController(Logger logger) {
        this.logger = logger;
    }

    long getCurrentBudgetNanos() {
        return currentBudgetNanos;
    }

    /** Job 在寫入/relight 迴圈裡這個 tick 實際花掉的時間，累加進「待下次 advanceTick 消耗」的暫存值。 */
    void recordOwnContribution(long nanos) {
        pendingOwnContributionNanos += nanos;
    }

    /**
     * 每個伺服器 tick 呼叫一次（{@code UndoManager.tick()} 開頭），餵入「最近一個已完整
     * 結束的 tick」的真實耗時。這個值必須來自 {@code MinecraftServer.getTickTimesNanos()}，
     * 不能是我們自己算的——這是整個自動模式設計最容易踩錯的一步，見類別註解。
     */
    void advanceTick(long realLastTickNanos) {
        long ownNanos = pendingOwnContributionNanos;
        pendingOwnContributionNanos = 0L;

        tickMsptWindow[windowIndex] = realLastTickNanos;
        ourContributionWindow[windowIndex] = ownNanos;
        windowIndex = (windowIndex + 1) % WINDOW_SIZE;
        windowFilled = Math.min(windowFilled + 1, WINDOW_SIZE);

        double avgMspt = average(tickMsptWindow, windowFilled);
        double avgOwn = average(ourContributionWindow, windowFilled);

        // 用「最近幾個 tick 的移動平均」而不是單一 tick 的原始值來觸發 AIMD，
        // 避免單一偶發尖峰（例如玩家瞬移觸發大量區塊載入）造成過度反應（規格書 3.2）。
        if (avgMspt > SAFE_MSPT_NANOS) {
            long before = currentBudgetNanos;
            currentBudgetNanos = Math.max(MIN_BUDGET_NANOS, (long) (currentBudgetNanos * DECREASE_FACTOR));
            consecutiveSafeTicks = 0;
            if (before != currentBudgetNanos) {
                logger.info(String.format(
                        "[WEPoC] auto 降檔: 近%d tick平均MSPT=%.2fms > 安全線%.0fms，預算 %.2fms → %.2fms",
                        windowFilled, avgMspt / 1_000_000.0, SAFE_MSPT_NANOS / 1_000_000.0,
                        before / 1_000_000.0, currentBudgetNanos / 1_000_000.0));
            }
        } else {
            consecutiveSafeTicks++;
            if (consecutiveSafeTicks >= CONSECUTIVE_SAFE_TICKS_FOR_INCREASE) {
                long before = currentBudgetNanos;
                currentBudgetNanos = (long) (currentBudgetNanos * INCREASE_FACTOR);
                consecutiveSafeTicks = 0; // 歸零後繼續累積下一輪，安全狀態會持續緩升，不是只升一次
                if (currentBudgetNanos != before && logger.isLoggable(java.util.logging.Level.FINE)) {
                    logger.fine(String.format("[WEPoC] auto 升檔: 連續安全，預算 %.2fms → %.2fms",
                            before / 1_000_000.0, currentBudgetNanos / 1_000_000.0));
                }
            }
        }

        // 硬性邊界（規格書 3.2）：扣掉我們自己之後，伺服器本來就在忙的部分（otherLoad）
        // 抓出「如果我們完全不跑，這個 tick 本來會剩多少空閒」，最多只能吃掉其中 75%。
        double otherLoad = avgMspt - avgOwn;
        double trueIdle = TICK_TARGET_NANOS - otherLoad;
        long ceiling = trueIdle > 0 ? (long) (trueIdle * IDLE_USAGE_FRACTION) : MIN_BUDGET_NANOS;
        currentBudgetNanos = clamp(currentBudgetNanos, MIN_BUDGET_NANOS, Math.min(ceiling, MAX_BUDGET_NANOS));
    }

    private static long clamp(long value, long min, long max) {
        if (max < min) {
            return min; // 理論上限比地板還低（伺服器已經非常忙）時，退守地板值而不是拋例外
        }
        return Math.max(min, Math.min(value, max));
    }

    private static double average(long[] window, int filled) {
        if (filled == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < filled; i++) {
            sum += window[i];
        }
        return (double) sum / filled;
    }
}
