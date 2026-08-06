package net.kalloer1.maidroster.init;

import net.kalloer1.maidroster.MaidRoster;
import net.kalloer1.maidroster.item.ItemMaidRoster;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** 本模组的物品注册表。 */
public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MaidRoster.MOD_ID);

    /** 女仆点名册：批量绑定、一键集合、统一管理。 */
    public static final RegistryObject<Item> MAID_ROSTER =
            ITEMS.register("maid_roster", ItemMaidRoster::new);

    private ModItems() {
    }
}
