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
 * Saves a frame as the game drew it and exits. With {@code hz.capture.world} it is a world (opened with quick
 * play): the server's ticks are frozen, the camera is held at a fixed place and angle ({@code hz.capture.view}:
 * x,y,z,yaw,pitch), and the frame is taken once the chunks have had {@code hz.capture.frames} frames to build.
 * Otherwise it is the title screen: once the screen has been up with nothing over it for
 * {@code hz.capture.frames} ticks (default 120), the game's own screenshot path reads its frame back (a texture
 * copy to a buffer, then a read mapping: the read-back half of the backend), writes it to {@code hz.capture}, and
 * records how long the title screen's frames took.
 */
public final class Capture {
    private static final int FRAMES = Integer.getInteger("hz.capture.frames", 120);
    /** At least this long between the first frame and the capture ({@code hz.capture.hold}, seconds). */
    private static final long HOLD_NS = (long) (Double.parseDouble(System.getProperty("hz.capture.hold", "0")) * 1e9);
    private static final boolean WORLD = Boolean.getBoolean("hz.capture.world");
    private static final double[] VIEW = java.util.Arrays.stream(
            System.getProperty("hz.capture.view", "8.5,100,8.5,135,30").split(",")).mapToDouble(Double::parseDouble)
            .toArray();
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
                fail((WORLD ? "the world" : "the title screen") + " did not come up in time (screen: "
                        + mc.gui.screen() + ")");
            }
            if (taken) {
                return;
            }
            boolean ready = WORLD
                    ? mc.level != null && mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null
                    : mc.gui.screen() instanceof TitleScreen && mc.gui.overlay() == null;
            if (!ready) {
                frames = 0;
                return;
            }
            if (WORLD) {
                if (frames == 0 && mc.getSingleplayerServer() != null) {
                    var server = mc.getSingleplayerServer();
                    // One time of day for every run (the sky and the sun at the same place), then nothing moves:
                    // the clock stopped too (a frozen server still lets the client's clock run on, and runs at
                    // different frame rates reach the capture at different times).
                    server.execute(() -> {
                        var source = server.createCommandSourceStack().withSuppressedOutput();
                        server.getCommands().performPrefixedCommand(source, "gamerule advance_time false");
                        server.getCommands().performPrefixedCommand(source, "time set 1000");
                        server.tickRateManager().setFrozen(true);
                    });
                }
                mc.player.snapTo(VIEW[0], VIEW[1], VIEW[2], (float) VIEW[3], (float) VIEW[4]);
                mc.player.setDeltaMovement(0, 0, 0);
            }
            if (frames == 0) {
                first = System.nanoTime();
            }
            if (++frames == FRAMES - 10 && System.getenv("HZ_WGPU_DUMP_ALL") != null) {
                Native.dumpNext();
            }
            // In a world, also a fixed time: the sky's and fog's colors blend over seconds, not frames, and runs at
            // different frame rates reach the frame count at different times.
            if (frames < FRAMES || System.nanoTime() - first < HOLD_NS) {
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
                    System.out.println("[horizon-wgpu] frame saved to " + out);
                } catch (IOException e) {
                    fail("saving the screenshot: " + e);
                }
                // Profiling (-Dhz.jfr=file with -XX:StartFlightRecording): halt runs no exit hooks, so the recording
                // is written here.
                String jfr = System.getProperty("hz.jfr");
                if (jfr != null) {
                    for (jdk.jfr.Recording r : jdk.jfr.FlightRecorder.getFlightRecorder().getRecordings()) {
                        try {
                            r.dump(Path.of(jfr));
                        } catch (IOException e) {
                            System.out.println("[horizon-wgpu] jfr: " + e);
                        }
                    }
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
