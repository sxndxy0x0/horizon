package dev.horizonlab.accel.boot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The light classes P4 guards ({@link RustLight}), by name: the agent records here, as each is defined, whether its
 * bytes are vanilla's own. Kept apart from {@code RustLight} so the agent loads no game class while transforming.
 */
public final class LightNames {
    public static final String[] GUARDED = {
        "net/minecraft/world/level/lighting/LightEngine",
        "net/minecraft/world/level/lighting/BlockLightEngine",
        "net/minecraft/world/level/lighting/SkyLightEngine",
        "net/minecraft/world/level/lighting/LayerLightSectionStorage",
        "net/minecraft/world/level/lighting/SkyLightSectionStorage",
        "net/minecraft/world/level/lighting/BlockLightSectionStorage",
        "net/minecraft/world/level/lighting/DataLayerStorageMap",
        // What the block snapshots (RustLight.snapshot) read, past LightChunk.getBlockState, by method: loaders and
        // mods add to these classes (Fabric API counts air its own way, Sodium unpacks containers), which is fine.
        "net/minecraft/world/level/chunk/LevelChunkSection.getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;",
        "net/minecraft/world/level/chunk/LevelChunkSection.hasOnlyAir()Z",
        "net/minecraft/world/level/chunk/PalettedContainer.get(III)Ljava/lang/Object;",
        "net/minecraft/world/level/chunk/PalettedContainer.get(I)Ljava/lang/Object;",
        // Methods (class.method+descriptor), checked alone: loaders and mods change other methods of these classes.
        "net/minecraft/world/level/chunk/LevelChunk.getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
        "net/minecraft/world/level/chunk/ProtoChunk.getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
    };
    /**
     * The map, kept in the system properties under {@link #KEY}: the runtime classes, defined into the game's own
     * loader, cannot see this class (Fabric's Knot shows game code nothing of the class path), but they share the
     * JDK's classes with it.
     */
    public static final String KEY = "horizon.accel.vanilla";
    public static final Map<String, Boolean> VANILLA_BYTES = vanillaBytes();

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> vanillaBytes() {
        synchronized (System.getProperties()) {
            Object m = System.getProperties().get(KEY);
            if (m == null) {
                m = new ConcurrentHashMap<String, Boolean>();
                System.getProperties().put(KEY, m);
            }
            return (Map<String, Boolean>) m;
        }
    }

    private LightNames() {}
}
