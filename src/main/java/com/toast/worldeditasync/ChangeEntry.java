package com.toast.worldeditasync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 單一座標在一次編輯操作中的變更前/變更後快照。
 * 同一座標在同一次操作中被改很多次也只留一筆：beforeState/beforeNbt 是第一次遇到時的值
 * （最原始的變更前值），afterState/afterNbt 每次被改寫都更新為最新值（最終的變更後值）。
 */
public final class ChangeEntry {

    final BlockState beforeState;
    final CompoundTag beforeNbt;
    BlockState afterState;
    CompoundTag afterNbt;

    ChangeEntry(BlockState beforeState, CompoundTag beforeNbt) {
        this.beforeState = beforeState;
        this.beforeNbt = beforeNbt;
        this.afterState = beforeState;
        this.afterNbt = beforeNbt;
    }

    void recordAfter(BlockState afterState, CompoundTag afterNbt) {
        this.afterState = afterState;
        this.afterNbt = afterNbt;
    }
}
