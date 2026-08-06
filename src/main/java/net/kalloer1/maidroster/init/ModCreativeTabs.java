package net.kalloer1.maidroster.init;

import net.kalloer1.maidroster.MaidRoster;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** 本模组的创造模式物品栏。 */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MaidRoster.MOD_ID);

    /** 主物品栏：放入女仆点名册。 */
    public static final RegistryObject<CreativeModeTab> MAIN_TAB = TABS.register("main", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("item_group.maid_roster.main"))
                    .icon(() -> ModItems.MAID_ROSTER.get().getDefaultInstance())
                    .displayItems((params, output) -> output.accept(ModItems.MAID_ROSTER.get()))
                    .build());

    private ModCreativeTabs() {
    }
}
