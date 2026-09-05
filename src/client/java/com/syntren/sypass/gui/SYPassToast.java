package com.syntren.sypass.gui;

import com.syntren.sypass.config.SYPassConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.List;

@Environment(EnvType.CLIENT)
public class SYPassToast implements Toast {
    private static final Identifier TEXTURE = Identifier.ofVanilla("toast/advancement");
    private final Text title;
    private final Text description;
    private final ItemStack icon;

    private List<OrderedText> descriptionLines = null;
    private int width = 160;

    public SYPassToast(Text title, Text description, ItemStack icon) {
        this.title = title;
        this.description = description;
        this.icon = (icon != null && !icon.isEmpty()) ? icon : new ItemStack(Items.TRIPWIRE_HOOK);
    }

    public static void show(Text title, Text description) {
        show(title, description, new ItemStack(Items.TRIPWIRE_HOOK));
    }

    public static void show(Text title, Text description, ItemStack icon) {
        if (!SYPassConfig.isToastsEnabled()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getToastManager() != null) {
            client.execute(() -> client.getToastManager().add(new SYPassToast(title, description, icon)));
        }
    }

    private void calculateSize(TextRenderer textRenderer) {
        if (descriptionLines != null) return;

        int maxWrapWidth = 190;
        if (description != null) {
            List<OrderedText> wrapped = textRenderer.wrapLines(description, maxWrapWidth);
            if (wrapped.size() > 2) {
                this.descriptionLines = wrapped.subList(0, 2);
            } else {
                this.descriptionLines = wrapped;
            }
        } else {
            this.descriptionLines = Collections.emptyList();
        }

        int maxTextWidth = textRenderer.getWidth(title);
        for (OrderedText line : descriptionLines) {
            maxTextWidth = Math.max(maxTextWidth, textRenderer.getWidth(line));
        }

        this.width = Math.max(160, Math.min(235, maxTextWidth + 38));
    }

    @Override
    public int getWidth() {
        return this.width;
    }

    @Override
    public int getHeight() {
        if (descriptionLines != null && descriptionLines.size() > 1) {
            return 32 + (descriptionLines.size() - 1) * 11;
        }
        return 32;
    }

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
        TextRenderer textRenderer = manager.getClient().textRenderer;
        calculateSize(textRenderer);

        int currentWidth = getWidth();
        int currentHeight = getHeight();

        if (currentWidth <= 160 && (descriptionLines == null || descriptionLines.size() <= 1)) {
            context.drawGuiTexture(TEXTURE, 0, 0, currentWidth, currentHeight);
        } else {
            drawSlicedBackground(context, currentWidth, currentHeight);
        }

        if (icon != null && !icon.isEmpty()) {
            int iconY = Math.max(8, (currentHeight - 16) / 2);
            context.drawItem(icon, 8, iconY);
        }

        context.drawText(textRenderer, title, 30, 7, 0xFFAA00, false);

        if (descriptionLines != null) {
            int lineY = (descriptionLines.size() == 1) ? 18 : 17;
            for (OrderedText line : descriptionLines) {
                context.drawText(textRenderer, line, 30, lineY, 0xFFFFFF, false);
                lineY += 11;
            }
        }

        return startTime >= 3500L ? Visibility.HIDE : Visibility.SHOW;
    }

    private void drawSlicedBackground(DrawContext context, int width, int height) {
        int topH = 28;
        int bottomH = Math.min(4, height - topH);

        drawPart(context, width, 0, 0, topH);
        for (int y = topH; y < height - bottomH; y += 16) {
            drawPart(context, width, 16, y, Math.min(16, height - y - bottomH));
        }
        drawPart(context, width, 32 - bottomH, height - bottomH, bottomH);
    }

    private void drawPart(DrawContext context, int width, int textureU, int y, int height) {
        int uLeft = (textureU == 0) ? 20 : 5;
        int uRight = Math.min(60, width - uLeft);

        context.drawGuiTexture(TEXTURE, 160, 32, 0, textureU, 0, y, uLeft, height);

        for (int x = uLeft; x < width - uRight; x += 64) {
            int partW = Math.min(64, width - x - uRight);
            context.drawGuiTexture(TEXTURE, 160, 32, 32, textureU, x, y, partW, height);
        }

        context.drawGuiTexture(TEXTURE, 160, 32, 160 - uRight, textureU, width - uRight, y, uRight, height);
    }
}
