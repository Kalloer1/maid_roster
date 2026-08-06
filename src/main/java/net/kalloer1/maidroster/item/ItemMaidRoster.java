package net.kalloer1.maidroster.item;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.kalloer1.maidroster.config.RosterConfig;
import net.kalloer1.maidroster.network.NetworkHandler;
import net.kalloer1.maidroster.network.RosterDataS2C;
import net.kalloer1.maidroster.roster.MaidStatus;
import net.kalloer1.maidroster.roster.RosterData;
import net.kalloer1.maidroster.server.RosterService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 女仆点名册。
 * <p>
 * 交互：
 * <ul>
 *   <li>右键女仆 —— 加入 / 移出名册（切换）</li>
 *   <li>右键（空手方向） —— 打开统一管理界面</li>
 *   <li>潜行 + 右键 —— 一键集合，把名册上所有女仆传送到身边</li>
 * </ul>
 */
public class ItemMaidRoster extends Item {
    public ItemMaidRoster() {
        super(new Properties().stacksTo(1));
    }

    // ------------------------------------------------------------------
    // 右键女仆：绑定 / 解绑
    // ------------------------------------------------------------------

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !(target instanceof EntityMaid maid)) {
            return super.interactLivingEntity(stack, player, target, hand);
        }
        if (!maid.isOwnedBy(player)) {
            if (!player.level().isClientSide) {
                player.sendSystemMessage(Component.translatable("message.maid_roster.bind.not_owner")
                        .withStyle(ChatFormatting.RED));
            }
            return InteractionResult.SUCCESS;
        }

        if (player.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        UUID maidId = maid.getUUID();
        String maidName = maid.getDisplayName().getString();

        if (RosterData.contains(stack, maidId)) {
            RosterData.remove(stack, maidId);
            player.sendSystemMessage(Component.translatable("message.maid_roster.bind.removed", maidName)
                    .withStyle(ChatFormatting.YELLOW));
            player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN,
                    SoundSource.PLAYERS, 1.0F, 0.8F);
            return InteractionResult.SUCCESS;
        }

        int max = RosterConfig.MAX_BOUND.get();
        if (RosterData.size(stack) >= max) {
            player.sendSystemMessage(Component.translatable("message.maid_roster.bind.full", max)
                    .withStyle(ChatFormatting.RED));
            return InteractionResult.SUCCESS;
        }

        RosterData.add(stack, maidId, maidName);
        player.sendSystemMessage(Component.translatable("message.maid_roster.bind.added",
                maidName, RosterData.size(stack), max).withStyle(ChatFormatting.GREEN));
        player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PUT,
                SoundSource.PLAYERS, 1.0F, 1.2F);
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------
    // 右键空气：开界面 / 潜行右键：一键集合
    // ------------------------------------------------------------------

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(stack);
        }

        if (RosterData.size(stack) == 0) {
            player.sendSystemMessage(Component.translatable("message.maid_roster.empty")
                    .withStyle(ChatFormatting.GRAY));
            return InteractionResultHolder.success(stack);
        }

        if (player.isShiftKeyDown()) {
            summonAll(serverPlayer, stack, hand);
        } else {
            openManageScreen(serverPlayer, stack, hand);
        }
        return InteractionResultHolder.success(stack);
    }

    /** 一键集合：把名册上所有女仆传送过来。 */
    private void summonAll(ServerPlayer player, ItemStack stack, InteractionHand hand) {
        Set<UUID> ids = RosterData.readIds(stack);
        RosterService.SummonResult result = RosterService.summon(player, ids);

        player.sendSystemMessage(RosterService.describeSummon(result));
        if (result.crossDimension() > 0) {
            player.sendSystemMessage(Component.translatable("message.maid_roster.summon.cross_dimension",
                    result.crossDimension()).withStyle(ChatFormatting.AQUA));
        }
        if (result.loadedFromChunk() > 0) {
            player.sendSystemMessage(Component.translatable("message.maid_roster.summon.chunk_loaded",
                    result.loadedFromChunk()).withStyle(ChatFormatting.DARK_AQUA));
        }

        player.level().playSound(null, player.blockPosition(), SoundEvents.BELL_BLOCK,
                SoundSource.PLAYERS, 2.0F, 1.4F);
        int cooldown = RosterConfig.SUMMON_COOLDOWN.get();
        if (cooldown > 0) {
            player.getCooldowns().addCooldown(this, cooldown);
        }
    }

    /** 采集女仆状态并让客户端打开管理界面。 */
    private void openManageScreen(ServerPlayer player, ItemStack stack, InteractionHand hand) {
        List<MaidStatus> statuses = RosterService.collectStatus(player, stack);
        NetworkHandler.sendToPlayer(new RosterDataS2C(hand, statuses), player);
    }

    // ------------------------------------------------------------------
    // 展示
    // ------------------------------------------------------------------

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        int count = RosterData.size(stack);
        int max = RosterConfig.MAX_BOUND.get();
        if (count > 0) {
            tooltip.add(Component.translatable("tooltips.maid_roster.count", count, max)
                    .withStyle(ChatFormatting.GOLD));
            List<RosterData.Entry> entries = RosterData.read(stack);
            int preview = Math.min(entries.size(), 5);
            for (int i = 0; i < preview; i++) {
                tooltip.add(Component.literal(" - " + entries.get(i).name())
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            if (entries.size() > preview) {
                tooltip.add(Component.translatable("tooltips.maid_roster.more", entries.size() - preview)
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
            tooltip.add(Component.empty());
        }
        tooltip.add(Component.translatable("tooltips.maid_roster.desc.bind").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltips.maid_roster.desc.manage").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltips.maid_roster.desc.summon").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return RosterData.size(stack) > 0;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int max = Math.max(1, RosterConfig.MAX_BOUND.get());
        return Math.round(13.0F * Math.min(1.0F, (float) RosterData.size(stack) / max));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0xFFB4D8;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return RosterData.size(stack) > 0;
    }
}
