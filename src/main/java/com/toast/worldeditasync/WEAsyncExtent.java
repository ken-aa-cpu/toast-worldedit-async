package com.toast.worldeditasync;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v1_21_R1.block.data.CraftBlockData;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 規格書第 2 節「AbstractDelegateExtent 包裝層」。
 *
 * 這一版不再在呼叫執行緒上直接落地寫入 —— 它只負責兩件事：
 *   1. 記錄每個座標「第一次遇到時」的變更前狀態/NBT（見 {@link ChangeEntry}）
 *   2. 記錄每個座標最新一次的變更後狀態/NBT
 * 真正的落地寫入交給 {@link UndoManager} 的每 tick 限時分批排程處理
 * （同一套流程同時服務正向編輯、undo、redo，見該類別註解）。
 *
 * 這個實例本身在單一次 WorldEdit 指令（一個 EditSession）的同步執行期間收集完所有變更，
 * 指令執行緒返回後，外層會在下一個 tick 把累積好的 {@link #getChanges()} 包成一筆
 * {@link EditRecord} 交給 UndoManager 排隊落地。
 */
public final class WEAsyncExtent extends AbstractDelegateExtent {

    private final ServerLevel level;
    private final JavaPlugin plugin;
    private final Map<BlockPos, ChangeEntry> changes = new LinkedHashMap<>();
    private final AtomicLong fallbackCount = new AtomicLong();

    public WEAsyncExtent(Extent delegate, ServerLevel level, JavaPlugin plugin) {
        super(delegate);
        this.level = level;
        this.plugin = plugin;
    }

    public Map<BlockPos, ChangeEntry> getChanges() {
        return changes;
    }

    public long getRecordedCount() {
        return changes.size();
    }

    public long getFallbackCount() {
        return fallbackCount.get();
    }

    /**
     * 讀取路徑。原生的 {@code BukkitWorld#getBlock}/{@code getFullBlock} 在沒有 native adapter
     * 時（此環境的既有限制，見 {@code BukkitConfiguration#unsupportedVersionEditing}，
     * 預設關閉）會直接丟 {@code UnsupportedVersionEditException}，導致 //copy、//smooth、
     * //deform 這類「先讀後寫」的指令完全無法使用——即使我們已經接管了寫入路徑也一樣，
     * 因為讀取根本沒機會走到我們接管的地方。
     *
     * 這裡直接用 NMS 讀，繞開那個檢查。同時處理「同一次編輯內先寫後讀」的一致性：
     * 若這個座標在本次操作中已經被我們自己的 {@link #setBlock} 記錄過變更（例如 //smooth
     * 這類會讀鄰居目前值的操作，鄰居剛好也在同一次選取範圍內被改過），回傳我們記錄的
     * 「邏輯上最新值」而不是世界裡還沒真正落地的舊值——因為正向寫入現在是先全部記錄、
     * 下一個 tick 才交給 UndoManager 分批落地，呼叫當下世界本身尚未真的被改到。
     */
    @Override
    public com.sk89q.worldedit.world.block.BlockState getBlock(BlockVector3 position) {
        try {
            BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
            ChangeEntry pending = changes.get(pos);
            net.minecraft.world.level.block.state.BlockState nmsState =
                    pending != null ? pending.afterState : level.getBlockState(pos);
            BlockData bukkitData = CraftBlockData.fromData(nmsState);
            return BukkitAdapter.adapt(bukkitData);
        } catch (Throwable t) {
            fallbackCount.incrementAndGet();
            plugin.getLogger().warning("[WEPoC] WEAsyncExtent 讀取失敗，該格 fallback 回原生路徑: " + t);
            return getExtent().getBlock(position);
        }
    }

    /**
     * 同上，但含方塊實體 NBT。目前尚未實作 NMS CompoundTag ←→ WorldEdit 自己那套
     * JNBT/LinBus NBT 格式的完整轉換（那正是官方 native adapter 平常負責的事，此環境沒有
     * 能用的 adapter，WorldEdit 自己也沒有隨插件一起附上這段轉換邏輯可以借用）。
     * 目前作法：只回傳純方塊狀態，不含 NBT——跟 WorldEdit 官方文件對「無 adapter」情境的
     * 說明（chests will be empty 等）一致，不是比現況更差，只是還沒補齊。
     */
    @Override
    public com.sk89q.worldedit.world.block.BaseBlock getFullBlock(BlockVector3 position) {
        return getBlock(position).toBaseBlock();
    }

    @Override
    public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 position, T block) throws WorldEditException {
        try {
            BlockData bukkitData = BukkitAdapter.adapt(block);
            net.minecraft.world.level.block.state.BlockState nmsState = ((CraftBlockData) bukkitData).getState();
            BlockPos pos = new BlockPos(position.x(), position.y(), position.z());

            ChangeEntry entry = changes.get(pos);
            if (entry == null) {
                net.minecraft.world.level.block.state.BlockState beforeState = level.getBlockState(pos);
                BlockEntity beforeBe = level.getBlockEntity(pos);
                CompoundTag beforeNbt = beforeBe != null ? beforeBe.saveWithFullMetadata(level.registryAccess()) : null;
                entry = new ChangeEntry(beforeState, beforeNbt);
                changes.put(pos, entry);
            }

            // 目前尚未接上 WorldEdit 目標方塊本身攜帶的 NBT 轉換（該轉換正是官方 adapter
            // 負責的事，此環境沒有能用的 adapter）。落地時若目標方塊需要 block entity，
            // 只會建立一個乾淨初始化的空白實體 —— 這與規格書第 0 節引用的 WorldEdit 官方
            // 警告（"chests will be empty"）在同一環境下的既有限制一致，不是本次倒退。
            entry.recordAfter(nmsState, null);
            return true;
        } catch (Throwable t) {
            fallbackCount.incrementAndGet();
            plugin.getLogger().warning("[WEPoC] WEAsyncExtent 記錄變更失敗，該格 fallback 回原生路徑: " + t);
            return getExtent().setBlock(position, block);
        }
    }
}
