package dev.horizonlab.wgpu;

import com.mojang.renderpearl.api.device.GpuBackend;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * The game's graphics backends with Horizon's wgpu one first (the product agent's {@code wgpuFirst} edit), unless a
 * mod draws with OpenGL itself (Sodium, Iris, ImmediatelyFast...): with wgpu there is no OpenGL context, and such a
 * mod's first call ends the game. Then the game keeps its own backends, as it would without Horizon.
 */
public final class Backends {
    private Backends() {}

    public static GpuBackend[] withWgpu(GpuBackend[] own) {
        List<String> gl = openGlMods(new File("mods"));
        if (!gl.isEmpty()) {
            System.out.println("[horizon-accel] wgpu off: these mods draw with OpenGL themselves: " + gl);
            return own;
        }
        GpuBackend[] all = new GpuBackend[own.length + 1];
        all[0] = new WgpuBackend();
        System.arraycopy(own, 0, all, 1, own.length);
        return all;
    }

    private static final byte[] GL = "org/lwjgl/opengl/".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The jars in the game folder's {@code mods/} (the launcher starts the game there) with a class that names
     * LWJGL's OpenGL bindings, looking into the jars they carry inside too (Fabric's jar-in-jar, NeoForge's jarjar).
     */
    static List<String> openGlMods(File mods) {
        List<String> out = new ArrayList<>();
        File[] jars = mods.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null) {
            return out;
        }
        for (File jar : jars) {
            try (ZipFile z = new ZipFile(jar)) {
                boolean uses = false;
                for (ZipEntry e : java.util.Collections.list(z.entries())) {
                    if (uses) {
                        break;
                    }
                    if (e.getName().endsWith(".class")) {
                        uses = names(z.getInputStream(e).readAllBytes());
                    } else if (e.getName().endsWith(".jar")) {
                        uses = nested(z.getInputStream(e));
                    }
                }
                if (uses) {
                    out.add(jar.getName());
                }
            } catch (IOException e) {
                // Not a jar the game could load either.
            }
        }
        return out;
    }

    private static boolean nested(InputStream in) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(in.readAllBytes()))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".class") && names(z.readAllBytes())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a class file's constant pool names a class of LWJGL's OpenGL bindings. */
    private static boolean names(byte[] bytes) {
        outer:
        for (int i = 0; i + GL.length <= bytes.length; i++) {
            for (int j = 0; j < GL.length; j++) {
                if (bytes[i + j] != GL[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
