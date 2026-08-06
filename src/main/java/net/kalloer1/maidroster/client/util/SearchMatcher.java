package net.kalloer1.maidroster.client.util;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 搜索匹配器：普通子串 + 可选 JECh 拼音。
 *
 * <p>实现逻辑和 JEI 搜索框一致：</p>
 * <ul>
 *   <li>如果安装了「拼音搜索 JEI」(JustEnoughCharacters, modid=jecharacters)，
 *       调用它公开的 {@code me.towdium.jecharacters.utils.Match.contains(String, CharSequence)}
 *       做拼音匹配（全拼 / 声母 / 混拼由 JECh 自己处理）。</li>
 *   <li>如果没安装 JECh，则退化为普通子串搜索，UI 完全可用。</li>
 *   <li>本 mod jar 不内置任何拼音库，避免臃肿。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public final class SearchMatcher {

    private static final boolean JECh_PRESENT;
    @Nullable
    private static final Method JECh_CONTAINS;

    static {
        boolean present = false;
        Method m = null;
        try {
            if (ModList.get().isLoaded("jecharacters")) {
                Class<?> matchClass = Class.forName("me.towdium.jecharacters.utils.Match");
                m = matchClass.getMethod("contains", String.class, CharSequence.class);
                present = true;
            }
        } catch (Throwable ignored) {
            // JECh 不存在或 API 不兼容：静默降级
        }
        JECh_PRESENT = present;
        JECh_CONTAINS = m;
    }

    private SearchMatcher() {}

    /**
     * 判断 {@code text} 是否匹配 {@code query}。
     *
     * @param text  待匹配文本（女仆名字）
     * @param query 搜索词
     * @return true 当 text 包含 query，或 query 能拼音匹配 text
     */
    public static boolean matches(String text, String query) {
        if (text == null || query == null) return false;
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return true;

        // 1) 普通子串（英文、数字、已输入的中文等）
        if (text.toLowerCase(Locale.ROOT).contains(q)) return true;

        // 2) JECh 拼音匹配（可选，未安装时跳过）
        if (JECh_PRESENT && JECh_CONTAINS != null) {
            try {
                return Boolean.TRUE.equals(JECh_CONTAINS.invoke(null, text, q));
            } catch (Throwable ignored) {
                // 反射异常时安全降级
            }
        }
        return false;
    }
}
