package net.kalloer1.maidroster.client.gui;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.kalloer1.maidroster.client.util.SearchMatcher;
import net.kalloer1.maidroster.network.NetworkHandler;
import net.kalloer1.maidroster.network.RosterActionC2S;
import net.kalloer1.maidroster.roster.MaidStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 女仆点名册统一管理界面。
 *
 * <p>落地 <code>mc-gui-design</code> skill 的图层铁律：</p>
 * <ul>
 *   <li>搜索框用真实的 {@link EditBox} widget（{@code addRenderableWidget}），点击/键入/焦点全由父类路由。</li>
 *   <li>整个 GUI 关闭 depth test，且每次 fill 前强制复位 shader color 并把 alpha 钉死 0xFF，
 *       彻底杜绝"图标/文字穿透浮层"与"面板半透明残留"。</li>
 *   <li>所有实体面板 100% 不透明（OPAQUENESS），不再有任何"透明背景板"让世界或下层元素透出来。</li>
 *   <li>浮层（任务选择 / 重命名）打开时独占整屏：底层列表/按钮/标题全部不渲染，只画不透明幕布 + 浮层本身，从根上杜绝穿透。</li>
 *   <li>搜索词优先走普通子串匹配；若玩家装了 JECh（拼音搜索 JEI），自动获得拼音匹配能力，未装则照常工作。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class RosterScreen extends Screen {

    // ---------- 布局常量 ----------
    private static final int PANEL_W = 296;
    private static final int PANEL_H = 246;
    private static final int ROW_H = 28;
    private static final int VISIBLE_ROWS = 5;
    private static final int SEARCH_W = 120;
    private static final int SEARCH_H = 16;

    // ---------- 统一调色板（全部不透明，杜绝"反人类"的半透明背景板） ----------
    private static final int COLOR_BG_DIM      = 0xC0000000; // 全屏压暗幕布（75% 黑）
    private static final int COLOR_PANEL       = 0xFF242038; // 主面板（不透明深靛）
    private static final int COLOR_PANEL_BDR   = 0xFFE89CCE; // 主面板边框（亮樱粉，清晰描边）
    private static final int COLOR_INNER       = 0xFF2E2748; // 浮层内填充
    private static final int COLOR_LIST_BG     = 0xFF1B1730; // 列表底（不透明，防透底）
    private static final int COLOR_ROW         = 0xFF2A2344; // 列表行（斑马）
    private static final int COLOR_ROW_ALT     = 0xFF241E3C;
    private static final int COLOR_BTN         = 0xFF4A4070; // 按钮
    private static final int COLOR_BTN_HOVER   = 0xFF6F5C9C; // 按钮悬停
    private static final int COLOR_BTN_DIS      = 0xFF332C4A; // 按钮禁用
    private static final int COLOR_INPUT_BG    = 0xFF1A1530; // 输入框底
    private static final int COLOR_INPUT_BDR   = 0xFFE89CCE; // 输入框边框
    private static final int COLOR_FOCUS       = 0xFFFFA6D4; // 搜索/输入聚焦
    private static final int COLOR_TEXT        = 0xFFF4F0FA; // 主文字（近白，高对比）
    private static final int COLOR_SUB         = 0xFFB6AEC6; // 次文字
    private static final int COLOR_NAME        = 0xFFFFE0F6; // 女仆名字（可点击）
    private static final int COLOR_HEADER      = 0xFFFFD9F2; // 标题
    private static final int COLOR_OVERLAY_DIM = 0xFF0A0818; // 浮层幕布（必须不透明，否则背后图标透出来）
    private static final int COLOR_STATUS_HERE = 0xFF6FF07A;
    private static final int COLOR_STATUS_OTH  = 0xFF6FC8FF;
    private static final int COLOR_STATUS_UNL  = 0xFFFFD070;
    private static final int COLOR_STATUS_MIS  = 0xFFFF6B6B;

    // ---------- 数据 ----------
    private final InteractionHand hand;
    private List<MaidStatus> statuses;
    private List<MaidStatus> visibleStatuses = Collections.emptyList();

    // ---------- 布局坐标（init 中算） ----------
    private int left, top;
    private int listTop, listBottom, listLeft, listRight;
    private int searchX, searchY;

    // ---------- 控件 ----------
    private EditBox searchBox;
    private int scroll;

    // 浮层：任务选择（手动绘制 + 手测热区）
    @Nullable private UUID pickerTarget;
    private boolean pickerOpen;
    private List<IMaidTask> pickerTasks = Collections.emptyList();

    // 浮层：重命名。EditBox 仅 addWidget 注册事件，绘制我们自己来
    @Nullable private UUID renamingMaidId;
    @Nullable private EditBox renameBox;
    private int renameX, renameY, renameW;

    public RosterScreen(InteractionHand hand, List<MaidStatus> statuses) {
        super(Component.translatable("gui.maid_roster.title"));
        this.hand = hand;
        this.statuses = new ArrayList<>(statuses);
    }

    /** 服务端回包后原地刷新，保留滚动位置与搜索词。 */
    public void refresh(List<MaidStatus> newStatuses) {
        this.statuses = new ArrayList<>(newStatuses);
        rebuildVisible();
        clampScroll();
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;

        this.listLeft = left + 8;
        this.listRight = left + PANEL_W - 8;
        this.listTop = top + 60;
        this.listBottom = listTop + VISIBLE_ROWS * ROW_H;

        this.searchBox = new EditBox(this.font, 0, 0, SEARCH_W, SEARCH_H,
                Component.translatable("gui.maid_roster.search.hint"));
        this.searchBox.setBordered(false);
        this.searchBox.setMaxLength(60);
        this.searchBox.setHint(Component.translatable("gui.maid_roster.search.hint"));
        this.searchBox.setTextColor(COLOR_TEXT);
        this.searchBox.setResponder(s -> rebuildVisible());
        positionSearchBox();
        this.addRenderableWidget(this.searchBox);

        rebuildVisible();
        clampScroll();
    }

    private void positionSearchBox() {
        this.searchX = left + PANEL_W - 8 - SEARCH_W;
        this.searchY = top + 6;
        this.searchBox.setX(this.searchX + 4);
        this.searchBox.setY(this.searchY + (SEARCH_H - this.font.lineHeight) / 2 - 1);
        this.searchBox.setWidth(SEARCH_W - 4 - 12);
        this.searchBox.setHeight(SEARCH_H);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (this.searchBox != null) this.searchBox.tick();
        if (this.renameBox != null) this.renameBox.tick();
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        RenderSystem.disableDepthTest();

        if (pickerOpen) {
            // 浮层独占整屏：只画“不透明压暗幕布 + 选择面板”。
            // 底层列表/按钮/标题一律不渲染 —— 从根上杜绝任何穿透。
            renderTaskPicker(gg, mouseX, mouseY);
        } else if (renamingMaidId != null && renameBox != null) {
            renderRenamePanel(gg, mouseX, mouseY);
        } else {
            // 1) 自管 chrome（背景 + 面板 + 搜索框底 + 列表底）—— 全部不透明
            renderChrome(gg);

            // 2) 所有 addRenderableWidget 的控件（搜索框）
            for (Renderable r : this.renderables) {
                r.render(gg, mouseX, mouseY, partialTick);
            }

            // 3) 标题 / 清空 / 批量 / 列表 / 底部提示
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.enableBlend();
            renderTitle(gg);
            drawSearchClear(gg, mouseX, mouseY);
            renderBatchBar(gg, mouseX, mouseY);
            renderList(gg, mouseX, mouseY);
            renderHint(gg);
        }

        RenderSystem.enableDepthTest();
    }

    private void renderChrome(GuiGraphics gg) {
        // 1) 整屏幕布
        fillBlend(gg, 0, 0, this.width, this.height, COLOR_BG_DIM);
        // 2) 主面板（边框 + 不透明填充）
        fillSolid(gg, left - 1, top - 1, left + PANEL_W + 1, top + PANEL_H + 1, COLOR_PANEL_BDR);
        fillSolid(gg, left, top, left + PANEL_W, top + PANEL_H, COLOR_PANEL);
        // 3) 搜索框底框
        fillSolid(gg, searchX, searchY, searchX + SEARCH_W, searchY + SEARCH_H, COLOR_INPUT_BDR);
        fillSolid(gg, searchX + 1, searchY + 1, searchX + SEARCH_W - 1, searchY + SEARCH_H - 1,
                this.searchBox.isFocused() ? COLOR_FOCUS : COLOR_INPUT_BG);
        // 4) 列表底（不透明）
        fillSolid(gg, left + 6, listTop - 2, left + PANEL_W - 6, listBottom + 2, COLOR_LIST_BG);
    }

    private void renderTitle(GuiGraphics gg) {
        Component title = Component.translatable("gui.maid_roster.title");
        gg.drawString(this.font, title, left + 8, top + 8, COLOR_HEADER, false);
        gg.drawString(this.font, statuses.size() + " 名",
                left + 8 + this.font.width(title) + 8, top + 8, COLOR_SUB, false);
    }

    private void renderHint(GuiGraphics gg) {
        gg.drawString(this.font, Component.translatable("gui.maid_roster.hint"),
                left + 8, top + PANEL_H - 14, COLOR_SUB, false);
    }

    private void clampScroll() {
        int max = Math.max(0, visibleStatuses.size() - VISIBLE_ROWS);
        this.scroll = Mth.clamp(this.scroll, 0, max);
    }

    /** 根据当前搜索词重新计算可见列表（普通子串 + 可选 JECh 拼音）。 */
    private void rebuildVisible() {
        String q = searchBox == null ? "" : searchBox.getValue().trim();
        if (q.isEmpty()) {
            visibleStatuses = statuses;
            return;
        }
        List<MaidStatus> filtered = new ArrayList<>();
        for (MaidStatus s : statuses) {
            if (SearchMatcher.matches(s.name(), q)) {
                filtered.add(s);
            }
        }
        visibleStatuses = filtered;
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    private void drawSearchClear(GuiGraphics gg, int mouseX, int mouseY) {
        String v = this.searchBox.getValue();
        if (v == null || v.isEmpty()) return;
        int cx = searchX + SEARCH_W - 9;
        int cy = searchY + SEARCH_H / 2;
        boolean hover = inside(mouseX, mouseY, cx - 4, cy - 4, 8, 8);
        gg.drawCenteredString(this.font,
                Component.literal("\u2715").withStyle(hover ? ChatFormatting.WHITE : ChatFormatting.GRAY),
                cx, cy - 3, 0xFFFFFFFF);
    }

    // === 批量栏（自己画、自己处理点击） ===

    private int batchY()      { return top + 28; }
    private int batchModeX()  { return left + 72; }
    private int batchSchedX() { return left + 148; }
    private int batchSummonX(){ return left + 210; }

    private void renderBatchBar(GuiGraphics gg, int mouseX, int mouseY) {
        int y = batchY();
        gg.drawString(this.font, Component.translatable("gui.maid_roster.batch"),
                left + 8, y + 6, COLOR_SUB, false);
        boolean enabled = !pickerOpen && renamingMaidId == null;
        drawButton(gg, batchModeX(),   y, 72, 20,
                Component.translatable("gui.maid_roster.batch.mode"),    mouseX, mouseY, enabled);
        drawButton(gg, batchSchedX(),  y, 58, 20,
                Component.translatable("gui.maid_roster.batch.schedule"), mouseX, mouseY, enabled);
        drawButton(gg, batchSummonX(), y, 78, 20,
                Component.translatable("gui.maid_roster.batch.summon"),   mouseX, mouseY, enabled);
    }

    // === 列表 ===

    private void renderList(GuiGraphics gg, int mouseX, int mouseY) {
        if (visibleStatuses.isEmpty()) {
            String q = searchBox == null ? "" : searchBox.getValue().trim();
            gg.drawCenteredString(this.font,
                    q.isEmpty()
                            ? Component.translatable("gui.maid_roster.empty")
                            : Component.translatable("gui.maid_roster.empty.search", q),
                    left + PANEL_W / 2, listTop + 40, COLOR_SUB);
            return;
        }
        int end = Math.min(visibleStatuses.size(), scroll + VISIBLE_ROWS);
        for (int i = scroll; i < end; i++) {
            renderRow(gg, visibleStatuses.get(i), i,
                    listTop + (i - scroll) * ROW_H, mouseX, mouseY);
        }
        if (visibleStatuses.size() > VISIBLE_ROWS) {
            drawScrollbar(gg);
        }
    }

    private void drawScrollbar(GuiGraphics gg) {
        int trackX = left + PANEL_W - 5;
        int trackH = listBottom - listTop;
        fillSolid(gg, trackX, listTop, trackX + 3, listBottom, 0xFF000000);
        int thumbH = Math.max(12, trackH * VISIBLE_ROWS / visibleStatuses.size());
        int maxScroll = visibleStatuses.size() - VISIBLE_ROWS;
        int thumbY = listTop + (trackH - thumbH) * scroll / Math.max(1, maxScroll);
        fillSolid(gg, trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFFB48CD8);
    }

    private void renderRow(GuiGraphics gg, MaidStatus s, int idx, int y, int mouseX, int mouseY) {
        // 行底色（斑马，不透明）
        fillSolid(gg, listLeft, y, listRight, y + ROW_H - 2,
                idx % 2 == 0 ? COLOR_ROW_ALT : COLOR_ROW);
        // 状态条
        fillSolid(gg, listLeft, y, listLeft + 3, y + ROW_H - 2, statusColor(s));
        // 名字 + 副标
        gg.drawString(this.font, trimToWidth(s.name(), 92), listLeft + 8, y + 4, COLOR_NAME, false);
        gg.drawString(this.font, subtitleOf(s), listLeft + 8, y + 15, COLOR_SUB, false);
        // 模式按钮（带任务图标）
        IMaidTask task = TaskManager.findTask(s.taskUid()).orElse(TaskManager.getIdleTask());
        int modeX = listLeft + 104;
        boolean loaded = s.isLoaded();
        drawButton(gg, modeX, y + 4, 80, 20, Component.empty(), mouseX, mouseY, loaded);
        gg.renderItem(task.getIcon(), modeX + 2, y + 6);
        gg.drawString(this.font, trimToWidth(task.getName().getString(), 56),
                modeX + 21, y + 10, loaded ? COLOR_TEXT : COLOR_SUB, false);
        // 作息
        drawButton(gg, listLeft + 188, y + 4, 32, 20,
                scheduleLabel(s.schedule()), mouseX, mouseY, loaded);
        // 召唤 / 移除
        drawButton(gg, listLeft + 224, y + 4, 20, 20, Component.literal("\u21BB"), mouseX, mouseY);
        drawButton(gg, listLeft + 248, y + 4, 20, 20,
                Component.literal("\u2716").withStyle(ChatFormatting.RED), mouseX, mouseY);
    }

    // === 浮层：任务选择（手动画 + 手测热区，确保图层在最上） ===

    private void renderTaskPicker(GuiGraphics gg, int mouseX, int mouseY) {
        // 浮层幕布：不透明全屏，彻底盖住背后列表/图标
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        fillSolid(gg, 0, 0, this.width, this.height, COLOR_OVERLAY_DIM);

        List<IMaidTask> tasks = pickerTasks;
        int cols = 6;
        int rows = Math.max(1, (tasks.size() + cols - 1) / cols);
        int cell = 28;
        int pad = 16;
        int pw = cols * cell + pad;
        int ph = rows * cell + 36;
        int px = (this.width - pw) / 2;
        int py = (this.height - ph) / 2;

        fillSolid(gg, px - 1, py - 1, px + pw + 1, py + ph + 1, COLOR_PANEL_BDR);
        fillSolid(gg, px, py, px + pw, py + ph, COLOR_INNER);
        gg.drawString(this.font, Component.translatable("gui.maid_roster.picker.title"),
                px + 8, py + 6, COLOR_HEADER, false);

        int hoveredIdx = -1;
        for (int i = 0; i < tasks.size(); i++) {
            int cx = px + 8 + (i % cols) * cell;
            int cy = py + 26 + (i / cols) * cell;
            boolean hover = inside(mouseX, mouseY, cx, cy, cell - 2, cell - 2);
            if (hover) hoveredIdx = i;
            fillSolid(gg, cx, cy, cx + cell - 2, cy + cell - 2,
                    hover ? COLOR_BTN_HOVER : COLOR_INNER);
            gg.renderItem(tasks.get(i).getIcon(), cx + 4, cy + 4);
        }
        if (hoveredIdx >= 0) {
            int cx = px + 8 + (hoveredIdx % cols) * cell;
            int cy = py + 26 + (hoveredIdx / cols) * cell;
            gg.renderTooltip(this.font, tasks.get(hoveredIdx).getName(), mouseX, mouseY);
        }
    }

    // === 浮层：重命名（自己画面板 + EditBox 用 addWidget 注册事件） ===

    private void renderRenamePanel(GuiGraphics gg, int mouseX, int mouseY) {
        // 浮层幕布：不透明全屏
        RenderSystem.disableDepthTest();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        fillSolid(gg, 0, 0, this.width, this.height, COLOR_OVERLAY_DIM);

        int pw = 240, ph = 90;
        int px = (this.width - pw) / 2;
        int py = (this.height - ph) / 2;
        fillSolid(gg, px - 1, py - 1, px + pw + 1, py + ph + 1, COLOR_PANEL_BDR);
        fillSolid(gg, px, py, px + pw, py + ph, COLOR_INNER);

        MaidStatus target = findStatus(renamingMaidId);
        Component title = Component.translatable("gui.maid_roster.rename.title",
                target == null ? "?" : target.name());
        gg.drawString(this.font, title, px + 10, py + 8, COLOR_HEADER, true);

        int inputH = 20;
        renameX = px + 10;
        renameY = py + 28;
        renameW = pw - 20;
        fillSolid(gg, renameX - 1, renameY - 1, renameX + renameW + 1, renameY + inputH + 1, COLOR_INPUT_BDR);
        fillSolid(gg, renameX, renameY, renameX + renameW, renameY + inputH,
                this.renameBox.isFocused() ? COLOR_FOCUS : COLOR_INPUT_BG);
        this.renameBox.setX(renameX + 3);
        this.renameBox.setY(renameY + (inputH - this.font.lineHeight) / 2 - 1);
        this.renameBox.setWidth(renameW - 6);
        this.renameBox.setHeight(inputH);
        this.renameBox.render(gg, mouseX, mouseY, 0);

        int btnY = py + 60;
        drawButton(gg, px + 10, btnY, 70, 20,
                Component.translatable("gui.maid_roster.rename.confirm"), mouseX, mouseY);
        drawButton(gg, px + pw - 80, btnY, 70, 20,
                Component.translatable("gui.maid_roster.rename.cancel"), mouseX, mouseY);
    }

    @Nullable
    private MaidStatus findStatus(@Nullable UUID id) {
        if (id == null) return null;
        for (MaidStatus s : statuses) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);

        // 浮层最先处理
        if (pickerOpen) return handlePickerClick(mouseX, mouseY);
        if (renamingMaidId != null && renameBox != null) return handleRenameClick(mouseX, mouseY);

        // 搜索框 ✕ 清空热区
        String v = this.searchBox.getValue();
        if (v != null && !v.isEmpty()) {
            int cx = searchX + SEARCH_W - 9;
            int cy = searchY + SEARCH_H / 2;
            if (inside(mouseX, mouseY, cx - 4, cy - 4, 8, 8)) {
                this.searchBox.setValue("");
                return true;
            }
        }

        // 批量栏
        int y = batchY();
        if (inside(mouseX, mouseY, batchModeX(), y, 72, 20)) {
            openTaskPicker(null);
            return true;
        }
        if (inside(mouseX, mouseY, batchSchedX(), y, 58, 20)) {
            send(RosterActionC2S.Action.APPLY, Collections.emptyList(), null,
                    nextSchedule(firstLoadedSchedule()));
            return true;
        }
        if (inside(mouseX, mouseY, batchSummonX(), y, 78, 20)) {
            send(RosterActionC2S.Action.SUMMON, Collections.emptyList(), null, null);
            return true;
        }

        // 列表行内控件
        if (handleRowClick(mouseX, mouseY)) return true;

        // 兜底：交给 super，让搜索框（addRenderableWidget）等真正注册的 widget 仍能拿到点击
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleRowClick(double mouseX, double mouseY) {
        int end = Math.min(visibleStatuses.size(), scroll + VISIBLE_ROWS);
        for (int i = scroll; i < end; i++) {
            int y = listTop + (i - scroll) * ROW_H;
            MaidStatus s = visibleStatuses.get(i);
            List<UUID> single = Collections.singletonList(s.id());
            if (inside(mouseX, mouseY, listLeft + 8,   y + 4, 92, 12)) { openRename(s); return true; }
            if (inside(mouseX, mouseY, listLeft + 104, y + 4, 80, 20)) {
                if (s.isLoaded()) openTaskPicker(s.id());
                return true;
            }
            if (inside(mouseX, mouseY, listLeft + 188, y + 4, 32, 20)) {
                if (s.isLoaded())
                    send(RosterActionC2S.Action.APPLY, single, null, nextSchedule(s.schedule()));
                return true;
            }
            if (inside(mouseX, mouseY, listLeft + 224, y + 4, 20, 20)) {
                send(RosterActionC2S.Action.SUMMON, single, null, null);
                return true;
            }
            if (inside(mouseX, mouseY, listLeft + 248, y + 4, 20, 20)) {
                send(RosterActionC2S.Action.REMOVE, single, null, null);
                return true;
            }
        }
        return false;
    }

    private boolean handlePickerClick(double mouseX, double mouseY) {
        List<IMaidTask> tasks = pickerTasks;
        int cols = 6, cell = 28, pad = 16;
        int pw = cols * cell + pad;
        int ph = Math.max(1, (tasks.size() + cols - 1) / cols) * cell + 36;
        int px = (this.width - pw) / 2;
        int py = (this.height - ph) / 2;
        for (int i = 0; i < tasks.size(); i++) {
            int cx = px + 8 + (i % cols) * cell;
            int cy = py + 26 + (i / cols) * cell;
            if (inside(mouseX, mouseY, cx, cy, cell - 2, cell - 2)) {
                ResourceLocation uid = tasks.get(i).getUid();
                List<UUID> targets = pickerTarget == null
                        ? Collections.emptyList()
                        : Collections.singletonList(pickerTarget);
                send(RosterActionC2S.Action.APPLY, targets, uid, null);
                closeTaskPicker();
                return true;
            }
        }
        if (!inside(mouseX, mouseY, px, py, pw, ph)) {
            closeTaskPicker();
            return true;
        }
        return true;
    }

    private boolean handleRenameClick(double mouseX, double mouseY) {
        int pw = 240, ph = 90;
        int px = (this.width - pw) / 2;
        int py = (this.height - ph) / 2;
        int btnY = py + 60;
        if (inside(mouseX, mouseY, px + 10,     btnY, 70, 20)) { submitRename(); return true; }
        if (inside(mouseX, mouseY, px + pw - 80, btnY, 70, 20)) { closeRename();  return true; }
        if (inside(mouseX, mouseY, renameX - 1, renameY - 1, renameW + 2, 22)) {
            this.renameBox.setFocused(true);
            this.renameBox.mouseClicked(mouseX, mouseY, 0);
            return true;
        }
        if (!inside(mouseX, mouseY, px, py, pw, ph)) { closeRename(); return true; }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (pickerOpen || renamingMaidId != null) return true;
        if (visibleStatuses.size() > VISIBLE_ROWS) {
            scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0,
                    visibleStatuses.size() - VISIBLE_ROWS);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean isCloseKey = (keyCode == 256)
                || (this.minecraft != null && this.minecraft.options.keyInventory.matches(keyCode, scanCode));
        if (isCloseKey) {
            if (pickerOpen) { closeTaskPicker(); return true; }
            if (renamingMaidId != null) { closeRename(); return true; }
            // 没有浮层时直接关闭整个 GUI（ESC 本来父类也会关，E 必须自己处理）
            this.minecraft.setScreen(null);
            return true;
        }
        if (renamingMaidId != null && renameBox != null) {
            if (keyCode == 257 || keyCode == 335) { submitRename(); return true; }
            if (this.renameBox.keyPressed(keyCode, scanCode, modifiers)) return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (renamingMaidId != null && renameBox != null) {
            return this.renameBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ==================================================================
    // 浮层状态
    // ==================================================================

    private void openTaskPicker(@Nullable UUID target) {
        this.pickerTarget = target;
        this.pickerOpen = true;
        this.pickerTasks = new ArrayList<>(TaskManager.getTaskIndex());
    }

    private void closeTaskPicker() {
        this.pickerOpen = false;
        this.pickerTarget = null;
    }

    private void openRename(MaidStatus s) {
        this.renamingMaidId = s.id();
        this.renameBox = new EditBox(this.font, 0, 0, 0, 0,
                Component.translatable("gui.maid_roster.rename.placeholder"));
        this.renameBox.setBordered(false);
        this.renameBox.setMaxLength(32);
        this.renameBox.setHint(Component.translatable("gui.maid_roster.rename.placeholder"));
        this.renameBox.setTextColor(COLOR_TEXT);
        this.renameBox.setValue(s.name() == null ? "" : s.name());
        this.renameBox.setFocused(true);
        // 首次开浮层即"全选" —— 用户敲任何键直接替换原名，而不是追加
        this.renameBox.moveCursorToEnd();
        this.renameBox.setHighlightPos(0);
    }

    private void closeRename() {
        this.renamingMaidId = null;
        if (this.renameBox != null) {
            this.renameBox.setFocused(false);
        }
        this.renameBox = null;
    }

    private void submitRename() {
        if (renamingMaidId == null || renameBox == null) return;
        String value = renameBox.getValue();
        if (value == null || value.trim().isEmpty()) return;
        send(RosterActionC2S.Action.RENAME, Collections.singletonList(renamingMaidId),
                null, null, value.trim());
        closeRename();
    }

    private void send(RosterActionC2S.Action action, List<UUID> targets,
                      @Nullable ResourceLocation taskUid, @Nullable MaidSchedule schedule) {
        NetworkHandler.sendToServer(new RosterActionC2S(action, hand, targets, taskUid, schedule));
    }

    private void send(RosterActionC2S.Action action, List<UUID> targets,
                      @Nullable ResourceLocation taskUid, @Nullable MaidSchedule schedule, String newName) {
        NetworkHandler.sendToServer(new RosterActionC2S(action, hand, targets, taskUid, schedule, newName));
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    /** 不透明矩形：复位 shader color + 钉死 alpha 0xFF + 临时禁 blend，杜绝任何透底/残留。 */
    private static void fillSolid(GuiGraphics gg, int x1, int y1, int x2, int y2, int color) {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        gg.fill(x1, y1, x2, y2, (color & 0x00FFFFFF) | 0xFF000000);
        RenderSystem.enableBlend();
    }

    /** 半透明矩形（仅用于浮层压暗幕布等有意的半透明层），同样复位 shader color。 */
    private static void fillBlend(GuiGraphics gg, int x1, int y1, int x2, int y2, int color) {
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableBlend();
        gg.fill(x1, y1, x2, y2, color);
    }

    private void drawButton(GuiGraphics gg, int x, int y, int w, int h,
                            Component label, int mouseX, int mouseY) {
        drawButton(gg, x, y, w, h, label, mouseX, mouseY, true);
    }

    private void drawButton(GuiGraphics gg, int x, int y, int w, int h,
                            Component label, int mouseX, int mouseY, boolean enabled) {
        boolean hover = enabled && inside(mouseX, mouseY, x, y, w, h);
        fillSolid(gg, x, y, x + w, y + h,
                hover ? COLOR_BTN_HOVER : (enabled ? COLOR_BTN : COLOR_BTN_DIS));
        fillBlend(gg, x, y, x + w, y + 1, enabled ? 0x60FFFFFF : 0x20FFFFFF);
        if (!label.getString().isEmpty()) {
            gg.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2,
                    enabled ? COLOR_TEXT : COLOR_SUB);
        }
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String trimToWidth(String text, int maxW) {
        if (text == null) return "";
        if (this.font.width(text) <= maxW) return text;
        return this.font.plainSubstrByWidth(text, maxW - this.font.width("...")) + "...";
    }

    private static int statusColor(MaidStatus s) {
        return switch (s.presence()) {
            case HERE -> COLOR_STATUS_HERE;
            case OTHER_DIMENSION -> COLOR_STATUS_OTH;
            case UNLOADED -> COLOR_STATUS_UNL;
            case MISSING -> COLOR_STATUS_MIS;
        };
    }

    private Component subtitleOf(MaidStatus s) {
        return switch (s.presence()) {
            case HERE -> Component.translatable("gui.maid_roster.state.here",
                    Math.round(s.health()), Math.round(s.maxHealth()));
            case OTHER_DIMENSION -> Component.translatable("gui.maid_roster.state.other_dim", shortDim(s.dimension()));
            case UNLOADED -> Component.translatable("gui.maid_roster.state.unloaded",
                    s.pos().getX(), s.pos().getZ());
            case MISSING -> Component.translatable("gui.maid_roster.state.missing");
        };
    }

    private static String shortDim(String d) {
        if (d == null) return "?";
        int i = d.indexOf(':');
        return i >= 0 ? d.substring(i + 1) : d;
    }

    private static Component scheduleLabel(MaidSchedule s) {
        return switch (s) {
            case DAY -> Component.translatable("gui.maid_roster.schedule.day");
            case NIGHT -> Component.translatable("gui.maid_roster.schedule.night");
            case ALL -> Component.translatable("gui.maid_roster.schedule.all");
        };
    }

    private static MaidSchedule nextSchedule(MaidSchedule c) {
        return switch (c) {
            case DAY -> MaidSchedule.NIGHT;
            case NIGHT -> MaidSchedule.ALL;
            case ALL -> MaidSchedule.DAY;
        };
    }

    private MaidSchedule firstLoadedSchedule() {
        for (MaidStatus s : statuses) {
            if (s.isLoaded()) return s.schedule();
        }
        return MaidSchedule.DAY;
    }
}
