package net.kalloer1.maidroster.server;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import net.kalloer1.maidroster.config.RosterConfig;
import net.kalloer1.maidroster.roster.MaidStatus;
import net.kalloer1.maidroster.roster.RosterData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 点名册的服务端逻辑：查询女仆状态、召唤、批量改工作模式与作息。
 * <p>
 * 所有操作都以服务端为权威，客户端 GUI 只负责发送意图。
 */
public final class RosterService {
    private RosterService() {
    }

    // ------------------------------------------------------------------
    // 查找
    // ------------------------------------------------------------------

    /**
     * 在所有已加载维度里按 UUID 找一名属于该玩家的女仆。
     * <p>
     * {@link ServerLevel#getEntity(UUID)} 是哈希查找，遍历维度的开销可以忽略。
     */
    @Nullable
    public static EntityMaid findLoadedMaid(MinecraftServer server, UUID maidId, ServerPlayer owner) {
        // 先查玩家所在维度，绝大多数情况一次命中
        EntityMaid maid = lookupIn(owner.serverLevel(), maidId, owner);
        if (maid != null) {
            return maid;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level == owner.serverLevel()) {
                continue;
            }
            maid = lookupIn(level, maidId, owner);
            if (maid != null) {
                return maid;
            }
        }
        return null;
    }

    @Nullable
    private static EntityMaid lookupIn(ServerLevel level, UUID maidId, ServerPlayer owner) {
        Entity entity = level.getEntity(maidId);
        if (entity instanceof EntityMaid maid && maid.isAlive() && maid.isOwnedBy(owner)) {
            return maid;
        }
        return null;
    }

    /** 从世界存档数据里查一名未加载女仆的位置记录。 */
    @Nullable
    private static MaidInfo findUnloadedInfo(ServerPlayer owner, UUID maidId) {
        MaidWorldData data = MaidWorldData.get(owner.level());
        if (data == null) {
            return null;
        }
        List<MaidInfo> infos = data.getPlayerMaidInfos(owner);
        if (infos == null) {
            return null;
        }
        for (MaidInfo info : infos) {
            if (info.getEntityId().equals(maidId)) {
                return info;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 状态采集
    // ------------------------------------------------------------------

    /** 采集点名册上全部女仆的当前状态，供 GUI 展示。 */
    public static List<MaidStatus> collectStatus(ServerPlayer owner, ItemStack roster) {
        MinecraftServer server = owner.getServer();
        if (server == null) {
            return Collections.emptyList();
        }
        List<MaidStatus> result = new ArrayList<>();
        String ownerDim = owner.level().dimension().location().toString();

        for (RosterData.Entry entry : RosterData.read(roster)) {
            EntityMaid maid = findLoadedMaid(server, entry.id(), owner);
            if (maid != null) {
                String dim = maid.level().dimension().location().toString();
                MaidStatus.Presence presence = dim.equals(ownerDim)
                        ? MaidStatus.Presence.HERE
                        : MaidStatus.Presence.OTHER_DIMENSION;
                result.add(new MaidStatus(entry.id(),
                        maid.getDisplayName().getString(),
                        presence,
                        dim,
                        maid.blockPosition(),
                        maid.getTask().getUid(),
                        maid.getSchedule(),
                        maid.getHealth(),
                        maid.getMaxHealth(),
                        maid.isHomeModeEnable()));
                continue;
            }

            MaidInfo info = findUnloadedInfo(owner, entry.id());
            if (info != null) {
                result.add(new MaidStatus(entry.id(),
                        info.getName().getString(),
                        MaidStatus.Presence.UNLOADED,
                        info.getDimension(),
                        info.getChunkPos(),
                        TaskManager.getIdleTask().getUid(),
                        MaidSchedule.DAY,
                        0.0F, 0.0F, false));
                continue;
            }

            result.add(new MaidStatus(entry.id(),
                    entry.name(),
                    MaidStatus.Presence.MISSING,
                    "",
                    BlockPos.ZERO,
                    TaskManager.getIdleTask().getUid(),
                    MaidSchedule.DAY,
                    0.0F, 0.0F, false));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 召唤
    // ------------------------------------------------------------------

    /** 一次召唤的结果统计。 */
    public record SummonResult(int teleported, int loadedFromChunk, int crossDimension, int missing) {
    }

    /**
     * 把指定的女仆召唤到玩家身边。
     *
     * @param maidIds 要召唤的女仆 UUID；传入顺序即处理顺序
     */
    public static SummonResult summon(ServerPlayer owner, Collection<UUID> maidIds) {
        MinecraftServer server = owner.getServer();
        if (server == null) {
            return new SummonResult(0, 0, 0, 0);
        }
        int teleported = 0;
        int chunkLoaded = 0;
        int crossDim = 0;
        int missing = 0;
        boolean allowUnloaded = RosterConfig.SUMMON_UNLOADED.get();
        boolean allowCrossDim = RosterConfig.SUMMON_CROSS_DIMENSION.get();

        for (UUID maidId : maidIds) {
            EntityMaid maid = findLoadedMaid(server, maidId, owner);
            boolean wasUnloaded = false;

            if (maid == null && allowUnloaded) {
                maid = forceLoadMaid(server, owner, maidId);
                wasUnloaded = maid != null;
            }
            if (maid == null) {
                missing++;
                continue;
            }

            boolean sameDim = maid.level().dimension().equals(owner.level().dimension());
            if (!sameDim && !allowCrossDim) {
                missing++;
                continue;
            }

            if (teleportToOwner(maid, owner)) {
                teleported++;
                if (wasUnloaded) {
                    chunkLoaded++;
                }
                if (!sameDim) {
                    crossDim++;
                }
            } else {
                missing++;
            }
        }
        return new SummonResult(teleported, chunkLoaded, crossDim, missing);
    }

    /**
     * 强制加载女仆所在区块，把她从未加载状态拽回内存。
     * <p>
     * 这是本模组相对本体铃铛/小号的核心增强：本体遇到未加载女仆只会提示坐标，
     * 这里直接同步加载目标区块让实体回到内存，再执行传送。
     */
    @Nullable
    private static EntityMaid forceLoadMaid(MinecraftServer server, ServerPlayer owner, UUID maidId) {
        MaidInfo info = findUnloadedInfo(owner, maidId);
        if (info == null) {
            return null;
        }
        ServerLevel target = null;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(info.getDimension())) {
                target = level;
                break;
            }
        }
        if (target == null) {
            return null;
        }
        // MaidInfo 里字段名虽叫 chunkPos，实际存的是女仆的 blockPosition
        ChunkPos chunkPos = new ChunkPos(info.getChunkPos());
        ChunkAccess chunk = target.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
        if (chunk == null) {
            return null;
        }
        Entity entity = target.getEntity(maidId);
        if (entity instanceof EntityMaid maid && maid.isAlive() && maid.isOwnedBy(owner)) {
            return maid;
        }
        return null;
    }

    /**
     * 把一名女仆传送到玩家身边。同维度直接 teleportTo，跨维度手动重建实体。
     * <p>
     * 注意：<b>不能</b>用 {@code maid.changeDimension(...)}——TLM 覆写了它，只会在本维度内
     * 随机传送并返回 null，拿它做跨维度召唤会静默失败。
     */
    private static boolean teleportToOwner(EntityMaid maid, ServerPlayer owner) {
        // 解除待命/驻留，否则女仆会立刻走回原点
        maid.setHomeModeEnable(false);
        if (maid.isPassenger()) {
            maid.stopRiding();
        }

        double x = owner.getX() + owner.getRandom().nextInt(3) - 1;
        double y = owner.getY();
        double z = owner.getZ() + owner.getRandom().nextInt(3) - 1;

        if (maid.level().dimension().equals(owner.level().dimension())) {
            maid.teleportTo(x, y, z);
            maid.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 1, true, false));
            return true;
        }

        ServerLevel targetLevel = owner.serverLevel();
        maid.unRide();
        // 原版跨维度流程：在目标世界新建同类型实体，复制存档数据，旧实体标记为已换维度
        EntityMaid moved = EntityMaid.TYPE.create(targetLevel);
        if (moved == null) {
            return false;
        }
        moved.restoreFrom(maid);
        moved.moveTo(x, y, z, maid.getYRot(), maid.getXRot());
        moved.setYHeadRot(maid.getYRot());
        maid.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
        targetLevel.addDuringTeleport(moved);
        moved.setHomeModeEnable(false);
        moved.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 1, true, false));
        return true;
    }

    // ------------------------------------------------------------------
    // 批量设置
    // ------------------------------------------------------------------

    /**
     * 批量设置工作模式与作息。任一参数传 null 表示该项不改。
     *
     * @return 实际生效的女仆数量
     */
    public static int apply(ServerPlayer owner, Collection<UUID> maidIds,
                            @Nullable ResourceLocation taskUid, @Nullable MaidSchedule schedule) {
        MinecraftServer server = owner.getServer();
        if (server == null) {
            return 0;
        }
        IMaidTask task = null;
        if (taskUid != null) {
            task = TaskManager.findTask(taskUid).orElse(null);
            if (task == null) {
                return 0;
            }
        }

        int changed = 0;
        for (UUID maidId : maidIds) {
            EntityMaid maid = findLoadedMaid(server, maidId, owner);
            if (maid == null) {
                continue;
            }
            boolean touched = false;
            if (task != null && task.isEnable(maid)) {
                maid.setTask(task);
                touched = true;
            }
            if (schedule != null) {
                maid.setSchedule(schedule);
                touched = true;
            }
            if (touched) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * 真正给女仆改名：对已加载的女仆调用 {@code setCustomName}。
     * <p>
     * 这是 TLM 本体「命名牌」物品用的同一套机制（{@code EntityMaid} 继承自
     * {@code LivingEntity}，改名就是标准 {@code setCustomName}）。未加载的女仆没法改实体，
     * 由调用方另行更新点名册里的名字快照。
     *
     * @return 实际改到实体的女仆数量
     */
    public static int renameMaid(ServerPlayer owner, Collection<UUID> maidIds, String newName) {
        MinecraftServer server = owner.getServer();
        if (server == null) {
            return 0;
        }
        Component name = Component.literal(newName);
        int changed = 0;
        for (UUID maidId : maidIds) {
            EntityMaid maid = findLoadedMaid(server, maidId, owner);
            if (maid == null) {
                continue;
            }
            maid.setCustomName(name);
            maid.setCustomNameVisible(true);
            changed++;
        }
        return changed;
    }

    /** 把召唤结果转成给玩家看的提示文本。 */
    public static Component describeSummon(SummonResult result) {
        if (result.teleported() == 0) {
            return Component.translatable("message.maid_roster.summon.none");
        }
        if (result.missing() > 0) {
            return Component.translatable("message.maid_roster.summon.partial",
                    result.teleported(), result.missing());
        }
        return Component.translatable("message.maid_roster.summon.success", result.teleported());
    }
}
