package com.toast.worldeditasync.light;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * 光罩計算池的 Java 端入口。設計原則（跟委託人對齊過的方案一）：
 * <ul>
 *   <li>Rust 端每個 JNI 進入點都用 {@code catch_unwind} 包住——一個 panic 最多讓這次呼叫
 *       回傳 {@code null}，絕不能讓它直接拖垮呼叫端的 JVM。</li>
 *   <li>這裡再包一層 Java 端的 {@code try/catch(Throwable)} 當雙重保險（涵蓋
 *       {@code UnsatisfiedLinkError} 等 native 載入層級的問題）。</li>
 *   <li><b>呼叫端規則</b>：永遠不要在主執行緒直接呼叫 {@link #tryComputeLight}——一律丟到
 *       背景執行緒，並自己控制逾時；即使 Rust 端算得異常久也不會拖住主執行緒，逾時就放棄
 *       這批、fallback 回 {@code UndoManager} 原本逐格呼叫 vanilla {@code checkBlock} 的
 *       路徑。這個類別本身不做執行緒切換，呼叫端要自己確保這件事。</li>
 * </ul>
 *
 * 驗收標準（委託人拍板）：不要求跟 vanilla 逐格數值完全一致，「肉眼分不清」即可——容許
 * 小範圍、小幅度（例如角落 ±1 等級）的落差，只要整體明暗分布看起來合理。
 */
public final class RustLightEngine {

    private RustLightEngine() {
    }

    private static native byte[] computeLight(int width, int height, int length,
            byte[] opacity, byte[] blockLightSeed, byte[] skyLightSeed);

    public static boolean isAvailable(Logger logger) {
        return NativeLoader.ensureLoaded(logger);
    }

    /**
     * @param opacity        每格擋光程度 0..15，flat index = ((x*height)+y)*length+z
     * @param blockLightSeed 已知方塊光種子（光源本身+視窗邊界既有值），未知格填 -1
     * @param skyLightSeed   已知天空光種子（開放天空欄+視窗邊界既有值），未知格填 -1
     * @return 算好的 [方塊光陣列, 天空光陣列]；native 呼叫失敗（未載入/panic/例外）回傳空。
     */
    public static Optional<Result> tryComputeLight(Logger logger, int width, int height, int length,
            byte[] opacity, byte[] blockLightSeed, byte[] skyLightSeed) {
        if (!NativeLoader.ensureLoaded(logger)) {
            return Optional.empty();
        }
        try {
            byte[] combined = computeLight(width, height, length, opacity, blockLightSeed, skyLightSeed);
            if (combined == null) {
                logger.warning("[WEPoC] 光罩計算池回傳 null（Rust 端 catch_unwind 接到 panic 或內部錯誤），這批 fallback 回 vanilla");
                return Optional.empty();
            }
            int size = width * height * length;
            if (combined.length != size * 2) {
                logger.warning("[WEPoC] 光罩計算池回傳長度不符預期，這批 fallback 回 vanilla");
                return Optional.empty();
            }
            byte[] blockLight = new byte[size];
            byte[] skyLight = new byte[size];
            System.arraycopy(combined, 0, blockLight, 0, size);
            System.arraycopy(combined, size, skyLight, 0, size);
            return Optional.of(new Result(blockLight, skyLight));
        } catch (Throwable t) {
            logger.warning("[WEPoC] 呼叫光罩計算池時發生例外，這批 fallback 回 vanilla: " + t);
            return Optional.empty();
        }
    }

    public record Result(byte[] blockLight, byte[] skyLight) {
    }
}
