package com.toast.worldeditasync;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v1_21_R1.block.data.CraftBlockData;
import org.enginehub.linbus.tree.LinCompoundTag;

/**
 * {@link com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter} 的核心：讓 WorldEdit 原生的
 * {@code BukkitWorld#setBlock}（引擎關閉、或任何繞過我們 extent 攔截的路徑）真正能動起來，
 * 而不是永遠丟 {@code UnsupportedVersionEditException}。
 *
 * 沿用第一階段 PoC 已驗證安全、且光照 bug 修好後的 {@code LevelChunk#setBlockState} 寫法。
 * 方塊實體 NBT（{@code updateTileEntity}）與鄰居通知/紅石物理目前都刻意不實作
 * （見各方法註解）——這是已知、記錄在案的限制，不是疏漏。
 */
final class WENativeAccess implements WorldNativeAccess<LevelChunk, BlockState, BlockPos> {

    private final ServerLevel level;

    WENativeAccess(ServerLevel level) {
        this.level = level;
    }

    @Override
    public LevelChunk getChunk(int x, int z) {
        return level.getChunk(x, z);
    }

    @Override
    public BlockState toNative(com.sk89q.worldedit.world.block.BlockState state) {
        BlockData bukkitData = BukkitAdapter.adapt(state);
        return ((CraftBlockData) bukkitData).getState();
    }

    @Override
    public BlockState getBlockState(LevelChunk chunk, BlockPos pos) {
        return chunk.getBlockState(pos);
    }

    @Override
    public BlockState setBlockState(LevelChunk chunk, BlockPos pos, BlockState newState) {
        return chunk.setBlockState(pos, newState, false);
    }

    @Override
    public BlockState getValidBlockForPosition(BlockState block, BlockPos pos) {
        return block;
    }

    @Override
    public BlockPos getPosition(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @Override
    public void updateLightingForBlock(BlockPos pos) {
        level.getLightEngine().checkBlock(pos);
    }

    /**
     * 方塊實體 NBT 還原尚未實作：需要把 WorldEdit 自己的 LinCompoundTag 轉成 NMS 的
     * {@code net.minecraft.nbt.CompoundTag}，這正是原本缺失的官方 adapter 平常負責的事，
     * WorldEdit 本體沒有隨插件附上這段轉換邏輯可以直接借用。回傳 false（未更新），
     * 跟 WorldEdit 官方文件對「無 adapter」情境的說明一致（chests will be empty 等）。
     */
    @Override
    public boolean updateTileEntity(BlockPos pos, LinCompoundTag tag) {
        return false;
    }

    @Override
    public void notifyBlockUpdate(LevelChunk chunk, BlockPos pos, BlockState oldState, BlockState newState) {
        level.sendBlockUpdated(pos, oldState, newState, 3);
    }

    @Override
    public boolean isChunkTicking(LevelChunk chunk) {
        return true;
    }

    /**
     * 2026-09-14 委託人實機測試抓到的真實 bug：這裡原本是空殼，註解誤把它當成「chunk
     * 存檔標記」，但反解 {@code WorldNativeAccess} 介面預設方法 {@code markAndNotifyBlock}
     * 才發現它真正的用途——WorldEdit 對「一般編輯」預設帶的 {@code SideEffectSet} 通常有
     * {@code NETWORK} 但沒有 {@code ENTITY_AI}，這種情況下 {@code markAndNotifyBlock} 選的
     * 是 {@code markBlockChanged} 這個分支，根本不會呼叫我們已經正確實作的
     * {@code notifyBlockUpdate}。空殼版等於「方塊資料真的寫進 chunk 了（碰撞判定正確、
     * 站得上去），但從來沒有標記給 vanilla 的 {@code ChunkHolder} 去打包送給客戶端」——
     * 這正是「engine=off native 寫入路徑看不到方塊、但重新登入（強制整包 chunk 重送）
     * 就正常」這個現象的根因：not a rendering bug, 是這格從頭到尾沒送過任何封包。
     *
     * 修法直接對齊 {@code Level#sendBlockUpdated} 內部真正在做的事——反解確認它也只是
     * 呼叫 {@code getChunkSource().blockChanged(pos)}——讓這個位置進入 vanilla 既有的
     * 每 tick 自動批次打包機制（見二點九查證過的 multi-block-change 廣播）。
     */
    @Override
    public void markBlockChanged(LevelChunk chunk, BlockPos pos) {
        level.getChunkSource().blockChanged(pos);
    }

    // 刻意不做鄰居通知/紅石物理：跟本引擎既有的設計哲學一致（大範圍編輯預設不觸發，
    // 避免貼一半就被誤觸發，也避免大量方塊連鎖更新拖垮 TPS）。
    @Override
    public void notifyNeighbors(BlockPos pos, BlockState oldState, BlockState newState) {
    }

    @Override
    public void updateNeighbors(BlockPos pos, BlockState oldState, BlockState newState, int recursionLimit) {
    }

    @Override
    public void onBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState) {
    }
}
