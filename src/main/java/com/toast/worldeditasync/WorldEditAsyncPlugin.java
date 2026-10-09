package com.toast.worldeditasync;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.toast.worldeditasync.light.RustLightEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_21_R1.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WorldEdit 混端非同步方塊引擎 PoC。
 *
 * - 直寫 LevelChunkSection + 手動 relight 的 Arclight 相容性、MinecraftServer tick 耗時介面
 *   已於第一階段驗證通過。
 * - {@code engine=on} 時，EditSessionEvent 於 BEFORE_HISTORY 階段換上 {@link WEAsyncExtent}，
 *   接管 WorldEdit 的方塊寫入（此 Arclight build 上 WorldEdit 原生路徑完全無法編輯）。
 * - {@code //undo}/{@code //redo} 一律攔截、導到 {@link UndoManager} 自建的 undo/redo，
 *   完全不呼叫 WorldEdit 原生 history/undo API（規格書第 5 節硬性要求，2026-09-13 拍板，
 *   不受先前崩潰根因診斷結果影響——當時的環境已確認被同機另一組高負載測試污染，
 *   淨室重測未能重現該崩潰）。
 */
public final class WorldEditAsyncPlugin extends JavaPlugin implements CommandExecutor, Listener {

    private static final Set<String> UNDO_REDO_COMMANDS = Set.of(
            "//undo", "/undo", "//redo", "/redo");
    private static final Set<String> UNDO_COMMANDS = Set.of("//undo", "/undo");
    private static final List<String> TIER_COMPLETION_WORDS = List.of("slow", "mid", "fast", "auto");

    private boolean editSessionHookRegistered = false;
    private volatile boolean adapterRegistered = false;
    // -1 = 尚未跑過/反射結構性失敗；-2 = 官方 adapter 已存在，不需要我們的修補；
    // >=0 = 我們自己重建成功，值為實際帶有屬性的方塊型別數。
    private static final int PROPERTY_REGISTRY_SKIPPED_OFFICIAL_ADAPTER = -2;
    private volatile int propertyRegistryFixedCount = -1;
    private volatile boolean engineEnabled = true;
    // 2026-09-19：執行期可切換的詳細日誌開關，預設關閉（避免正常運作時洗版）。
    // 開啟後 debugLog() 才會真的印東西，見 onEditSession/UndoManager/PasteExecutor 裡的呼叫點。
    private volatile boolean debugEnabled = false;
    // 診斷用暫時開關：僅供重現/排除原生崩潰場景時使用，正常使用不應開啟。
    private final boolean diagnosticAllowNativeUndo = System.getProperty("wepoc.allowNativeUndo") != null;
    // 每個 EditSessionEvent 觸發時新建一個 WEAsyncExtent，用併發表追蹤最近一個實例以便回報統計
    private final Map<UUID, WEAsyncExtent> lastExtentByActor = new ConcurrentHashMap<>();
    /**
     * 規格書第 4 節：從指令最後一個參數剝出來的檔位，暫存到這個 actor「下一次」觸發的
     * EditSessionEvent 用一次就丟（見 onCommandPreprocess/onEditSession）。沒有暫存值時
     * 回傳 mid，符合「未填寫檔位參數時，預設為 mid」。
     *
     * 2026-09-14 補防呆：正常情況下 put 跟 remove 都發生在同一次指令的同步派發過程裡
     * （PlayerCommandPreprocessEvent → WorldEdit 處理該指令 → EditSessionEvent，全部
     * 在同一個主執行緒呼叫堆疊內完成），理論上不會殘留。但如果玩家打的指令選區沒設好、
     * 語法錯誤、或任何原因讓 WorldEdit 根本沒建立 EditSession（例如指令在更前面就被
     * WorldEdit 自己擋下報錯），這個 put 進去的值就永遠不會被消費，會一直卡在 map 裡
     * ——單一筆不會怎樣，但長時間下來每個「打過帶檔位指令又失敗」的玩家都會留一筆
     * 用不到的殘留。加上時間戳記＋TTL，consume 時比對是否過期，並用一個低頻的
     * repeating task 定期清掉沒被消費、放太久的殘留項目。
     */
    /**
     * 2026-09-15：{@code expectedSize} 是這輪追加的——實測發現 {@link WEAsyncExtent} 內部
     * {@link ChangeBuffer} 用預設容量（4096）起手，大型操作要經歷幾十次「容量翻倍+整批
     * rehash」才能長到最終大小，這個過程本身的成本遠比想像中高：8,000,000 格 19.2 秒完成，
     * 但只大 24%（9,938,375 格）就沒能在 60 秒看門狗時限內做完，這種非線性的斷崖式變化
     * 正是雜湊表反覆擴容的典型癥狀（跟先前 {@link ChangeBuffer} 雜湊碰撞那次踩的坑同一個
     * 家族的問題，但這次是擴容次數而非碰撞鏈）。反正這個估計值早就為了危險確認閘算過一次
     * （{@link #estimateOperationVolume}），這裡直接一起帶著走，讓 {@link WEAsyncExtent}
     * 一開始就用正確的容量建立，不用一路翻倍上去。
     */
    private record PendingTier(SpeedTier tier, long insertedAtMillis, long expectedSize) {
    }

    private static final long PENDING_TIER_TTL_MILLIS = 5000L;
    private final Map<UUID, PendingTier> pendingTierByActor = new ConcurrentHashMap<>();

    /**
     * 2026-09-15 委託人拿一個 5676 萬格的城堡 schematic 做壓力測試後提出的安全閘：任何預估
     * 會動到超過 {@link #DANGER_BLOCK_THRESHOLD} 格的操作（選區類指令看目前選區體積、
     * {@code //paste} 看 clipboard 體積），一律先擋下來，要求玩家先送出 {@code /wepoc danger
     * confirm} 才會放行——而且只放行「確認後的下一個 WorldEdit 指令」這一次，用一次就丟
     * （跟 {@link #pendingTierByActor} 同一種「用一次就丟＋TTL 防呆」模式）。委託人明確
     * 要求的語意：如果確認後玩家送出的下一個指令根本不是 WorldEdit 操作指令，這次確認直接
     * 失效，不能留著等下一次真的送操作指令時才用掉——見 {@link #onCommandPreprocess} 開頭
     * 那段判斷。
     *
     * 只用「選區/clipboard 的體積」而不是「實際會被改到的格數」來估計，是刻意的簡化：
     * 前者在指令真正執行前就能查到（`//replace` 之類的指令實際影響格數必須跑完才知道），
     * 用體積當保守上限（worst case）估計，足以擋下真正危險的大範圍操作，不需要先跑一次
     * 才知道要不要擋。
     */
    // 2026-09-15 用真實壓力測試校正過的數字：5676 萬格的城堡同步貼上，玩家看到「完成」
    // 訊息花了 81 秒（實際主執行緒卡住時間更長——WorldEdit 自己的原生 history 收尾還要
    // 再花時間，見驗證報告「二點十八」），已經超過 Paper 預設 60 秒看門狗時限。反推吞吐量
    // 約 70 萬格/秒，用預設看門狗時限反推大概 4200 萬格左右就會開始踩線，原本訂的 1 億格
    // 明顯太高——2000 萬格留了足夠安全邊際（且這次測試的城堡屬性複雜度不低，實際門檻
    // 可能因方塊種類更單純而更高，這裡刻意保守）。
    // 2026-09-19：改成可由 config.yml 的 danger-gate 覆寫的實例欄位，見 onEnable 裡的
    // loadWepocConfig()；沒有 config.yml 或欄位缺漏時維持這裡寫的預設值，行為不變。
    private long DANGER_BLOCK_THRESHOLD = 20_000_000L;
    private long DANGER_CONFIRM_TTL_MILLIS = 30_000L;
    private final Map<UUID, Long> pendingDangerConfirmByActor = new ConcurrentHashMap<>();
    private UndoManager undoManager;
    private PasteExecutor pasteExecutor;

    /**
     * 2026-09-15：{@link PasteExecutor} 自己接管 `//paste`，用同一套 {@code EditSessionEvent}
     * 機制建立 EditSession（見 {@code PasteExecutor#doStart}），會觸發跟原生指令完全一樣的
     * {@link #onEditSession}——但那裡預設假設「呼叫端是同步指令，下一 tick 前一定已經收集完
     * 所有變更」，排了一個下一 tick 自動打包丟給 {@link UndoManager#enqueueForward} 的動作。
     * {@link PasteExecutor} 的 COPY 階段是跨很多 tick 才會做完，不能套用這個假設——用這個集合
     * 讓 {@link #onEditSession} 對這些 actor 跳過自動打包那一步，改成 {@link PasteExecutor}
     * 自己在 COPY 真正跑完時才呼叫 {@link UndoManager#enqueueForward}。
     */
    private final Set<UUID> customPasteInFlight = ConcurrentHashMap.newKeySet();

    void markCustomPasteInFlight(UUID actor) {
        customPasteInFlight.add(actor);
    }

    void clearCustomPasteInFlight(UUID actor) {
        customPasteInFlight.remove(actor);
    }

    /** 取出並移除某個 actor 最近一次 {@link #onEditSession} 換上的 {@link WEAsyncExtent}。 */
    WEAsyncExtent takeLastExtent(UUID actor) {
        return lastExtentByActor.remove(actor);
    }

    /**
     * {@link PasteExecutor} 自己組出 {@code EditSession} 前呼叫——它已經直接從 clipboard
     * 的 region 知道精確格數，用同一套 {@link PendingTier} 機制把這個提示帶給
     * {@link #onEditSession}，讓 //paste 的 {@link WEAsyncExtent} 也用正確容量建立，
     * 不會跟其他大型操作一樣吃到反覆擴容的成本。
     */
    void stashPendingSizeHint(UUID actor, SpeedTier tier, long expectedSize) {
        pendingTierByActor.put(actor, new PendingTier(tier, System.currentTimeMillis(), expectedSize));
    }

    /**
     * 2026-09-15：第三方外掛（例如 BetterStructures 生成大型建築時）呼叫
     * {@code WorldEdit.getInstance().newEditSession(World)} 完全不帶 actor——反解確認過，
     * 這類呼叫觸發的 {@link EditSessionEvent#getActor()} 永遠是 {@code null}。原本這裡直接
     * {@code return}、整段編輯完全放給 WorldEdit 原生同步路徑處理，等於這類操作不管
     * {@code engine} 開關為何都攔不到——壓力測試已經證實這條原生路徑在大型操作下會讓主執行緒
     * 卡住超過看門狗時限（見驗證報告「二點十八」）。與其一個個去 patch 各個第三方外掛讓它們
     * 補上 actor（脆弱、且以後每次外掛更新都要重新確認），改成在這裡用一個固定的系統假
     * {@code UUID} 頂替，讓「沒有真人 actor 的編輯」也能走同一套 {@link WEAsyncExtent}/
     * {@link UndoManager} 分批限流管線。下游（{@link WEAsyncExtent}、{@link UndoManager}）
     * 全程只用 {@code UUID} 當 key，從未直接碰 WorldEdit 的 {@code Actor} 物件本身，且
     * {@code notifyPlayer}/{@code maybeSendProgressActionBar} 對「找不到對應線上玩家」
     * 已經是 null-safe（{@code Bukkit.getPlayer(actor) == null} 就靜默跳過，只留 log），
     * 所以這個系統假 actor 不需要下游額外配合就能安全共用整條管線——它甚至會累積自己的
     * undo 堆疊，理論上管理員之後可以用一個綁定同一個系統 UUID 的指令 undo 掉一次
     * 系統觸發的大型結構貼上（目前尚未實作對應指令，先留著這個可能性）。
     */
    static final UUID SYSTEM_ACTOR_UUID = new UUID(0L, 0L);

    /**
     * 2026-09-19：委託人要求的執行期 debug 開關——想排查「某個外掛的呼叫到底有沒有被
     * 我們攔截到」這類問題時，不用改程式碼重編，直接 {@code /wepoc debug on} 就能看到
     * 每次 {@link #onEditSession} 觸發的 actor/engine 狀態/選到的檔位，以及
     * {@link UndoManager}/{@link PasteExecutor} 的 job 階段轉換。預設關閉，正常運作
     * 不會多印任何東西。
     */
    boolean isDebugEnabled() {
        return debugEnabled;
    }

    /** 懶人版：所有給玩家的訊息改走 actionbar，避免洗版聊天欄。非玩家（console）仍用一般訊息。 */
    void sendActionBar(CommandSender target, String message) {
        if (target instanceof Player p) {
            p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                    new net.md_5.bungee.api.chat.TextComponent(message));
        } else {
            target.sendMessage(message);
        }
    }

    /** actionbar 約 2~3 秒就淡出；重要訊息（例如危險門檻警告）每 2 秒重送一次、持續 {@code seconds} 秒。 */
    void sendActionBarFor(Player player, String message, int seconds) {
        sendActionBar(player, message);
        int[] remaining = {Math.max(0, seconds / 2)};
        if (remaining[0] == 0) {
            return;
        }
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            if (!player.isOnline() || remaining[0]-- <= 0) {
                task.cancel();
                return;
            }
            sendActionBar(player, message);
        }, 40L, 40L);
    }

    /** 只有 {@link #debugEnabled} 開著才會真的印，統一加 {@code [WEPoC-DEBUG]} 前綴方便篩選 log。 */
    void debugLog(String message) {
        if (debugEnabled) {
            getLogger().info("[WEPoC-DEBUG] " + message);
        }
    }

    /**
     * 2026-09-19：讀取 {@code config.yml}，覆寫危險確認閘的門檻/TTL、三個固定檔位的
     * 每-tick 預算、引擎預設開關狀態。必須在 {@link #onEnable} 建立
     * {@link UndoManager}（進而建立 {@link AutoThrottleController}）之前呼叫，因為
     * {@link SpeedTier#configureBudgets} 之後改值不會回溯套用到已經算好的
     * {@code AutoThrottleController} 上限快取。
     */
    private void loadWepocConfig() {
        saveDefaultConfig();
        var cfg = getConfig();
        engineEnabled = cfg.getBoolean("engine.enabled-by-default", true);
        DANGER_BLOCK_THRESHOLD = cfg.getLong("danger-gate.block-threshold", 20_000_000L);
        DANGER_CONFIRM_TTL_MILLIS = cfg.getLong("danger-gate.confirm-ttl-seconds", 30) * 1000L;
        SpeedTier.configureBudgets(
                cfg.getLong("speed-tiers.slow-budget-ms", 2) * 1_000_000L,
                cfg.getLong("speed-tiers.mid-budget-ms", 6) * 1_000_000L,
                cfg.getLong("speed-tiers.fast-budget-ms", 15) * 1_000_000L);
    }

    @Override
    public void onEnable() {
        loadWepocConfig();
        getCommand("wepoc").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        undoManager = new UndoManager(this);
        undoManager.setMaxStackPerPlayer(getConfig().getInt("undo.max-stack-per-player", 15));
        pasteExecutor = new PasteExecutor(this, undoManager);
        Bukkit.getScheduler().runTaskTimer(this, this::sweepStalePendingTiers, 100L, 100L);
        try {
            WorldEdit.getInstance().getEventBus().register(this);
            editSessionHookRegistered = true;
            getLogger().info("[WEPoC] EditSessionEvent 訂閱註冊成功");
        } catch (Throwable t) {
            getLogger().severe("[WEPoC] EditSessionEvent 訂閱註冊失敗: " + t);
            t.printStackTrace();
        }
        registerBukkitImplAdapter();
    }

    /**
     * 把 {@link WEBukkitAdapter} 塞進 WorldEdit 自己的 adapter 欄位——但只在「這個平台
     * 真的沒有官方 adapter」時才做。
     *
     * 2026-09-15 委託人的建築服環境（純 PaperMC，不是 Arclight）暴露了一個沒考慮到的情境：
     * 純 Paper 本身就在 WorldEdit 官方原生 adapter 清單裡，{@code WorldEditPlugin} 自己的
     * {@code loadAdapter()} 在啟動時就已經找到並注入了官方 adapter。這個方法原本無條件執行
     * 反射注入，會把官方已經裝好的 adapter **蓋掉**，換成我們這個功能刻意做得比較少的最小
     * 實作（例如 {@link WEBukkitAdapter} 完全不支援地形重生成、自訂生物群系等）——等於在
     * 一個原本就正常的環境上，把功能完整的官方實作換成一個閹割版，是純粹的倒退，不是修復。
     *
     * 修法：反射拿到 {@code adapter} 欄位（本來就需要，見下方）後，先問它「現在已經有
     * adapter 了嗎」——這一步不需要額外反射，直接把欄位值轉型成它實作的公開介面
     * {@code com.sk89q.worldedit.util.lifecycle.Lifecycled} 呼叫公開方法 {@code isValid()}
     * 即可（{@code WorldEditPlugin} 自己對外的 getter 是 package-private，叫不到，但介面
     * 本身是 public，轉型呼叫合法）。已經有效就代表這個平台（不管是純 Paper 還是任何未來
     * 版本 Arclight 補齊了官方支援）已經有可用的官方實作，直接跳過，讀取/寫入/屬性表全部
     * 交給官方 adapter 負責，不去動它。只有無效（目前只在 Arclight 這種缺官方 adapter 的
     * 環境會發生）才繼續執行下面這段注入。
     *
     * WorldEdit 在自己的 onEnable（比我們早，因為 plugin.yml 宣告 depend: [WorldEdit]）
     * 掃描 classpath 尋找官方 adapter 失敗後，會把內部的 {@code SimpleLifecycled<BukkitImplAdapter>}
     * 設成 invalid。那個欄位是 private，但型別本身（{@code SimpleLifecycled}）跟它的
     * {@code newValue(T)} 方法都是 public——用反射拿到欄位參照後，直接呼叫 public 方法即可，
     * 不需要碰任何 private 方法。
     *
     * 這個做法跟 WorldEdit 7.3.7 這個確切版本的內部欄位結構（欄位名稱 "adapter"、
     * {@code SimpleLifecycled} 這個型別、{@code newValue(Object)} 這個方法簽章）綁死——
     * WorldEdit 一旦升級改了這些內部細節，反射會直接失敗。失敗時不能安靜吞掉：這裡明確
     * 區分「反射打到的內部結構跟預期不符」（NoSuchFieldException/NoSuchMethodException/
     * ClassCastException，八成是 WorldEdit 版本變了）跟其他未預期例外，兩種都會在啟動時
     * 用最顯眼的方式報告「引擎的讀取類指令功能已停用」，不要讓管理員在指令莫名壞掉後
     * 還要重新從頭排查一輪才發現是這裡的問題。
     */
    private void registerBukkitImplAdapter() {
        com.sk89q.worldedit.bukkit.WorldEditPlugin worldEditPlugin =
                (com.sk89q.worldedit.bukkit.WorldEditPlugin) Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (worldEditPlugin == null) {
            getLogger().warning("[WEPoC] 找不到 WorldEdit 插件實例，無法註冊 BukkitImplAdapter");
            return;
        }
        try {
            java.lang.reflect.Field adapterField =
                    com.sk89q.worldedit.bukkit.WorldEditPlugin.class.getDeclaredField("adapter");
            adapterField.setAccessible(true);
            Object lifecycled = adapterField.get(worldEditPlugin);
            // WorldEditPlugin#getBukkitImplAdapter()/getLifecycledBukkitImplAdapter() 都是
            // package-private（反解確認 flags=0x0000），從我們自己的套件叫不到，但這個欄位
            // 實際型別 SimpleLifecycled 實作了公開介面 com.sk89q.worldedit.util.lifecycle.Lifecycled
            // ——轉型成這個公開介面型別後就能直接呼叫它的公開方法（isValid()），不需要碰任何
            // package-private 成員，也不用另外反射一個 getter。
            if (lifecycled instanceof com.sk89q.worldedit.util.lifecycle.Lifecycled<?> existing && existing.isValid()) {
                adapterRegistered = true;
                propertyRegistryFixedCount = PROPERTY_REGISTRY_SKIPPED_OFFICIAL_ADAPTER;
                getLogger().info("[WEPoC] 偵測到這個平台已經有官方 BukkitImplAdapter（例如純 Paper），"
                        + "不注入我們自己的最小實作，讀取/寫入/屬性表全部交給官方 adapter 負責");
                return;
            }
            java.lang.reflect.Method newValue = lifecycled.getClass().getMethod("newValue", Object.class);
            WEBukkitAdapter bukkitAdapter = new WEBukkitAdapter();
            newValue.invoke(lifecycled, bukkitAdapter);
            adapterRegistered = true;
            getLogger().info("[WEPoC] 已註冊 WEBukkitAdapter 作為 WorldEdit 的 BukkitImplAdapter"
                    + "（讓 //copy 等讀取類指令與 engine=off 時的原生寫入不再被 UnsupportedVersionEditException 擋住）");
            // WorldEdit 自己的 onEnable 在我們的 adapter 存在之前就已經跑過
            // initializeRegistries()，把每個 BlockType 的屬性表永久快取成空的
            // （見 PropertyRegistryFixer 開頭的說明），這裡補一次重建。
            propertyRegistryFixedCount = PropertyRegistryFixer.refreshAll(bukkitAdapter, getLogger());
        } catch (NoSuchFieldException | NoSuchMethodException | ClassCastException e) {
            adapterRegistered = false;
            getLogger().severe("====================================================================");
            getLogger().severe("[WEPoC] 【嚴重】WorldEdit 內部結構已變更，BukkitImplAdapter 反射注入失敗！");
            getLogger().severe("[WEPoC] 這代表 WorldEdit 版本可能已經更新，跟這個 PoC 寫死假設的");
            getLogger().severe("[WEPoC] WorldEditPlugin.adapter 欄位/SimpleLifecycled.newValue() 簽章不符。");
            getLogger().severe("[WEPoC] 影響範圍：//copy //smooth //deform 等讀取類指令、以及 engine=off");
            getLogger().severe("[WEPoC] 時的原生寫入路徑，將全部回到「Editing on unsupported versions is");
            getLogger().severe("[WEPoC] disabled」的不可用狀態。我們自己 engine=on 的寫入路徑不受影響。");
            getLogger().severe("[WEPoC] 請先確認目前安裝的 WorldEdit 版本，並重新比對 WorldEditPlugin 原始碼");
            getLogger().severe("[WEPoC] 的 adapter 欄位定義，再考慮升級這個 PoC 的相依版本或調整反射邏輯。");
            getLogger().severe("====================================================================");
            getLogger().severe("[WEPoC] 原始例外: " + e);
            e.printStackTrace();
        } catch (Throwable t) {
            adapterRegistered = false;
            getLogger().severe("[WEPoC] 註冊 BukkitImplAdapter 失敗（非預期例外），讀取類指令將維持原本的不可用狀態: " + t);
            t.printStackTrace();
        }
    }

    /**
     * 規格書第 4 節：檔位參數固定放在「所有支援本引擎的指令」的最後一個參數位置
     * （例：{@code //paste fast}、{@code //set stone mid}、{@code //undo auto}），
     * 未填時預設 mid。//undo //redo 額外一律攔截導到自建 UndoManager，完全不讓
     * WorldEdit 原生指令執行；其餘指令只做「剝掉尾端檔位關鍵字、記住給下一次
     * EditSessionEvent 用」，指令本身原封不動交給 WorldEdit 處理。
     *
     * 範圍刻意只鎖定「//」開頭的指令（WorldEdit 自己專用、幾乎不會跟其他外掛的單斜線
     * 指令撞名的命名空間）加上我們原本就攔截的 {@code /undo}/{@code /redo} 這兩個
     * WorldEdit 也會註冊的單斜線別名——不對任何其他單斜線指令做尾端剝字，避免誤觸到
     * 剛好最後一個參數叫 fast/mid/slow/auto 的其他外掛指令。
     *
     * diagnosticAllowNativeUndo 開啟時整段放行給原生路徑，僅供根因排除測試使用。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (diagnosticAllowNativeUndo) {
            return;
        }
        String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length == 0) {
            return;
        }
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        boolean isWorldEditNamespace = cmd.startsWith("//") || UNDO_REDO_COMMANDS.contains(cmd);
        Player player = event.getPlayer();

        if (!isWorldEditNamespace) {
            // 委託人明確要求的語意：danger confirm 只保護「確認後緊接著送出的下一個指令」，
            // 如果那個指令根本不是 WorldEdit 操作指令，確認就直接失效，不能留到之後真的送出
            // 操作指令時才被撿去用。
            pendingDangerConfirmByActor.remove(player.getUniqueId());
            return;
        }

        // 見 PendingTier 欄位註解：這個估計值原本只為了危險確認閘算一次，現在也順便拿來
        // 當 WEAsyncExtent/ChangeBuffer 的初始容量提示，避免大型操作經歷幾十次擴容+rehash。
        long estimatedVolume = 0L;
        if (!UNDO_REDO_COMMANDS.contains(cmd)) {
            estimatedVolume = estimateOperationVolume(player, cmd);
            Long confirmedAtMillis = pendingDangerConfirmByActor.remove(player.getUniqueId());
            boolean dangerConfirmed = confirmedAtMillis != null
                    && System.currentTimeMillis() - confirmedAtMillis <= DANGER_CONFIRM_TTL_MILLIS;
            if (!dangerConfirmed && estimatedVolume > DANGER_BLOCK_THRESHOLD) {
                event.setCancelled(true);
                sendActionBarFor(player, String.format(Locale.ROOT,
                        "[WEPoC] 預估動到約 %,d 格，超過 %,d 格危險門檻。確定要做請先輸入 /wepoc danger confirm，"
                                + "%d 秒內再送出一次指令",
                        estimatedVolume, DANGER_BLOCK_THRESHOLD, DANGER_CONFIRM_TTL_MILLIS / 1000), 8);
                return;
            }
        }

        // 懶人版：檔位一律 auto。指令尾端的 slow/mid/fast/auto 仍會被剝掉（相容舊習慣），但不再生效。
        SpeedTier tier = SpeedTier.AUTO;
        if (parts.length > 1 && isTierKeyword(parts[parts.length - 1])) {
            parts = Arrays.copyOf(parts, parts.length - 1);
        }

        if (UNDO_REDO_COMMANDS.contains(cmd)) {
            event.setCancelled(true);
            ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
            UUID actorId = player.getUniqueId();
            SpeedTier finalTier = tier;
            // 2026-09-14 修掉一個實測抓到的排序 race：正向編輯的 enqueueForward 是在
            // onEditSession 裡透過 Bukkit.getScheduler().runTask(...) 延後一個 tick 才真正
            // push 進 UndoManager 的佇列（因為 EditSessionEvent 觸發當下，wrapped extent
            // 還沒收集完整個指令的變更）。undo/redo 原本是同步、當下立刻 push——如果玩家
            // 背靠背連續打「//set ... fast」緊接著「//undo slow」，undo 的 push 反而會搶在
            // 前一個 //set 的 push 之前，導致 //undo 復原到的是更早一筆編輯，不是玩家剛剛
            // 那筆 //set。這裡讓 undo/redo 也一樣延後一個 tick 再 push，兩種路徑用同一種
            // 排程時機，Bukkit 的 scheduler 對同一 tick 內排入、同樣 0-tick 延遲的工作會照
            // 排入順序執行，順序才會跟玩家實際下指令的順序一致。
            Bukkit.getScheduler().runTask(this, () -> {
                if (UNDO_COMMANDS.contains(cmd)) {
                    undoManager.requestUndo(actorId, level, finalTier);
                } else {
                    undoManager.requestRedo(actorId, level, finalTier);
                }
            });
            return;
        }

        // 2026-09-15：//paste 整個接管，理由見 PasteExecutor 類別註解——WorldEdit 原生
        // Operations.completeLegacy 本身是一次跑完整個區域的同步迴圈，不管我們自己的
        // WEAsyncExtent/UndoManager 多能分批都救不了它，實測 56.7M 格城堡在 Arclight 上
        // 兩度觸發看門狗證實了這一點（見驗證報告「二點二十二」）。engine=off 時維持原生路徑
        // （跟其餘指令的 engine 開關語意一致），且只在玩家真的有官方權限時才接管——沒有權限
        // 就讓它落回原生路徑，原生自己的權限錯誤訊息會處理，不用我們在這裡重複一套。
        if (cmd.equals("//paste") && engineEnabled && player.hasPermission("worldedit.clipboard.paste")) {
            event.setCancelled(true);
            String[] pasteArgs = Arrays.copyOfRange(parts, 1, parts.length);
            SpeedTier finalTier = tier;
            pasteExecutor.handlePasteCommand(player, pasteArgs, finalTier);
            return;
        }

        // 一般編輯指令：把（可能已剝掉檔位關鍵字的）指令原樣交給 WorldEdit，
        // 檔位另外記著，等這個指令觸發的 EditSessionEvent 進來時才消耗掉一次。
        event.setMessage(String.join(" ", parts));
        pendingTierByActor.put(player.getUniqueId(), new PendingTier(tier, System.currentTimeMillis(), estimatedVolume));
    }

    /** 見 {@link #pendingTierByActor} 欄位註解：清掉沒被任何 EditSessionEvent 消費、放太久的殘留項目。 */
    private void sweepStalePendingTiers() {
        long now = System.currentTimeMillis();
        pendingTierByActor.entrySet().removeIf(e -> now - e.getValue().insertedAtMillis() > PENDING_TIER_TTL_MILLIS);
        // 見 pendingDangerConfirmByActor 欄位註解：正常情況下會在下一個指令就被消費/清掉，
        // 這裡只是保底，避免玩家確認完之後完全不再送任何指令，殘留一筆永遠用不到的確認。
        pendingDangerConfirmByActor.entrySet().removeIf(e -> now - e.getValue() > DANGER_CONFIRM_TTL_MILLIS);
    }

    private static boolean isTierKeyword(String token) {
        return TIER_COMPLETION_WORDS.contains(token.toLowerCase(Locale.ROOT));
    }

    /**
     * 危險操作防呆的體積估計：用「選區/clipboard 的體積」當保守上限，不是「實際會被改到的
     * 格數」（後者要跑完才知道）。{@code //paste} 看 clipboard 體積，其餘看目前選區體積。
     * 查不到（選區沒設好、clipboard 是空的、任何反解沒預期到的例外）就回傳 0——不擋，讓
     * WorldEdit 自己既有的錯誤訊息（"Make a region selection first." 等）去處理，不在這裡
     * 越權搶著報錯。
     */
    private long estimateOperationVolume(Player player, String cmd) {
        try {
            com.sk89q.worldedit.entity.Player weActor = BukkitAdapter.adapt(player);
            com.sk89q.worldedit.LocalSession session = WorldEdit.getInstance().getSessionManager().get(weActor);
            if (cmd.equals("//paste")) {
                return session.getClipboard().getClipboard().getRegion().getVolume();
            }
            com.sk89q.worldedit.world.World world = BukkitAdapter.adapt(player.getWorld());
            return session.getSelection(world).getVolume();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 規格書第 4 節：檔位參數位置要支援 tab 補全，列出 fast/mid/slow/auto。
     * 用 {@link TabCompleteEvent} 而不是 {@code PluginCommand#setTabCompleter}——後者
     * 只能替自己宣告的指令註冊補全，WorldEdit 的 {@code //set}/{@code //paste} 等指令
     * 是它自己註冊的，我們只能用這個「攔截任何指令的補全請求」的事件去追加選項，沒辦法
     * 精準判斷玩家當下是不是「剛好在打最後一個參數」——所以這裡的做法是隻要偵測到是
     * 在幫一個 WorldEdit 指令補全（見 {@link #onCommandPreprocess} 同樣的命名空間判斷），
     * 就把還沒被目前輸入排除掉的檔位關鍵字一併加進候選清單，不強制要求一定是最後一格。
     */
    @EventHandler
    public void onTabComplete(TabCompleteEvent event) {
        String buffer = event.getBuffer();
        String[] tokens = buffer.split("\\s+", -1);
        if (tokens.length < 2) {
            return;
        }
        String cmd = tokens[0].toLowerCase(Locale.ROOT);
        if (!(cmd.startsWith("//") || UNDO_REDO_COMMANDS.contains(cmd))) {
            return;
        }
        String currentToken = tokens[tokens.length - 1].toLowerCase(Locale.ROOT);
        List<String> merged = new ArrayList<>(event.getCompletions());
        for (String tierWord : TIER_COMPLETION_WORDS) {
            if (tierWord.startsWith(currentToken) && !merged.contains(tierWord)) {
                merged.add(tierWord);
            }
        }
        event.setCompletions(merged);
    }

    @Override
    public void onDisable() {
        try {
            WorldEdit.getInstance().getEventBus().unregister(this);
        } catch (Throwable ignored) {
        }
    }

    // BEFORE_HISTORY：換上只負責記錄變更前後狀態的 WEAsyncExtent，不在呼叫執行緒上落地寫入。
    // 指令執行緒同步跑完（累積好全部變更）後，下一個 tick 才把整包 EditRecord 交給
    // UndoManager 排隊，用限時分批流程落地——forward/undo/redo 三者共用同一套排程與寫入路徑。
    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        debugLog("EditSessionEvent 觸發: actor=" + event.getActor()
                + " world=" + event.getWorld() + " stage=" + event.getStage()
                + " engine=" + (engineEnabled ? "on" : "off"));
        if (!engineEnabled) {
            return;
        }
        if (event.getStage() != EditSession.Stage.BEFORE_HISTORY) {
            return;
        }
        try {
            org.bukkit.World bukkitWorld = BukkitAdapter.adapt(event.getWorld());
            ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
            // 見 SYSTEM_ACTOR_UUID 欄位註解：沒有真人 actor（第三方外掛的無 actor 呼叫）
            // 時頂替一個固定系統假 UUID，讓這類編輯一樣被接管、走分批限流，而不是放給
            // WorldEdit 原生同步路徑直接卡住主執行緒。
            UUID actorId = event.getActor() != null ? event.getActor().getUniqueId() : SYSTEM_ACTOR_UUID;
            if (event.getActor() == null) {
                debugLog("actor=null（第三方外掛未帶 actor 呼叫 WorldEdit），改用系統假 UUID="
                        + SYSTEM_ACTOR_UUID + " 接管此次編輯");
            }
            // 用一次就丟：這個 tier/容量提示只給「引發這次 EditSessionEvent 的那個指令」用，
            // 沒有暫存值（例如指令沒帶檔位參數）時預設 mid、預設容量。remove 而非 get，
            // 同一筆不會被下一次 EditSessionEvent 重複吃到。
            PendingTier pending = pendingTierByActor.remove(actorId);
            boolean pendingFresh = pending != null && System.currentTimeMillis() - pending.insertedAtMillis() <= PENDING_TIER_TTL_MILLIS;
            SpeedTier tier = pendingFresh ? pending.tier() : SpeedTier.AUTO;
            // 見 PendingTier 欄位註解：用預估格數當 ChangeBuffer 初始容量，避免大型操作
            // 反覆擴容+rehash（實測：不給提示時，一千萬格級別的操作光是這個成本就能讓整個
            // 操作撐不過看門狗時限，即使已知同尺寸操作在給對容量後遠快於此）。查不到估計值
            // 或估計值不可信（<=0）時，回退到 WEAsyncExtent 自己的預設值（4096）。
            long sizeHint = pendingFresh && pending.expectedSize() > 0 ? pending.expectedSize() : 4096;
            WEAsyncExtent wrapped = new WEAsyncExtent(event.getExtent(), level, this,
                    (int) Math.min(Integer.MAX_VALUE, sizeHint));
            event.setExtent(wrapped);
            lastExtentByActor.put(actorId, wrapped);
            String worldKey = level.dimension().location().toString();
            debugLog("接管完成: actor=" + actorId + " tier=" + tier + " sizeHint=" + sizeHint
                    + " customPasteInFlight=" + customPasteInFlight.contains(actorId));
            // 見 customPasteInFlight 欄位註解：PasteExecutor 自己接管的 //paste 會跨很多
            // tick 才收集完變更，不能套用「下一 tick 就自動打包」這個假設——它會自己在
            // COPY 真正跑完時呼叫 enqueueForward，這裡跳過。
            if (!customPasteInFlight.contains(actorId)) {
                Bukkit.getScheduler().runTask(this, () -> {
                    EditRecord record = new EditRecord(actorId, worldKey, wrapped.getChanges());
                    undoManager.enqueueForward(actorId, level, record, tier);
                });
            }
        } catch (Throwable t) {
            getLogger().severe("[WEPoC] 包裝 extent 失敗，本次編輯將維持原生路徑: " + t);
            t.printStackTrace();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendActionBar(sender, "用法: /wepoc <debug|danger> [args]");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "debug":
                return handleDebug(sender, args);
            case "danger":
                return handleDangerConfirm(sender, args);
            default:
                sendActionBar(sender, "用法: /wepoc <debug|danger> [args]");
                return true;
        }
    }

    private boolean handleDebug(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendActionBar(sender, "用法: /wepoc debug <on|off>，目前=" + (debugEnabled ? "on" : "off"));
            return true;
        }
        debugEnabled = args[1].equalsIgnoreCase("on");
        sendActionBar(sender, "[WEPoC] debug 設為 " + (debugEnabled ? "on" : "off"));
        getLogger().info("[WEPoC] debug 設為 " + (debugEnabled ? "on" : "off"));
        return true;
    }

    // 見 pendingDangerConfirmByActor 欄位註解。/wepoc danger confirm
    private boolean handleDangerConfirm(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendActionBar(sender, "需在遊戲內執行");
            return true;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            sendActionBar(sender, "用法: /wepoc danger confirm（用來放行上一個因為預估影響格數"
                    + "超過 " + String.format(Locale.ROOT, "%,d", DANGER_BLOCK_THRESHOLD) + " 格被擋下的操作，"
                    + "只對「緊接著送出的下一個指令」有效，用一次就丟）");
            return true;
        }
        pendingDangerConfirmByActor.put(player.getUniqueId(), System.currentTimeMillis());
        sendActionBar(sender, String.format(Locale.ROOT,
                "[WEPoC] 危險操作已確認，%d 秒內送出的下一個 WorldEdit 指令會被放行一次"
                        + "（不管那個指令實際大小），送出其他任何指令都會讓這次確認失效。",
                DANGER_CONFIRM_TTL_MILLIS / 1000));
        return true;
    }
}
