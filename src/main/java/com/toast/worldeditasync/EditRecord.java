package com.toast.worldeditasync;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.UUID;

/**
 * 一次引擎編輯操作的完整記錄，完全獨立於 WorldEdit 原生的 LocalSession/ChangeSet。
 * 不呼叫、不依賴任何 WorldEdit 原生 history/undo API。
 */
public final class EditRecord {

    final UUID actor;
    final String worldKey;
    final long timestamp;
    final Map<BlockPos, ChangeEntry> changes;

    EditRecord(UUID actor, String worldKey, Map<BlockPos, ChangeEntry> changes) {
        this.actor = actor;
        this.worldKey = worldKey;
        this.timestamp = System.currentTimeMillis();
        this.changes = changes;
    }
}
