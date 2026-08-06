package net.kalloer1.maidroster.client;

import net.kalloer1.maidroster.client.gui.RosterScreen;
import net.kalloer1.maidroster.network.RosterDataS2C;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 客户端侧的收包处理。 */
@OnlyIn(Dist.CLIENT)
public final class ClientPacketHandler {
    private ClientPacketHandler() {
    }

    /**
     * 收到女仆状态：界面没开就打开，已经开着就原地刷新（保留滚动位置）。
     */
    public static void handleRosterData(RosterDataS2C msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof RosterScreen screen) {
            screen.refresh(msg.getStatuses());
        } else {
            mc.setScreen(new RosterScreen(msg.getHand(), msg.getStatuses()));
        }
    }
}
