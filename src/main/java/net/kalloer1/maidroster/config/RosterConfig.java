package net.kalloer1.maidroster.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/** 点名册的通用配置。 */
public final class RosterConfig {
    public static final ForgeConfigSpec SPEC;
    private static final RosterConfig INSTANCE;

    /** 单本点名册最多能绑定多少名女仆。 */
    public static ForgeConfigSpec.IntValue MAX_BOUND;
    /** 是否允许召唤所在区块未加载的女仆（会短暂强制加载目标区块）。 */
    public static ForgeConfigSpec.BooleanValue SUMMON_UNLOADED;
    /** 是否允许跨维度召唤。 */
    public static ForgeConfigSpec.BooleanValue SUMMON_CROSS_DIMENSION;
    /** 召唤的冷却时间（tick）。 */
    public static ForgeConfigSpec.IntValue SUMMON_COOLDOWN;

    static {
        Pair<RosterConfig, ForgeConfigSpec> pair = new ForgeConfigSpec.Builder().configure(RosterConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private RosterConfig(ForgeConfigSpec.Builder builder) {
        builder.comment("女仆点名册").push("roster");

        MAX_BOUND = builder
                .comment("单本点名册最多可绑定的女仆数量")
                .defineInRange("maxBoundMaids", 32, 1, 256);

        SUMMON_UNLOADED = builder
                .comment("是否允许召唤所在区块未加载的女仆。",
                        "开启后会同步强制加载她所在的区块把人拽回来；关闭则只能召唤已加载的女仆。")
                .define("summonUnloaded", true);

        SUMMON_CROSS_DIMENSION = builder
                .comment("是否允许跨维度召唤女仆。")
                .define("summonCrossDimension", true);

        SUMMON_COOLDOWN = builder
                .comment("一键集合后的冷却时间（tick，20 tick = 1 秒）")
                .defineInRange("summonCooldown", 100, 0, 12000);

        builder.pop();
    }

    public static RosterConfig get() {
        return INSTANCE;
    }
}
