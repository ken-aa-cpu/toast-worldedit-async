package com.toast.worldeditasync;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.blocks.BaseItem;
import com.sk89q.worldedit.blocks.BaseItemStack;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.adapter.BukkitImplAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.DirectionalProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.DataFixer;
import com.sk89q.worldedit.world.RegenOptions;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.generation.ConfiguredFeatureType;
import com.sk89q.worldedit.world.generation.StructureType;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.item.ItemTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v1_21_R1.CraftWorld;
import org.bukkit.craftbukkit.v1_21_R1.block.data.CraftBlockData;
import org.bukkit.craftbukkit.v1_21_R1.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.enginehub.linbus.tree.LinByteTag;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinIntTag;
import org.enginehub.linbus.tree.LinStringTag;
import org.enginehub.linbus.tree.LinTag;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * 補上「這台 Arclight build 沒有官方 native adapter」這個缺口的最小實作。
 *
 * 背景：WorldEdit 的 {@code BukkitWorld#getBlock}/{@code getFullBlock} 讀取路徑是
 * 直接呼叫 {@code EditSession#getBlock}/{@code getFullBlock}，而那兩個方法內部
 * 硬編碼直接讀 {@code this.world}（真正的 BukkitWorld），完全繞過我們原本用
 * EditSessionEvent + extent 替換攔截的寫入路徑——也就是說，光是覆寫我們自己
 * extent 的 getBlock 對 //copy /smooth /deform 這類「先讀後寫」指令完全沒用，
 * 因為那些指令根本沒有走到我們的 extent。
 *
 * 真正能接住讀取（以及 engine=off 時的原生寫入）的地方，是 WorldEdit 自己設計好
 * 的擴充點：{@link BukkitImplAdapter}。只要註冊一個實作進去，
 * {@code BukkitWorld} 就會優先呼叫它，不再落到「沒有 adapter → 直接丟例外」的分支。
 * 這裡只實作 getBlock/getFullBlock/createWorldNativeAccess 這三個真正需要的方法，
 * 其餘（實體轉換、生物群系覆寫、地形重生成等）保持最小/不支援，不在本次範圍內。
 */
final class WEBukkitAdapter implements BukkitImplAdapter {

    @Override
    @Nullable
    public DataFixer getDataFixer() {
        return null;
    }

    @Override
    public BlockState getBlock(Location location) {
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        BlockPos pos = new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        net.minecraft.world.level.block.state.BlockState nmsState = level.getBlockState(pos);
        BlockData bukkitData = CraftBlockData.fromData(nmsState);
        return BukkitAdapter.adapt(bukkitData);
    }

    /**
     * 不含 NBT——需要把 NMS CompoundTag 轉成 WorldEdit 自己的 LinCompoundTag 格式，
     * 那正是原本缺失的官方 adapter 平常負責的事。跟 {@link WENativeAccess#updateTileEntity}
     * 是同一個已知、記錄在案的限制，不是本次疏漏。
     */
    @Override
    public BaseBlock getFullBlock(Location location) {
        return getBlock(location).toBaseBlock();
    }

    @Override
    public WorldNativeAccess<?, ?, ?> createWorldNativeAccess(World world) {
        return new WENativeAccess(((CraftWorld) world).getHandle());
    }

    @Override
    @Nullable
    public BaseEntity getEntity(Entity entity) {
        return null;
    }

    @Override
    @Nullable
    public Entity createEntity(Location location, BaseEntity state) {
        return null;
    }

    @Override
    public Component getRichBlockName(BlockType blockType) {
        return TextComponent.of(blockType.getId());
    }

    @Override
    public Component getRichItemName(ItemType itemType) {
        return TextComponent.of(itemType.getId());
    }

    @Override
    public Component getRichItemName(BaseItemStack itemStack) {
        return TextComponent.of(itemStack.getType().getId());
    }

    /**
     * 這裡就是 propdump 診斷出「WorldEdit 自己的 property registry 是空的」那個缺口的真正修法。
     *
     * {@code BlockType.getPropertyMap()} 內部是一個 {@code LazyReference}，只在第一次被呼叫時
     * 透過 {@code getRegistries().getBlockRegistry().getProperties(this)} 算一次值——而
     * {@code getBlockRegistry()} 在有註冊 adapter 的環境下解析到的正是「這個方法」。
     * 舊版直接 {@code return blockType.getPropertyMap();} 等於在計算這個 LazyReference 的
     * 過程中又呼叫了同一個 LazyReference，是自我循環參照，永遠只能拿到「尚未算完」的空結果——
     * 不是 WorldEdit 核心壞掉，是這個 adapter 自己沒把「回報這個平台每個方塊有哪些屬性」這段
     * 做完整。真正的資料來源必須是 NMS 這個方塊型別本身的 {@code StateDefinition}，跟
     * WorldEdit 自己的 registry 完全無關。
     */
    @Override
    public Map<String, ? extends Property<?>> getProperties(BlockType blockType) {
        Map<String, Property<?>> result = new LinkedHashMap<>();
        try {
            Material material = BukkitAdapter.adapt(blockType);
            BlockData defaultData = material.createBlockData();
            net.minecraft.world.level.block.state.BlockState nmsState = ((CraftBlockData) defaultData).getState();
            for (net.minecraft.world.level.block.state.properties.Property<?> nmsProp
                    : nmsState.getBlock().getStateDefinition().getProperties()) {
                Property<?> weProp = convertProperty(nmsProp);
                if (weProp != null) {
                    result.put(weProp.getName(), weProp);
                }
            }
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[WEPoC] 無法解析 " + blockType.getId() + " 的屬性表，該方塊的屬性將視為不存在: " + t);
        }
        return result;
    }

    private static Property<?> convertProperty(net.minecraft.world.level.block.state.properties.Property<?> nmsProp) {
        String name = nmsProp.getName();
        if (nmsProp instanceof net.minecraft.world.level.block.state.properties.DirectionProperty directionProp) {
            List<Direction> values = new ArrayList<>();
            for (net.minecraft.core.Direction v : directionProp.getPossibleValues()) {
                Direction dir = adaptDirection(v);
                if (dir != null) {
                    values.add(dir);
                }
            }
            return new DirectionalProperty(name, values);
        }
        if (nmsProp instanceof net.minecraft.world.level.block.state.properties.IntegerProperty integerProp) {
            List<Integer> values = new ArrayList<>(integerProp.getPossibleValues());
            values.sort(null);
            return new IntegerProperty(name, values);
        }
        if (nmsProp instanceof net.minecraft.world.level.block.state.properties.BooleanProperty) {
            return new BooleanProperty(name, List.of(Boolean.TRUE, Boolean.FALSE));
        }
        if (nmsProp instanceof net.minecraft.world.level.block.state.properties.EnumProperty<?> enumProp) {
            List<String> values = new ArrayList<>();
            for (Object v : enumProp.getPossibleValues()) {
                values.add(((StringRepresentable) v).getSerializedName());
            }
            return new EnumProperty(name, values);
        }
        Bukkit.getLogger().warning("[WEPoC] 未知的屬性型別 " + nmsProp.getClass() + "（name=" + name + "），已略過");
        return null;
    }

    private static Direction adaptDirection(net.minecraft.core.Direction nmsDirection) {
        return switch (nmsDirection) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
    }

    /**
     * 2026-09-14 委託人實機測試發現「選取範圍看不到外框」——反解 {@code BukkitPlayer#sendFakeBlock}
     * （WorldEdit 選取範圍視覺化 server-side CUI 的實際呼叫點）確認：假的方塊本身（讓客戶端
     * 暫時看到一個 structure_block）是透過標準 Bukkit {@code Player#sendBlockChange} 完成，
     * 不需要 adapter；但那個方塊要真的畫出邊界框，還需要另外送這裡兩個 hook 補的東西：
     * 方塊實體 NBT（{@code showboundingbox}/尺寸等欄位）跟讓客戶端「以為」自己有權限看見
     * game-master 方塊 UI 的假 op 等級封包。這兩個 hook 舊版都是空殼，所以方塊看起來像
     * 換了種類（如果玩家有仔細看的話），但完全沒有畫外框。
     *
     * 兩個封包都只送給這一個玩家的連線，不影響任何其他人、也不改真正的世界資料——跟
     * {@code sendBlockChange} 本身的「假」性質一致。
     */
    @Override
    public void sendFakeNBT(Player player, BlockVector3 pos, LinCompoundTag nbtData) {
        try {
            BlockPos blockPos = new BlockPos(pos.x(), pos.y(), pos.z());
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            CompoundTag nmsTag = convertLinCompoundTag(nbtData);
            Constructor<ClientboundBlockEntityDataPacket> ctor = ClientboundBlockEntityDataPacket.class
                    .getDeclaredConstructor(BlockPos.class, BlockEntityType.class, CompoundTag.class);
            ctor.setAccessible(true);
            Packet<?> packet = ctor.newInstance(blockPos, BlockEntityType.STRUCTURE_BLOCK, nmsTag);
            nmsPlayer.connection.send(packet);
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[WEPoC] sendFakeNBT 失敗，選取範圍的伺服器端視覺化外框可能不會顯示"
                    + "（不影響任何實際編輯功能，純視覺）: " + t);
        }
    }

    /** 只有這幾種簡單型別（structure_block 的視覺化 NBT 用得到的全部型別）；其餘型別記警告但不中斷。 */
    private static CompoundTag convertLinCompoundTag(LinCompoundTag lin) {
        CompoundTag tag = new CompoundTag();
        for (Map.Entry<String, LinTag<?>> entry : lin.value().entrySet()) {
            String key = entry.getKey();
            LinTag<?> value = entry.getValue();
            if (value instanceof LinStringTag stringTag) {
                tag.putString(key, stringTag.value());
            } else if (value instanceof LinIntTag intTag) {
                tag.putInt(key, intTag.valueAsInt());
            } else if (value instanceof LinByteTag byteTag) {
                tag.putByte(key, byteTag.valueAsByte());
            } else if (value instanceof LinCompoundTag compoundTag) {
                tag.put(key, convertLinCompoundTag(compoundTag));
            } else {
                Bukkit.getLogger().warning("[WEPoC] sendFakeNBT 遇到未支援的 NBT 型別 "
                        + value.getClass() + "（key=" + key + "），已略過該欄位");
            }
        }
        return tag;
    }

    @Override
    public void sendFakeOP(Player player) {
        try {
            ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
            // status 24~28 對應 op 等級 0~4：讓客戶端「以為」自己有權限顯示 game-master
            // 方塊（結構方塊等）的 UI/邊界框，跟這個玩家實際是不是 op 完全無關——單純解鎖
            // WorldEdit 選取範圍視覺化需要的客戶端渲染權限，不影響任何真正的伺服器端權限判斷。
            nmsPlayer.connection.send(new ClientboundEntityEventPacket(nmsPlayer, (byte) 28));
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[WEPoC] sendFakeOP 失敗，選取範圍的伺服器端視覺化外框可能不會顯示"
                    + "（不影響任何實際編輯功能，純視覺）: " + t);
        }
    }

    @Override
    public boolean simulateItemUse(World world, BlockVector3 position, BaseItem item, Direction face) {
        return false;
    }

    @Override
    public boolean canPlaceAt(World world, BlockVector3 position, BlockState blockState) {
        return true;
    }

    /**
     * 2026-09-14 委託人實機測試（取魔杖，`//wand` 內部會走到這裡轉換物品）時抓到的真實
     * bug：{@code BukkitAdapter.adapt(BaseItemStack)}／{@code adapt(ItemStack)} 這兩個
     * WorldEdit 自己的靜態方法，內部邏輯是「如果有註冊 adapter，就呼叫
     * {@code adapter.adapt(...)}」——而我們自己就是那個被註冊的 adapter，舊版寫法在這裡
     * 呼叫 {@code BukkitAdapter.adapt(...)} 會被導回我們自己這個方法，形成無窮遞迴，最終
     * {@code StackOverflowError} 產生一個幾萬字元的巨大字串，塞進封包編碼時直接把玩家的
     * 連線弄斷（{@code UTFDataFormatException: ... too long}）。
     *
     * 修法：直接照抄 {@code BukkitAdapter} 在「沒有 adapter 時」自己的 fallback 邏輯
     * （反解 {@code BukkitAdapter.class} 確認），不透過會繞回我們自己的靜態方法。
     */
    @Override
    public ItemStack adapt(BaseItemStack baseItemStack) {
        return new ItemStack(BukkitAdapter.adapt(baseItemStack.getType()), baseItemStack.getAmount());
    }

    @Override
    public BaseItemStack adapt(ItemStack itemStack) {
        return new BaseItemStack(ItemTypes.get(itemStack.getType().getKey().toString()), itemStack.getAmount());
    }

    @Override
    public Set<SideEffect> getSupportedSideEffects() {
        return Set.of(SideEffect.LIGHTING);
    }

    @Override
    public boolean regenerate(World world, Region region, Extent extent, RegenOptions options) {
        throw new UnsupportedOperationException("此最小 adapter 不支援地形重生成。");
    }

    @Override
    public boolean clearContainerBlockContents(World world, BlockVector3 pt) {
        return false;
    }

    @Override
    public void setBiome(Location location, BiomeType biome) {
        throw new UnsupportedOperationException("此最小 adapter 不支援自訂生物群系寫入。");
    }

    @Override
    public BiomeType getBiome(Location location) {
        throw new UnsupportedOperationException("此最小 adapter 不支援自訂生物群系讀取。");
    }

    @Override
    public boolean generateFeature(ConfiguredFeatureType feature, World world, EditSession session, BlockVector3 pt) {
        return false;
    }

    @Override
    public boolean generateStructure(StructureType feature, World world, EditSession session, BlockVector3 pt) {
        return false;
    }
}
