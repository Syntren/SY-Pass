package com.syntren.sypass.gui;

import com.syntren.sypass.config.SYPassConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.List;

public class SYPassToast implements Toast {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("toast/advancement");
    private final Component title;
    private final Component description;
    private final ItemStack icon;

    private List<FormattedCharSequence> descriptionLines = null;
    private int width = 160;

    public SYPassToast(Component title, Component description, ItemStack icon) {
        this.title = title;
        this.description = description;
        this.icon = (icon != null && !icon.isEmpty()) ? icon : new ItemStack(Items.TRIPWIRE_HOOK);
    }

    public static void show(Component title, Component description) {
        show(title, description, new ItemStack(Items.TRIPWIRE_HOOK));
    }

    public static void show(Component title, Component description, ItemStack icon) {
        if (!SYPassConfig.isToastsEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client != null && client.getToasts() != null) {
            client.execute(() -> client.getToasts().addToast(new SYPassToast(title, description, icon)));
        }
    }

    private void calculateSize(Font font) {
        if (descriptionLines != null) return;

        int maxWrapWidth = 190;
        if (description != null) {
            List<FormattedCharSequence> wrapped = font.split(description, maxWrapWidth);
            if (wrapped.size() > 2) {
                this.descriptionLines = wrapped.subList(0, 2);
            } else {
                this.descriptionLines = wrapped;
            }
        } else {
            this.descriptionLines = Collections.emptyList();
        }

        int maxTextWidth = font.width(title);
        for (FormattedCharSequence line : descriptionLines) {
            maxTextWidth = Math.max(maxTextWidth, font.width(line));
        }

        this.width = Math.max(160, Math.min(235, maxTextWidth + 38));
    }

    @Override
    public int width() {
        return this.width;
    }

    @Override
    public int height() {
        if (descriptionLines != null && descriptionLines.size() > 1) {
            return 32 + (descriptionLines.size() - 1) * 11;
        }
        return 32;
    }

    @Override
    public Visibility render(GuiGraphics guiGraphics, ToastComponent toastComponent, long timeSinceLastVisible) {
        Font font = toastComponent.getMinecraft().font;
        calculateSize(font);

        int currentWidth = width();
        int currentHeight = height();

        if (currentWidth <= 160 && (descriptionLines == null || descriptionLines.size() <= 1)) {
            guiGraphics.blitSprite(TEXTURE, 0, 0, currentWidth, currentHeight);
        } else {
            drawSlicedBackground(guiGraphics, currentWidth, currentHeight);
        }

        if (icon != null && !icon.isEmpty()) {
            int iconY = Math.max(8, (currentHeight - 16) / 2);
            guiGraphics.renderItem(icon, 8, iconY);
        }

        guiGraphics.drawString(font, title, 30, 7, 0xFFAA00, false);

        if (descriptionLines != null) {
            int lineY = (descriptionLines.size() == 1) ? 18 : 17;
            for (FormattedCharSequence line : descriptionLines) {
                guiGraphics.drawString(font, line, 30, lineY, 0xFFFFFF, false);
                lineY += 11;
            }
        }

        return timeSinceLastVisible >= 3500L ? Visibility.HIDE : Visibility.SHOW;
    }

    private void drawSlicedBackground(GuiGraphics guiGraphics, int width, int height) {
        int topH = 28;
        int bottomH = Math.min(4, height - topH);

        drawPart(guiGraphics, width, 0, 0, topH);
        for (int y = topH; y < height - bottomH; y += 16) {
            drawPart(guiGraphics, width, 16, y, Math.min(16, height - y - bottomH));
        }
        drawPart(guiGraphics, width, 32 - bottomH, height - bottomH, bottomH);
    }

    private void drawPart(GuiGraphics guiGraphics, int width, int textureU, int y, int height) {
        int uLeft = (textureU == 0) ? 20 : 5;
        int uRight = Math.min(60, width - uLeft);

        guiGraphics.blitSprite(TEXTURE, 160, 32, 0, textureU, 0, y, uLeft, height);

        for (int x = uLeft; x < width - uRight; x += 64) {
            int partW = Math.min(64, width - x - uRight);
            guiGraphics.blitSprite(TEXTURE, 160, 32, 32, textureU, x, y, partW, height);
        }

        guiGraphics.blitSprite(TEXTURE, 160, 32, 160 - uRight, textureU, width - uRight, y, uRight, height);
    }
}
