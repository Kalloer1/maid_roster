package net.kalloer1.maidroster;

import net.kalloer1.maidroster.config.RosterConfig;
import net.kalloer1.maidroster.init.ModCreativeTabs;
import net.kalloer1.maidroster.init.ModItems;
import net.kalloer1.maidroster.network.NetworkHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 女仆点名册（Maid Roster）——车万女仆附属模组。
 * <p>
 * 提供「女仆点名册」物品：可绑定任意多名女仆，一键将她们全部集合到身边
 * （支持跨维度与未加载区块），并通过统一管理界面批量设置工作模式与作息。
 */
@Mod(MaidRoster.MOD_ID)
public class MaidRoster {
    public static final String MOD_ID = "maid_roster";

    public MaidRoster() {
        // Forge 通用配置（COMMON 会同步到客户端，服务端也可读）
        ModLoadingContext.get().registerConfig(Type.COMMON, RosterConfig.SPEC);
        // 物品注册
        ModItems.ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        // 创造模式物品栏
        ModCreativeTabs.TABS.register(FMLJavaModLoadingContext.get().getModEventBus());
        // 网络通道（绑定/召唤/统一管理的数据包）
        NetworkHandler.register();
    }
}
