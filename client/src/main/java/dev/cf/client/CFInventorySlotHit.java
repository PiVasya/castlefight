package dev.cf.client;

/** Position-only slot hit test. No Minecraft classes; can be regression tested offline. */
public final class CFInventorySlotHit {
    private CFInventorySlotHit() { }

    public static boolean contains(double mouseX, double mouseY,
                                   int guiLeft, int guiTop, int slotX, int slotY) {
        // Inventory menu slot positions are relative to AbstractContainerScreen's
        // actual leftPos/topPos; each slot occupies a 16x16 item box plus a
        // one-pixel padding used by the previous Castle Fight handler.
        double localX = mouseX - guiLeft;
        double localY = mouseY - guiTop;
        return localX >= slotX - 1 && localX < slotX + 17
            && localY >= slotY - 1 && localY < slotY + 17;
    }
}
