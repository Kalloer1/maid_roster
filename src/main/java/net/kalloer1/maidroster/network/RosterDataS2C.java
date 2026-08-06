package net.kalloer1.maidroster.network;

import net.kalloer1.maidroster.client.ClientPacketHandler;
import net.kalloer1.maidroster.roster.MaidStatus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 → 客户端：下发点名册上所有女仆的当前状态，用于打开或刷新管理界面。
 */
public class RosterDataS2C {
    private final InteractionHand hand;
    private final List<MaidStatus> statuses;

    public RosterDataS2C(InteractionHand hand, List<MaidStatus> statuses) {
        this.hand = hand;
        this.statuses = statuses;
    }

    public InteractionHand getHand() {
        return hand;
    }

    public List<MaidStatus> getStatuses() {
        return statuses;
    }

    public static void encode(RosterDataS2C msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.hand);
        buf.writeVarInt(msg.statuses.size());
        for (MaidStatus status : msg.statuses) {
            MaidStatus.encode(status, buf);
        }
    }

    public static RosterDataS2C decode(FriendlyByteBuf buf) {
        InteractionHand hand = buf.readEnum(InteractionHand.class);
        int size = buf.readVarInt();
        List<MaidStatus> statuses = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            statuses.add(MaidStatus.decode(buf));
        }
        return new RosterDataS2C(hand, statuses);
    }

    public static void handle(RosterDataS2C msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPacketHandler.handleRosterData(msg)));
        context.setPacketHandled(true);
    }
}
