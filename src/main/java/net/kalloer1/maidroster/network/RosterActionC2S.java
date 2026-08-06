package net.kalloer1.maidroster.network;

import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import net.kalloer1.maidroster.init.ModItems;
import net.kalloer1.maidroster.roster.MaidStatus;
import net.kalloer1.maidroster.roster.RosterData;
import net.kalloer1.maidroster.server.RosterService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 → 服务端：管理界面上的一次操作。
 * <p>
 * 服务端执行完后会回发一份最新的 {@link RosterDataS2C} 用于刷新界面。
 */
public class RosterActionC2S {
    /** 操作类型。 */
    public enum Action {
        /** 设置工作模式与 / 或作息 */
        APPLY,
        /** 召唤指定女仆 */
        SUMMON,
        /** 从名册移除 */
        REMOVE,
        /** 仅刷新数据 */
        REFRESH,
        /** 重命名指定女仆在本点名册里的显示快照名 */
        RENAME
    }

    private final Action action;
    private final InteractionHand hand;
    /** 目标女仆；为空表示"名册上全部"。 */
    private final List<UUID> targets;
    @Nullable
    private final ResourceLocation taskUid;
    @Nullable
    private final MaidSchedule schedule;
    /** 仅在 {@link Action#RENAME} 下有意义；其他操作可为空。 */
    @Nullable
    private final String newName;

    public RosterActionC2S(Action action, InteractionHand hand, List<UUID> targets,
                           @Nullable ResourceLocation taskUid, @Nullable MaidSchedule schedule) {
        this(action, hand, targets, taskUid, schedule, null);
    }

    public RosterActionC2S(Action action, InteractionHand hand, List<UUID> targets,
                           @Nullable ResourceLocation taskUid, @Nullable MaidSchedule schedule,
                           @Nullable String newName) {
        this.action = action;
        this.hand = hand;
        this.targets = targets;
        this.taskUid = taskUid;
        this.schedule = schedule;
        this.newName = newName;
    }

    public static void encode(RosterActionC2S msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
        buf.writeEnum(msg.hand);
        buf.writeVarInt(msg.targets.size());
        for (UUID id : msg.targets) {
            buf.writeUUID(id);
        }
        buf.writeBoolean(msg.taskUid != null);
        if (msg.taskUid != null) {
            buf.writeResourceLocation(msg.taskUid);
        }
        buf.writeBoolean(msg.schedule != null);
        if (msg.schedule != null) {
            buf.writeEnum(msg.schedule);
        }
        // 新名字字段始终写出：null 与空串可区分，便于以后做"清空回退"语义
        buf.writeBoolean(msg.newName != null);
        if (msg.newName != null) {
            buf.writeUtf(msg.newName, 64);
        }
    }

    public static RosterActionC2S decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        InteractionHand hand = buf.readEnum(InteractionHand.class);
        int size = buf.readVarInt();
        List<UUID> targets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            targets.add(buf.readUUID());
        }
        ResourceLocation taskUid = buf.readBoolean() ? buf.readResourceLocation() : null;
        MaidSchedule schedule = buf.readBoolean() ? buf.readEnum(MaidSchedule.class) : null;
        String newName = buf.readBoolean() ? buf.readUtf(64) : null;
        return new RosterActionC2S(action, hand, targets, taskUid, schedule, newName);
    }

    public static void handle(RosterActionC2S msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = player.getItemInHand(msg.hand);
            if (!stack.is(ModItems.MAID_ROSTER.get())) {
                return;
            }
            msg.execute(player, stack);
        });
        context.setPacketHandled(true);
    }

    private void execute(ServerPlayer player, ItemStack stack) {
        Set<UUID> bound = RosterData.readIds(stack);
        // 目标为空 = 对名册全员操作；否则取交集，避免客户端伪造无关 UUID
        Set<UUID> effective = new LinkedHashSet<>();
        if (targets.isEmpty()) {
            effective.addAll(bound);
        } else {
            for (UUID id : targets) {
                if (bound.contains(id)) {
                    effective.add(id);
                }
            }
        }

        switch (action) {
            case APPLY -> {
                int changed = RosterService.apply(player, effective, taskUid, schedule);
                if (changed > 0) {
                    player.sendSystemMessage(Component.translatable("message.maid_roster.apply.done", changed)
                            .withStyle(ChatFormatting.GREEN));
                    player.level().playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(),
                            SoundSource.PLAYERS, 0.6F, 1.4F);
                } else {
                    player.sendSystemMessage(Component.translatable("message.maid_roster.apply.none")
                            .withStyle(ChatFormatting.GRAY));
                }
            }
            case SUMMON -> {
                RosterService.SummonResult result = RosterService.summon(player, effective);
                player.sendSystemMessage(RosterService.describeSummon(result));
                player.level().playSound(null, player.blockPosition(), SoundEvents.BELL_BLOCK,
                        SoundSource.PLAYERS, 1.5F, 1.4F);
            }
            case REMOVE -> {
                for (UUID id : effective) {
                    RosterData.remove(stack, id);
                }
            }
            case REFRESH -> {
                // 什么都不做，下面统一回发最新数据
            }
            case RENAME -> {
                // 只对单个女仆生效
                String target = newName == null ? "" : newName.trim();
                if (target.isEmpty()) {
                    player.sendSystemMessage(Component.translatable("message.maid_roster.rename.empty")
                            .withStyle(ChatFormatting.GRAY));
                } else {
                    // 1) 真改女仆实体名字（标准 setCustomName，与 TLM 命名牌同源）
                    int changed = RosterService.renameMaid(player, effective, target);
                    // 2) 同步更新点名册里的名字快照（含未加载女仆的兜底）
                    for (UUID id : effective) {
                        RosterData.updateName(stack, id, target);
                    }
                    if (changed > 0) {
                        player.sendSystemMessage(Component.translatable("message.maid_roster.rename.done", target)
                                .withStyle(ChatFormatting.GREEN));
                        player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PUT,
                                SoundSource.PLAYERS, 1.0F, 1.2F);
                    } else {
                        // 女仆没加载（只在名册里），也提示改名已记录到名册
                        player.sendSystemMessage(Component.translatable("message.maid_roster.rename.done", target)
                                .withStyle(ChatFormatting.GREEN));
                    }
                }
            }
        }

        List<MaidStatus> statuses = RosterService.collectStatus(player, stack);
        NetworkHandler.sendToPlayer(new RosterDataS2C(hand, statuses), player);
    }
}
