package dev.horizonlab.accel.boot;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Horizon's agent for the real Minecraft: Java Edition, under any loader (the launcher adds it with
 * {@code -javaagent} to a Java instance it accelerates; {@code docs/design/java-mods-everything.md}).
 *
 * <p>{@code -Dhorizon.accel=light,wgpu} says what it does: {@code light} puts block storage and light in Rust's
 * memory and propagates light in Rust (P2, P4); {@code wgpu} draws through Horizon's wgpu backend, the game's own
 * backends after it (P3). The native libraries are found beside the agent's jar, or at {@code horizon.accel.lib}
 * and {@code horizon.accel.wgpu.lib}.
 *
 * <p>Its classes come in two halves. These ({@code boot}) touch no game class: they run on the system class loader
 * and edit the game's bytes as they are defined ({@link Rewrites}). The rest ({@code runtime/} in the jar, not
 * classes the system loader can see) reference the game, so they are defined into the class loader that defines the
 * game (Fabric's Knot, or the system loader for vanilla) the first time an edit calls into them: loaded anywhere
 * else they would bring a second copy of the game's classes.
 */
public final class Agent {
    static final String BACKENDS = "net/minecraft/client/PreferredGraphicsApi";
    static final String GPU_BACKEND = "com/mojang/renderpearl/api/device/GpuBackend";
    static final Map<ClassLoader, Boolean> INJECTED = new ConcurrentHashMap<>();
    static Map<String, byte[]> runtime = Map.of();
    static Method define;
    static boolean wgpu;

    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        Set<String> on = new HashSet<>(Arrays.asList(System.getProperty("horizon.accel", "").split(",")));
        File home = home();
        if (on.contains("light")) {
            System.setProperty("hz.mode", "rust");
            System.setProperty("hz.light", "accel");
            setIfAbsent("hz.lib", System.getProperty("horizon.accel.lib", library(home, "horizon_shm_probe")));
        } else {
            System.setProperty("hz.mode", "vanilla");
        }
        wgpu = on.contains("wgpu");
        if (wgpu) {
            setIfAbsent("hz.wgpu.lib", System.getProperty("horizon.accel.wgpu.lib", library(home, "horizon_wgpu_backend")));
        }
        try {
            asm(inst, home);
            // Defining the runtime classes into the game's loader takes ClassLoader.defineClass.
            inst.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
                    Map.of("java.lang", Set.of(Agent.class.getModule())), Set.of(), Map.of());
            define = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class,
                    int.class, ProtectionDomain.class);
            define.setAccessible(true);
            runtime = readRuntime();
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            System.out.println("[horizon-accel] off: " + e);
            return;
        }
        System.out.println("[horizon-accel] on: " + on + ", " + runtime.size() + " runtime classes");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain,
                    byte[] bytes) {
                if (name == null || loader == null || !name.startsWith("net/minecraft/")) {
                    return null;
                }
                try {
                    Rewrites.guard(loader, domain, name, bytes);
                    boolean backends = wgpu && name.equals(BACKENDS);
                    if (!backends && !Rewrites.wanted(name, bytes)) {
                        return null;
                    }
                    byte[] edited = Rewrites.edit(bytes, backends);
                    if (edited != null) {
                        inject(loader, domain);
                    }
                    return edited;
                } catch (Throwable t) {
                    System.out.println("[horizon-accel] left " + name + " as it is: " + t);
                    return null;
                }
            }
        });
    }

    /**
     * ASM for the edits: the loader's own when the class path has one (Fabric, NeoForge and Forge bring theirs, and
     * Fabric refuses to start beside a second copy), else the copy beside the agent's jar (vanilla has none).
     */
    private static void asm(Instrumentation inst, File home) throws IOException {
        try {
            Class.forName("org.objectweb.asm.ClassReader", false, ClassLoader.getSystemClassLoader());
            return;
        } catch (ClassNotFoundException e) {
            // Not on the class path: add ours.
        }
        File[] jars = home.listFiles((dir, name) -> name.startsWith("asm-") && name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            throw new IOException("no ASM on the class path or beside the agent in " + home);
        }
        for (File jar : jars) {
            inst.appendToSystemClassLoaderSearch(new JarFile(jar));
        }
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null && value != null) {
            System.setProperty(key, value);
        }
    }

    /** The folder of the agent's jar. */
    private static File home() {
        try {
            return new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
        } catch (Exception e) {
            return new File(".");
        }
    }

    private static String library(File home, String name) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String file = os.contains("win") ? name + ".dll" : os.contains("mac") ? "lib" + name + ".dylib" : "lib" + name + ".so";
        return new File(home, file).getPath();
    }

    /** The runtime classes' bytes, by binary name, from the agent's own jar ({@code runtime/...class}). */
    private static Map<String, byte[]> readRuntime() throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        File jar;
        try {
            jar = new File(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        try (JarFile f = new JarFile(jar)) {
            for (JarEntry e : java.util.Collections.list(f.entries())) {
                String n = e.getName();
                if (n.startsWith("runtime/") && n.endsWith(".class")) {
                    out.put(n.substring("runtime/".length(), n.length() - ".class".length()),
                            f.getInputStream(e).readAllBytes());
                }
            }
        }
        return out;
    }

    /**
     * Defines the runtime classes into {@code loader}, once: each after the runtime classes it extends or
     * implements, so defining one never asks the loader for another it cannot find.
     */
    static void inject(ClassLoader loader, ProtectionDomain domain) {
        if (INJECTED.putIfAbsent(loader, Boolean.TRUE) != null) {
            return;
        }
        List<String> order = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String name : runtime.keySet()) {
            Rewrites.order(name, runtime, order, placed);
        }
        for (String name : order) {
            byte[] bytes = runtime.get(name);
            try {
                define.invoke(loader, name.replace('/', '.'), bytes, 0, bytes.length, null);
            } catch (ReflectiveOperationException e) {
                System.out.println("[horizon-accel] could not define " + name + " in " + loader + ": " + e.getCause());
            }
        }
        System.out.println("[horizon-accel] runtime in " + loader.getClass().getName());
    }

}
