package net.kalloer1.maidroster.roster;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 一名被绑定女仆的实时状态快照，由服务端采集后下发给客户端 GUI。
 */
public record MaidStatus(UUID id,
                         String name,
                         Presence presence,
                         String dimension,
                         BlockPos pos,
                         ResourceLocation taskUid,
                         MaidSchedule schedule,
                         float health,
                         float maxHealth,
                         boolean homeMode) {

    /** 女仆当前的可达状态。 */
    public enum Presence {
        /** 实体已加载，就在玩家所在维度。 */
        HERE,
        /** 实体已加载，但在别的维度。 */
        OTHER_DIMENSION,
        /** 所在区块未加载，但世界数据里还有位置记录。 */
        UNLOADED,
        /** 查无此人——可能已阵亡或被移除。 */
        MISSING
    }

    /** 是否能读到有效的模式/作息（只有实体已加载时才能）。 */
    public boolean isLoaded() {
        return presence == Presence.HERE || presence == Presence.OTHER_DIMENSION;
    }

    public static void encode(MaidStatus status, FriendlyByteBuf buf) {
        buf.writeUUID(status.id());
        buf.writeUtf(status.name());
        buf.writeEnum(status.presence());
        buf.writeUtf(status.dimension());
        buf.writeBlockPos(status.pos());
        buf.writeResourceLocation(status.taskUid());
        buf.writeEnum(status.schedule());
        buf.writeFloat(status.health());
        buf.writeFloat(status.maxHealth());
        buf.writeBoolean(status.homeMode());
    }

    public static MaidStatus decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        Presence presence = buf.readEnum(Presence.class);
        String dimension = buf.readUtf();
        BlockPos pos = buf.readBlockPos();
        ResourceLocation taskUid = buf.readResourceLocation();
        MaidSchedule schedule = buf.readEnum(MaidSchedule.class);
        float health = buf.readFloat();
        float maxHealth = buf.readFloat();
        boolean homeMode = buf.readBoolean();
        return new MaidStatus(id, name, presence, dimension, pos, taskUid, schedule, health, maxHealth, homeMode);
    }
}
