package dev.horizonlab.shm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The light classes P4 guards ({@link RustLight}), by name: the agent records here, as each is defined, whether its
 * bytes are vanilla's own. Kept apart from {@code RustLight} so the agent loads no game class while transforming.
 */
final class LightNames {
    static final String[] GUARDED = {
        "net/minecraft/world/level/lighting/LightEngine",
        "net/minecraft/world/level/lighting/BlockLightEngine",
        "net/minecraft/world/level/lighting/SkyLightEngine",
        "net/minecraft/world/level/lighting/LayerLightSectionStorage",
        "net/minecraft/world/level/lighting/SkyLightSectionStorage",
        "net/minecraft/world/level/lighting/BlockLightSectionStorage",
        "net/minecraft/world/level/lighting/DataLayerStorageMap",
    };
    static final Map<String, Boolean> VANILLA_BYTES = new ConcurrentHashMap<>();

    private LightNames() {}
}
