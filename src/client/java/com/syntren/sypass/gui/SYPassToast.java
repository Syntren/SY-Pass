package com.syntren.sypass.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public class SYPassToast implements Toast {
    private static final Identifier TEXTURE = Identifier.ofVanilla("toast/advancement");
    private final Text title;
    private final Text description;
    private final ItemStack icon;

    public SYPassToast(Text title, Text description, ItemStack icon) {
        this.title = title;
        this.description = description;
        this.icon = (icon != null && !icon.isEmpty()) ? icon : new ItemStack(Items.TRIPWIRE_HOOK);
    }

    public static void show(Text title, Text description) {
        show(title, description, new ItemStack(Items.TRIPWIRE_HOOK));
    }

    public static void show(Text title, Text description, ItemStack icon) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getToastManager() != null) {
            client.execute(() -> client.getToastManager().add(new SYPassToast(title, description, icon)));
        }
    }

    @Override
    public Visibility draw(DrawContext context, ToastManager manager, long startTime) {
        // У 1.21.1 текстура спрайту малюється без RenderLayer
        context.drawGuiTexture(TEXTURE, 0, 0, this.getWidth(), this.getHeight());

        if (icon != null && !icon.isEmpty()) {
            context.drawItem(icon, 8, 8);
        }

        context.drawText(manager.getClient().textRenderer, title, 30, 7, 0xFFAA00, false);
        context.drawText(manager.getClient().textRenderer, description, 30, 18, 0xFFFFFF, false);

        // Відображається 3.5 секунди
        return startTime >= 3500L ? Visibility.HIDE : Visibility.SHOW;
    }
}