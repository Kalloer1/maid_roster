package net.kalloer1.maidroster.roster;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 点名册的持久化数据：一个物品上绑定的女仆名单。
 * <p>
 * 数据结构（存在 ItemStack 的 NBT 上）：
 * <pre>
 * MaidRoster: [
 *   { Id: UUID, Name: "咲夜" },
 *   ...
 * ]
 * </pre>
 * 只存 UUID 和一个用于显示的名字快照——女仆的实时状态（工作模式、作息、位置）
 * 一律在服务端按需查询，避免物品 NBT 变成一份会过期的脏缓存。
 */
public final class RosterData {
    public static final String ROSTER_TAG = "MaidRoster";
    private static final String ENTRY_ID = "Id";
    private static final String ENTRY_NAME = "Name";

    /** 单条绑定记录。 */
    public record Entry(UUID id, String name) {
    }

    private RosterData() {
    }

    /** 读取名单；物品未绑定过任何女仆时返回空列表。 */
    public static List<Entry> read(ItemStack stack) {
        List<Entry> result = new ArrayList<>();
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROSTER_TAG, Tag.TAG_LIST)) {
            return result;
        }
        ListTag listTag = tag.getList(ROSTER_TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag entryTag = listTag.getCompound(i);
            if (!entryTag.hasUUID(ENTRY_ID)) {
                continue;
            }
            result.add(new Entry(entryTag.getUUID(ENTRY_ID), entryTag.getString(ENTRY_NAME)));
        }
        return result;
    }

    /** 只读取 UUID 集合，保持名单顺序。 */
    public static Set<UUID> readIds(ItemStack stack) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Entry entry : read(stack)) {
            ids.add(entry.id());
        }
        return ids;
    }

    /** 覆盖写入整份名单。 */
    public static void write(ItemStack stack, List<Entry> entries) {
        if (entries.isEmpty()) {
            CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove(ROSTER_TAG);
            }
            return;
        }
        ListTag listTag = new ListTag();
        for (Entry entry : entries) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID(ENTRY_ID, entry.id());
            entryTag.putString(ENTRY_NAME, entry.name());
            listTag.add(entryTag);
        }
        stack.getOrCreateTag().put(ROSTER_TAG, listTag);
    }

    /**
     * 加入一名女仆。若已在名单中则只刷新其显示名。
     *
     * @return true 表示是新增，false 表示原本就在名单里
     */
    public static boolean add(ItemStack stack, UUID id, String name) {
        List<Entry> entries = read(stack);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                entries.set(i, new Entry(id, name));
                write(stack, entries);
                return false;
            }
        }
        entries.add(new Entry(id, name));
        write(stack, entries);
        return true;
    }

    /**
     * 从名单中移除一名女仆。
     *
     * @return true 表示确实移除了
     */
    public static boolean remove(ItemStack stack, UUID id) {
        List<Entry> entries = read(stack);
        boolean removed = entries.removeIf(entry -> entry.id().equals(id));
        if (removed) {
            write(stack, entries);
        }
        return removed;
    }

    public static boolean contains(ItemStack stack, UUID id) {
        for (Entry entry : read(stack)) {
            if (entry.id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public static int size(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROSTER_TAG, Tag.TAG_LIST)) {
            return 0;
        }
        return tag.getList(ROSTER_TAG, Tag.TAG_COMPOUND).size();
    }

    /** 查名字，找不到返回 null。 */
    @Nullable
    public static String findName(ItemStack stack, UUID id) {
        for (Entry entry : read(stack)) {
            if (entry.id().equals(id)) {
                return entry.name();
            }
        }
        return null;
    }

    /**
     * 改写点名册中某条记录的「显示名」快照。
     * <p>
     * 这只影响本模组内 GUI / 物品 tooltip 的展示用名字，不会去改原版 CustomName 标签，
     * 也不会因此影响女仆在游戏里的实际显示。
     *
     * @return 是否真的改动了某条记录
     */
    public static boolean updateName(ItemStack stack, UUID id, String newName) {
        List<Entry> entries = read(stack);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                Entry old = entries.get(i);
                if (old.name().equals(newName)) {
                    return false;
                }
                entries.set(i, new Entry(id, newName));
                write(stack, entries);
                return true;
            }
        }
        return false;
    }
}
