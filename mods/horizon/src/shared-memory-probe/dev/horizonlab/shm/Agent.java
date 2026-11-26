package dev.horizonlab.shm;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import dev.horizonlab.accel.boot.Rewrites;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import java.util.Map;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The probes' agent for P2 and P4: the product's edits ({@link Rewrites}: block storage and light in Rust's memory,
 * light propagated by Rust), on the plain class path the probes run the game from, plus the probes' own hooks: the
 * live-world run ({@code -Dhz.world=...}, each game-test server tick handed to {@link World}), the kept save
 * ({@code hz.keep}) and the relight test ({@code hz.relight}).
 */
public final class Agent {
    static final String TEST_SERVER = "net/minecraft/gametest/framework/GameTestServer";
    static final String TEST_MAIN = "net/minecraft/gametest/framework/GameTestMainUtil";
    static final String CHUNK_DATA = "net/minecraft/world/level/chunk/storage/SerializableChunkData";
    /** Whether the game's storage and light go to Rust's memory: not in the vanilla run of the world probe. */
    static final boolean STORE = Rewrites.memory();
    static final String LIGHT = Rewrites.light();
    static final java.util.concurrent.atomic.AtomicInteger REWRITTEN = Rewrites.REWRITTEN;
    static final List<String> SITES = Rewrites.SITES;

    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain,
                    byte[] bytes) {
                if (name == null || !name.startsWith("net/minecraft/")) {
                    return null;
                }
                Rewrites.guard(domain, name, bytes);
                try {
                    return rewrite(name, bytes);
                } catch (RuntimeException e) {
                    System.out.println("[shm] could not rewrite " + name + ": " + e);
                    return null;
                }
            }
        });
    }

    /**
     * The world probe's hook ({@link World}; here, so the transform loads no game class): in
     * {@code GameTestServer.tickServer}, right after the server's own tick: {@code if (World.tick(this))
     * return;}, so the test server runs no tests while the probe has the world. With {@code hz.world=normal} its
     * flat preset becomes the normal one.
     */
    static boolean hookWorld(ClassNode node) {
        boolean changed = false;
        boolean normal = "normal".equals(System.getProperty("hz.world"));
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (normal && insn instanceof FieldInsnNode f
                        && f.owner.equals("net/minecraft/world/level/levelgen/presets/WorldPresets")
                        && f.name.equals("FLAT_ALL_DIMENSIONS")) {
                    f.name = "NORMAL";
                    changed = true;
                }
            }
            if (!m.name.equals("tickServer") || !m.desc.equals("(Ljava/util/function/BooleanSupplier;)V")) {
                continue;
            }
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL
                        && c.name.equals("tickServer")) {
                    InsnList add = new InsnList();
                    LabelNode go = new LabelNode();
                    add.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/horizonlab/shm/World", "tick",
                            "(Lnet/minecraft/server/MinecraftServer;)Z", false));
                    add.add(new JumpInsnNode(Opcodes.IFEQ, go));
                    add.add(new InsnNode(Opcodes.RETURN));
                    add.add(go);
                    // The locals here are the method's own arguments, as at its start.
                    add.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                    m.instructions.insert(c, add);
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    /**
     * With {@code hz.keep}, the test server keeps the world already in its folder (it resets the folder
     * otherwise): {@code createOrResetDir} returns at once, so the server loads the saved chunks instead of
     * generating new ones. Every run then starts from the same world.
     */
    static boolean keepWorld(ClassNode node) {
        for (MethodNode m : node.methods) {
            if (m.name.equals("createOrResetDir") && m.desc.equals("(Ljava/lang/String;)V")) {
                // The body becomes a bare return: nothing of it is left for a frame or handler to refer to.
                m.instructions.clear();
                m.instructions.add(new InsnNode(Opcodes.RETURN));
                m.tryCatchBlocks.clear();
                m.localVariables = null;
                return true;
            }
        }
        return false;
    }

    /**
     * P4's relight test ({@code hz.relight}): every chunk loaded from the save comes in unlit, so the game lights it
     * from scratch as it lights a new one. In {@code SerializableChunkData.parse}, {@code getBooleanOr("isLightOn",
     * ...)} gives false, and each section's {@code getByteArray("BlockLight")} and {@code ("SkyLight")} nothing.
     */
    static boolean hookRelight(ClassNode node) {
        boolean changed = false;
        for (MethodNode m : node.methods) {
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (!(insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) || !(ldc.cst instanceof String key)) {
                    continue;
                }
                AbstractInsnNode call = insn.getNext();
                while (call != null && !(call instanceof MethodInsnNode)) {
                    call = call.getNext();
                }
                if (!(call instanceof MethodInsnNode c)) {
                    continue;
                }
                InsnList replace = new InsnList();
                if (key.equals("isLightOn") && c.name.equals("getBooleanOr")) {
                    replace.add(new InsnNode(Opcodes.POP));
                    replace.add(new InsnNode(Opcodes.ICONST_0));
                } else if ((key.equals("BlockLight") || key.equals("SkyLight")) && c.name.equals("getByteArray")) {
                    replace.add(new InsnNode(Opcodes.POP));
                    replace.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty",
                            "()Ljava/util/Optional;", false));
                } else {
                    continue;
                }
                m.instructions.insert(c, replace);
                changed = true;
            }
        }
        return changed;
    }

    static byte[] rewrite(String name, byte[] bytes) {
        boolean testServer = name.equals(TEST_SERVER) && System.getProperty("hz.world") != null;
        boolean keep = name.equals(TEST_MAIN) && Boolean.getBoolean("hz.keep");
        boolean relight = name.equals(CHUNK_DATA) && Boolean.getBoolean("hz.relight");
        if (!testServer && !keep && !relight && !Rewrites.wanted(name, bytes)) {
            return null;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        boolean changed = testServer && hookWorld(node);
        changed |= keep && keepWorld(node);
        changed |= relight && hookRelight(node);
        changed |= Rewrites.apply(node);
        if (!changed) {
            return null;
        }
        // Frames are kept: the rewrites add no branch, and the world hook's one branch lands where a frame
        // already is. Maxs shrink by the removed NEW/DUP.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
