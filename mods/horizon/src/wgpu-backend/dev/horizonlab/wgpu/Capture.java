package dev.horizonlab.wgpu;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Saves the title screen as the game drew it and exits: once the screen has been up with nothing over it for
 * {@code hz.capture.frames} ticks (default 120), the game's own screenshot path reads its frame back (a texture
 * copy to a buffer, then a read mapping: the read-back half of the backend), writes it to {@code hz.capture}, and
 * records how long the title screen's frames took.
 */
public final class Capture {
    private static final int FRAMES = Integer.getInteger("hz.capture.frames", 120);
    private static final long LIMIT = Long.getLong("hz.capture.seconds", 300L) * 1_000_000_000L;
    private static final long START = System.nanoTime();
    private static int frames;
    private static long first;
    private static boolean taken;
    /** The longest wait between two client ticks: how long the window went without handling its messages. */
    private static long lastTick;
    private static long longestGap;

    private Capture() {}

    public static void tick(Minecraft mc) {
        long now = System.nanoTime();
        if (lastTick != 0) {
            longestGap = Math.max(longestGap, now - lastTick);
        }
        lastTick = now;
        try {
            if (System.nanoTime() - START > LIMIT) {
                fail("the title screen did not come up in time (screen: " + mc.gui.screen() + ")");
            }
            if (taken) {
                return;
            }
            if (!(mc.gui.screen() instanceof TitleScreen) || mc.gui.overlay() != null) {
                frames = 0;
                return;
            }
            if (frames == 0) {
                first = System.nanoTime();
            }
            if (++frames < FRAMES) {
                return;
            }
            taken = true;
            double ms = (System.nanoTime() - first) / 1e6 / (frames - 1);
            Path out = Path.of(System.getProperty("hz.capture"));
            String backend = mc.getWindow().toString();
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), (NativeImage image) -> {
                try (image) {
                    image.writeToFile(out);
                    Files.writeString(Path.of(out + ".txt"), "frames\t" + frames + "\nms per frame\t"
                            + String.format("%.3f", ms) + "\nlongest tick gap ms\t"
                            + String.format("%.0f", longestGap / 1e6) + "\nsize\t" + image.getWidth() + "x" + image.getHeight()
                            + "\nwindow\t" + backend + "\n", StandardCharsets.UTF_8);
                    System.out.println("[horizon-wgpu] title screen saved to " + out);
                } catch (IOException e) {
                    fail("saving the screenshot: " + e);
                }
                System.out.flush();
                Runtime.getRuntime().halt(0);
            });
        } catch (RuntimeException e) {
            e.printStackTrace(System.out);
            fail("capture: " + e);
        }
    }

    private static void fail(String why) {
        System.out.println("[horizon-wgpu] capture failed: " + why);
        System.out.flush();
        Runtime.getRuntime().halt(4);
    }
}
