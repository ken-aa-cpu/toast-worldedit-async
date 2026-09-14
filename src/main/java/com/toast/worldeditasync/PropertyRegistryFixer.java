package com.toast.worldeditasync;

import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 修掉 propdump 診斷出來的「WorldEdit 自己的 property registry 是空的」這個缺口。
 *
 * 根因不是 WorldEdit 核心壞掉，也不是 adapter 沒做完整，而是啟動順序：
 * {@code WorldEditPlugin#onEnable} 依序呼叫 {@code loadAdapter()} 再呼叫
 * {@code initializeRegistries()}——後者會強制對「每一個」已註冊的 {@code BlockType} 呼叫
 * 一次屬性解析（透過 {@code BlockFactory#parseFromInput}），把結果快取進
 * {@code BlockType} 內部的 {@code properties}/{@code blockStatesMap}/{@code defaultState}
 * 三個 {@code LazyReference} 欄位裡——而這三個欄位一旦算過一次就永久定型，沒有
 * {@code invalidate()} 這種 API 可以重算。
 *
 * 因為我們是「依附在另一個 plugin（本身宣告 depend: [WorldEdit]）的 onEnable 裡，用反射
 * 事後補上 adapter」，這個時間點必然晚於 WorldEdit 自己的 {@code loadAdapter()}/
 * {@code initializeRegistries()}——所以上面那三個欄位在我們的 adapter 存在之前就已經
 * 用「adapter==null」的結果被永久快取成空的了。即使 adapter 本身的 {@code getProperties()}
 * 實作完全正確（見 {@link WEBukkitAdapter#getProperties}），BlockType 也不會再去問它。
 *
 * 這裡用反射把這三個欄位整批換成用「現在已經註冊好的 adapter」重新算出來的
 * {@code LazyReference.computed(...)}，讓 //set 的屬性語法解析、//copy //paste 的屬性保留
 * 都能吃到真正的屬性表。官方原生 adapter 不需要這段，因為它們是在
 * {@code loadAdapter()} 本身裡面被同步發現的，天生就趕在 {@code initializeRegistries()}
 * 前面；只有我們這種「依賴另一個 plugin 的 onEnable 事後注入」的做法才會踩到這個
 * 啟動順序坑。
 *
 * 跟 {@link WorldEditAsyncPlugin#registerBukkitImplAdapter} 是同一類風險：兩者都用反射碰
 * WorldEdit 內部欄位（{@code properties}/{@code blockStatesMap}/{@code defaultState}
 * 三個欄位名稱、{@code BlockState.generateStateMap}/{@code BlockType.computeDefaultState}
 * 兩個方法簽章），跟 WorldEdit 7.3.7 這個確切版本的內部結構綁死，版本一升級就可能失效。
 * 這裡套用跟 adapter 注入完全一樣的防呆：反射查找階段（欄位/方法是否存在）視為
 * 「結構性失敗」，用醒目的多行 severe 訊息報告「WorldEdit 版本已變更、property
 * registry 重建失敗、屬性遺失問題已回歸」；逐一方塊型別處理階段任何例外只跳過該
 * 方塊型別並記警告，不讓單一方塊的問題吃掉整批重建結果。
 */
final class PropertyRegistryFixer {

    private PropertyRegistryFixer() {
    }

    /**
     * @return 實際帶有屬性的方塊型別數（>=0 視為成功，即使個別方塊型別被跳過）；
     *         -1 代表反射結構性失敗，整個重建完全沒有執行（八成是 WorldEdit 版本變了）。
     */
    static int refreshAll(WEBukkitAdapter adapter, Logger logger) {
        Field propertiesField;
        Field blockStatesMapField;
        Field defaultStateField;
        Method generateStateMap;
        Method computeDefaultState;
        try {
            propertiesField = BlockType.class.getDeclaredField("properties");
            propertiesField.setAccessible(true);
            blockStatesMapField = BlockType.class.getDeclaredField("blockStatesMap");
            blockStatesMapField.setAccessible(true);
            defaultStateField = BlockType.class.getDeclaredField("defaultState");
            defaultStateField.setAccessible(true);
            generateStateMap = BlockState.class.getDeclaredMethod("generateStateMap", BlockType.class);
            generateStateMap.setAccessible(true);
            computeDefaultState = BlockType.class.getDeclaredMethod("computeDefaultState");
            computeDefaultState.setAccessible(true);
        } catch (NoSuchFieldException | NoSuchMethodException | SecurityException e) {
            logger.severe("====================================================================");
            logger.severe("[WEPoC] 【嚴重】WorldEdit 內部結構已變更，property registry 重建反射失敗！");
            logger.severe("[WEPoC] 這代表 WorldEdit 版本可能已經更新，跟這個 PoC 寫死假設的");
            logger.severe("[WEPoC] BlockType.properties/blockStatesMap/defaultState 欄位，或是");
            logger.severe("[WEPoC] BlockState.generateStateMap/BlockType.computeDefaultState 方法簽章不符。");
            logger.severe("[WEPoC] 影響範圍：樓梯/半磚/原木/玻璃板等帶屬性方塊的 //set 屬性語法、");
            logger.severe("[WEPoC] //copy //paste 屬性保留，將全部回到「屬性被重設成預設值」的遺失狀態");
            logger.severe("[WEPoC] （這正是 2026-09-13 花了一整輪才定位修好的問題，會悄悄回歸）。");
            logger.severe("[WEPoC] 純方塊種類（無分辨性屬性者）不受影響，engine=on 的寫入路徑也不受影響。");
            logger.severe("[WEPoC] 請先確認目前安裝的 WorldEdit 版本，並重新比對 BlockType/BlockState");
            logger.severe("[WEPoC] 原始碼裡這幾個欄位/方法的定義，再考慮升級這個 PoC 或調整反射邏輯。");
            logger.severe("====================================================================");
            logger.severe("[WEPoC] 原始例外: " + e);
            e.printStackTrace();
            return -1;
        }

        int withProperties = 0;
        int total = 0;
        int skipped = 0;
        for (BlockType blockType : BlockType.REGISTRY.values()) {
            total++;
            try {
                Map<String, ? extends Property<?>> realProps = adapter.getProperties(blockType);
                propertiesField.set(blockType, LazyReference.computed(realProps));

                @SuppressWarnings("unchecked")
                Map<Map<Property<?>, Object>, BlockState> stateMap =
                        (Map<Map<Property<?>, Object>, BlockState>) generateStateMap.invoke(null, blockType);
                blockStatesMapField.set(blockType, LazyReference.computed(stateMap));

                BlockState defaultState = (BlockState) computeDefaultState.invoke(blockType);
                defaultStateField.set(blockType, LazyReference.computed(defaultState));

                if (!realProps.isEmpty()) {
                    withProperties++;
                }
            } catch (Throwable t) {
                skipped++;
                logger.warning("[WEPoC] 重建 " + blockType.id() + " 的 property registry 失敗，"
                        + "此方塊型別的屬性維持原本（可能遺失）的狀態: " + t);
            }
        }
        logger.info("[WEPoC] 已重建 " + total + " 個方塊型別的 property registry（繞過 WorldEdit"
                + " 啟動順序造成的永久快取），其中 " + withProperties + " 個型別實際帶有屬性"
                + (skipped > 0 ? "，" + skipped + " 個型別重建失敗已跳過（見上方個別警告）" : ""));
        return withProperties;
    }
}
