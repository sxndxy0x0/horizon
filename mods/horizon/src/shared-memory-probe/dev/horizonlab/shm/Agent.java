package dev.horizonlab.shm;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Puts the game's block storage and light in Rust's memory: every {@code new SimpleBitStorage(...)} in the game's
 * classes becomes {@code RustBitStorage.create(...)}, and every {@code new DataLayer(...)} (method references
 * {@code DataLayer::new} included) becomes {@code RustDataLayer.create(...)}; each returns the vanilla type, over
 * memory Rust owns. Loader-neutral like the loader probe's agent: it works on the bytes any loader defines.
 *
 * <p>For the live-world run ({@code -Dhz.world=...}) it also hands each game-test server tick to {@link World}.
 */
public final class Agent {
    static final String STORAGE = "net/minecraft/util/SimpleBitStorage";
    static final String RUST = "dev/horizonlab/shm/RustBitStorage";
    static final String LAYER = "net/minecraft/world/level/chunk/DataLayer";
    static final String RUST_LAYER = "dev/horizonlab/shm/RustDataLayer";
    /** Each vanilla class the game constructs, and the factory that stands in for its constructors. */
    static final Map<String, String> FACTORIES = Map.of(STORAGE, RUST, LAYER, RUST_LAYER);
    static final String TEST_SERVER = "net/minecraft/gametest/framework/GameTestServer";
    static final String TEST_MAIN = "net/minecraft/gametest/framework/GameTestMainUtil";
    /** Whether the game's storage and light go to Rust's memory: not in the vanilla run of the world probe. */
    static final boolean STORE = !"vanilla".equals(System.getProperty("hz.mode"));
    static final AtomicInteger REWRITTEN = new AtomicInteger();
    static final List<String> SITES = new ArrayList<>();

    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain,
                    byte[] bytes) {
                if (name == null || !name.startsWith("net/minecraft/") || FACTORIES.containsKey(name)) {
                    return null;
                }
                try {
                    return rewrite(bytes);
                } catch (RuntimeException e) {
                    System.out.println("[shm] could not rewrite " + name + ": " + e);
                    return null;
                }
            }
        });
    }

    /**
     * Each {@code getRaw()} on a storage becomes {@code RustBitStorage.raw(storage)}, and the method flushes before it
     * returns or throws, so a method that writes into the array (PalettedContainer.read, Heightmap.setRawData) writes
     * into Rust's memory.
     */
    static boolean lendRaw(ClassNode node, MethodNode m) {
        boolean lent = false;
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode c && c.name.equals("getRaw") && c.desc.equals("()[J")
                    && (c.owner.equals(STORAGE) || c.owner.equals("net/minecraft/util/BitStorage"))) {
                m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, RUST, "raw",
                        "(Lnet/minecraft/util/BitStorage;)[J", false));
                lent = true;
            }
        }
        if (!lent) {
            return false;
        }
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN || op == Opcodes.ATHROW) {
                m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, RUST, "flush", "()V", false));
            }
        }
        synchronized (SITES) {
            SITES.add("raw:" + node.name + "." + m.name);
        }
        return true;
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

    /** A method reference to a vanilla constructor ({@code DataLayer::new}), as a reference to its factory. */
    static Handle factoryHandle(Handle h) {
        String factory = FACTORIES.get(h.getOwner());
        if (h.getTag() != Opcodes.H_NEWINVOKESPECIAL || factory == null) {
            return null;
        }
        String desc = Type.getMethodDescriptor(Type.getObjectType(h.getOwner()), Type.getArgumentTypes(h.getDesc()));
        return new Handle(Opcodes.H_INVOKESTATIC, factory, "create", desc, false);
    }

    static void site(String name) {
        REWRITTEN.incrementAndGet();
        synchronized (SITES) {
            SITES.add(name);
        }
    }

    /**
     * Each NEW/DUP ... INVOKESPECIAL <init> of a vanilla class in {@link #FACTORIES} becomes the arguments, then
     * INVOKESTATIC create; each method reference to such a constructor becomes a reference to create.
     */
    static byte[] rewrite(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        boolean testServer = reader.getClassName().equals(TEST_SERVER) && System.getProperty("hz.world") != null;
        if (!text.contains(STORAGE) && !text.contains(LAYER) && !text.contains("getRaw") && !testServer
                && !reader.getClassName().equals(TEST_MAIN)) {
            return null;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean changed = testServer && hookWorld(node);
        if (reader.getClassName().equals(TEST_MAIN) && Boolean.getBoolean("hz.keep")) {
            changed |= keepWorld(node);
        }
        for (MethodNode m : STORE ? node.methods : List.<MethodNode>of()) {
            changed |= lendRaw(node, m);
            Deque<AbstractInsnNode> open = new ArrayDeque<>();
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && FACTORIES.containsKey(t.desc)
                        && next != null && next.getOpcode() == Opcodes.DUP) {
                    open.push(t);
                } else if (insn instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL
                        && FACTORIES.containsKey(c.owner) && c.name.equals("<init>") && !open.isEmpty()
                        && ((TypeInsnNode) open.peek()).desc.equals(c.owner)) {
                    AbstractInsnNode created = open.pop();
                    m.instructions.remove(created.getNext());
                    m.instructions.remove(created);
                    Type[] args = Type.getArgumentTypes(c.desc);
                    String desc = Type.getMethodDescriptor(Type.getObjectType(c.owner), args);
                    m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, FACTORIES.get(c.owner), "create",
                            desc, false));
                    changed = true;
                    site(node.name + "." + m.name);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        if (indy.bsmArgs[i] instanceof Handle h && factoryHandle(h) != null) {
                            indy.bsmArgs[i] = factoryHandle(h);
                            changed = true;
                            site("ref:" + node.name + "." + m.name);
                        }
                    }
                }
                insn = next;
            }
        }
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
