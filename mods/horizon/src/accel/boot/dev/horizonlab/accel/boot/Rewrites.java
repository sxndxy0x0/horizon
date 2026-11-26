package dev.horizonlab.accel.boot;

import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The bytecode edits Horizon's accelerators make to the game's classes, shared by the product agent ({@link Agent})
 * and the probes' agents. They touch no game class themselves (they run while classes are being defined) and name
 * the runtime classes they call ({@code dev.horizonlab.accel.*}) only by name.
 *
 * <ul>
 *   <li>Memory (P2, {@link #memory}): every {@code new SimpleBitStorage(...)} and {@code new DataLayer(...)} (method
 *       references included) becomes the runtime's factory, over memory Rust owns; each {@code getRaw()} on a
 *       storage lends a copy that is written back.
 *   <li>Light (P4, {@link #light}): {@code LightEngine.runLightUpdates} hands its propagation to {@code RustLight};
 *       {@code LevelChunkSection} and {@code BlockStateBase} get the fields its snapshots and classes live in.
 * </ul>
 */
public final class Rewrites {
    public static final String STORAGE = "net/minecraft/util/SimpleBitStorage";
    public static final String LAYER = "net/minecraft/world/level/chunk/DataLayer";
    public static final String RUNTIME = "dev/horizonlab/accel/";
    public static final String RUST_STORAGE = RUNTIME + "RustBitStorage";
    public static final String RUST_LAYER = RUNTIME + "RustDataLayer";
    public static final String RUST_LIGHT = RUNTIME + "RustLight";
    /** Each vanilla class the game constructs, and the factory that stands in for its constructors. */
    public static final Map<String, String> FACTORIES = Map.of(STORAGE, RUST_STORAGE, LAYER, RUST_LAYER);
    public static final String ENGINE = "net/minecraft/world/level/lighting/LightEngine";
    public static final String SECTION = "net/minecraft/world/level/chunk/LevelChunkSection";
    public static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    /** The fields added: a section's light-class snapshot and its version, a state's light class. */
    public static final String SECTION_LIGHT = "hz$light";
    public static final String SECTION_VERSION = "hz$version";
    public static final String STATE_CLASS = "hz$lightClass";

    public static final AtomicInteger REWRITTEN = new AtomicInteger();
    public static final List<String> SITES = new ArrayList<>();

    private Rewrites() {}

    /** Whether the game's block storage and light go to Rust's memory ({@code hz.mode} not {@code vanilla}). */
    public static boolean memory() {
        return !"vanilla".equals(System.getProperty("hz.mode"));
    }

    /** {@code hz.light}: {@code accel} (Rust propagates light), {@code time} (vanilla's, timed), or none. */
    public static String light() {
        return System.getProperty("hz.light");
    }

    /** Records, for a guarded light class ({@link LightNames}), whether it is defined with its jar's own bytes. */
    public static void guard(ClassLoader loader, ProtectionDomain domain, String name, byte[] bytes) {
        if (memory() && name.equals(STORAGE)) {
            guardStorage(loader, domain, bytes);
        }
        if (light() == null) {
            return;
        }
        for (String g : LightNames.GUARDED) {
            if (g.equals(name)) {
                byte[] jar = jarBytes(loader, domain, name);
                LightNames.VANILLA_BYTES.put(name, jar != null && Arrays.equals(jar, bytes));
            } else if (g.startsWith(name + ".")) {
                String method = g.substring(name.length() + 1);
                byte[] jar = jarBytes(loader, domain, name);
                LightNames.VANILLA_BYTES.put(g, jar != null && code(bytes, method).equals(code(jar, method)));
            }
        }
    }

    /**
     * What mods add to {@code SimpleBitStorage} that {@code RustBitStorage} does as well (name and descriptor):
     * Sodium's copy of a section for its meshes.
     */
    static final java.util.Set<String> STORAGE_ADDITIONS = java.util.Set.of(
            "sodium$unpack([Ljava/lang/Object;Lnet/minecraft/world/level/chunk/Palette;)V");

    /**
     * Records whether block storage may go to Rust: every method of the jar's {@code SimpleBitStorage} runs as it
     * does there, and whatever a mod added is in {@link #STORAGE_ADDITIONS}. Else the game's storage stays its own.
     */
    static void guardStorage(ClassLoader loader, ProtectionDomain domain, byte[] bytes) {
        byte[] jar = jarBytes(loader, domain, STORAGE);
        List<String> changed = new ArrayList<>();
        if (jar == null) {
            changed.add("(no jar to compare with)");
        } else {
            java.util.Set<String> original = new java.util.HashSet<>();
            for (MethodNode m : node(jar).methods) {
                original.add(m.name + m.desc);
                if (!code(bytes, m.name + m.desc).equals(code(jar, m.name + m.desc))) {
                    changed.add(m.name + m.desc);
                }
            }
            for (MethodNode m : node(bytes).methods) {
                if (!original.contains(m.name + m.desc) && !STORAGE_ADDITIONS.contains(m.name + m.desc)) {
                    changed.add(m.name + m.desc);
                }
            }
        }
        LightNames.VANILLA_BYTES.put(STORAGE, changed.isEmpty());
        if (!changed.isEmpty()) {
            System.out.println("[horizon-accel] block storage stays Java: SimpleBitStorage changed by a mod: " + changed);
        }
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG
                | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        return node;
    }

    /**
     * The class's bytes as its jar holds them, or null: from the loader's own resources first (ModLauncher's jars,
     * Forge's, are on a file system of its own, under URLs a {@code File} cannot open), else the code source's jar.
     */
    static byte[] jarBytes(ClassLoader loader, ProtectionDomain domain, String name) {
        if (loader != null) {
            try (java.io.InputStream in = loader.getResourceAsStream(name + ".class")) {
                if (in != null) {
                    return in.readAllBytes();
                }
            } catch (java.io.IOException e) {
                // The code source's jar, then.
            }
        }
        try {
            java.net.URL where = domain == null || domain.getCodeSource() == null ? null
                    : domain.getCodeSource().getLocation();
            if (where == null) {
                return null;
            }
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new java.io.File(where.toURI()))) {
                java.util.jar.JarEntry entry = jar.getJarEntry(name + ".class");
                return entry == null ? null : jar.getInputStream(entry).readAllBytes();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A method's instructions as text (opcodes and what they name; labels, lines and frames left out), "" when the
     * class has no such method: two classes run the method alike when this is equal.
     */
    static String code(byte[] bytes, String nameAndDesc) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG
                | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        StringBuilder out = new StringBuilder();
        for (MethodNode m : node.methods) {
            if (!(m.name + m.desc).equals(nameAndDesc)) {
                continue;
            }
            for (AbstractInsnNode insn : m.instructions) {
                if (insn.getOpcode() < 0) {
                    continue;
                }
                out.append(insn.getOpcode());
                if (insn instanceof MethodInsnNode c) {
                    out.append(' ').append(c.owner).append('.').append(c.name).append(c.desc);
                } else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f) {
                    out.append(' ').append(f.owner).append('.').append(f.name).append(f.desc);
                } else if (insn instanceof TypeInsnNode t) {
                    out.append(' ').append(t.desc);
                } else if (insn instanceof org.objectweb.asm.tree.LdcInsnNode l) {
                    out.append(' ').append(l.cst);
                } else if (insn instanceof VarInsnNode v) {
                    out.append(' ').append(v.var);
                } else if (insn instanceof org.objectweb.asm.tree.IntInsnNode i) {
                    out.append(' ').append(i.operand);
                } else if (insn instanceof org.objectweb.asm.tree.IincInsnNode i) {
                    out.append(' ').append(i.var).append(' ').append(i.incr);
                } else if (insn instanceof InvokeDynamicInsnNode d) {
                    out.append(' ').append(d.name).append(d.desc).append(Arrays.toString(d.bsmArgs));
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** Whether {@link #apply} may change a class with these bytes: a cheap look before it is parsed. */
    public static boolean wanted(String className, byte[] bytes) {
        if (light() != null && memory()
                && (className.equals(ENGINE) || className.equals(SECTION) || className.equals(STATE_BASE))) {
            return true;
        }
        if (!memory() || FACTORIES.containsKey(className)) {
            return false;
        }
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        return text.contains(STORAGE) || text.contains(LAYER) || text.contains("getRaw");
    }

    /** Every edit this class needs; whether any was made. */
    public static boolean apply(ClassNode node) {
        boolean changed = false;
        if (light() != null && memory()) {
            changed |= switch (node.name) {
                case ENGINE -> hookEngine(node);
                case SECTION -> hookSection(node);
                case STATE_BASE -> hookState(node);
                default -> false;
            };
        }
        if (memory() && !FACTORIES.containsKey(node.name)) {
            changed |= memory(node);
        }
        return changed;
    }

    /**
     * Whether a class is defined with the bytes its jar holds: no loader, mixin or agent before this one changed
     * it. Light runs in Rust only when every guarded class is vanilla's own ({@code RustLight.rustRuns}).
     */
    public static boolean sameAsJar(ProtectionDomain domain, String name, byte[] bytes) {
        try {
            java.net.URL where = domain == null || domain.getCodeSource() == null ? null
                    : domain.getCodeSource().getLocation();
            if (where == null) {
                return false;
            }
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(new java.io.File(where.toURI()))) {
                java.util.jar.JarEntry entry = jar.getJarEntry(name + ".class");
                return entry != null && Arrays.equals(jar.getInputStream(entry).readAllBytes(), bytes);
            }
        } catch (Exception e) {
            return false;
        }
    }

    // -- Memory ----------------------------------------------------------------------------------------------------

    /**
     * Each NEW/DUP ... INVOKESPECIAL <init> of a vanilla class in {@link #FACTORIES} becomes the arguments, then
     * INVOKESTATIC create; each method reference to such a constructor becomes a reference to create; and
     * {@link #lendRaw}.
     */
    static boolean memory(ClassNode node) {
        boolean changed = false;
        for (MethodNode m : node.methods) {
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
        return changed;
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
                m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, RUST_STORAGE, "raw",
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
                m.instructions.insertBefore(insn,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, RUST_STORAGE, "flush", "()V", false));
            }
        }
        synchronized (SITES) {
            SITES.add("raw:" + node.name + "." + m.name);
        }
        return true;
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

    // -- Light -----------------------------------------------------------------------------------------------------

    /**
     * In {@code LightEngine.runLightUpdates}, {@code propagateDecreases()} becomes {@code RustLight.propagate(this)}
     * and {@code propagateIncreases()} {@code RustLight.zero(this)}.
     */
    static boolean hookEngine(ClassNode node) {
        boolean changed = false;
        for (MethodNode m : node.methods) {
            if (!m.name.equals("runLightUpdates") || !m.desc.equals("()I")) {
                continue;
            }
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn instanceof MethodInsnNode c && c.owner.equals(ENGINE) && c.desc.equals("()I")
                        && (c.name.equals("propagateDecreases") || c.name.equals("propagateIncreases"))) {
                    m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, RUST_LIGHT,
                            c.name.equals("propagateDecreases") ? "propagate" : "zero", "(L" + ENGINE + ";)I", false));
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * {@code LevelChunkSection} gets its light-class snapshot's address and version ({@code RustLight.snapshot}); with
     * {@code hz.light=accel}, {@code setBlockState} and {@code read} tell {@code RustLight} what changed before they
     * return.
     */
    static boolean hookSection(ClassNode node) {
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_VOLATILE, SECTION_LIGHT, "J", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_VOLATILE, SECTION_VERSION, "I", null, null));
        if (!"accel".equals(light())) {
            return true;
        }
        String state = "Lnet/minecraft/world/level/block/state/BlockState;";
        for (MethodNode m : node.methods) {
            boolean set = m.name.equals("setBlockState") && m.desc.equals("(III" + state + "Z)" + state);
            boolean read = m.name.equals("read") && m.desc.equals("(Lnet/minecraft/network/FriendlyByteBuf;)V");
            if (!set && !read) {
                continue;
            }
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.ARETURN && insn.getOpcode() != Opcodes.RETURN) {
                    continue;
                }
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                if (set) {
                    call.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    call.add(new VarInsnNode(Opcodes.ILOAD, 2));
                    call.add(new VarInsnNode(Opcodes.ILOAD, 3));
                    call.add(new VarInsnNode(Opcodes.ALOAD, 4));
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUST_LIGHT, "blockSet",
                            "(L" + SECTION + ";III" + state + ")V", false));
                } else {
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUST_LIGHT, "sectionRead",
                            "(L" + SECTION + ";)V", false));
                }
                m.instructions.insertBefore(insn, call);
            }
        }
        return true;
    }

    /** {@code BlockStateBase} gets the field its light class is kept in ({@code RustLight.classOf}). */
    static boolean hookState(ClassNode node) {
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, STATE_CLASS, "I", null, null));
        return true;
    }

    /**
     * Horizon's wgpu backend before the game's own: before each return of
     * {@code PreferredGraphicsApi.getBackendsToTry}, {@code Backends.withWgpu(theGamesList)}. A machine wgpu cannot
     * draw on starts on the game's OpenGL or Vulkan.
     */
    public static boolean wgpuFirst(ClassNode node) {
        boolean changed = false;
        for (MethodNode m : node.methods) {
            if (!m.name.equals("getBackendsToTry")) {
                continue;
            }
            for (AbstractInsnNode insn : m.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.ARETURN) {
                    m.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "dev/horizonlab/wgpu/Backends", "withWgpu",
                            "([Lcom/mojang/renderpearl/api/device/GpuBackend;)[Lcom/mojang/renderpearl/api/device/GpuBackend;", false));
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Every edit a class needs as the product agent makes them ({@link #apply}, and with {@code wgpuFirst}
     * {@link #wgpuFirst}): its new bytes, or null when nothing changed. Here, not in {@link Agent}: the JVM reads the
     * agent class's method signatures before {@code premain} can put ASM on the class path.
     */
    public static byte[] edit(byte[] bytes, boolean wgpuFirst) {
        ClassNode node = new ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
        boolean changed = apply(node);
        changed |= wgpuFirst && wgpuFirst(node);
        if (!changed) {
            return null;
        }
        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code name} into {@code order} after the runtime classes it extends or implements. */
    public static void order(String name, Map<String, byte[]> runtime, List<String> order, java.util.Set<String> placed) {
        if (!runtime.containsKey(name) || !placed.add(name)) {
            return;
        }
        org.objectweb.asm.ClassReader r = new org.objectweb.asm.ClassReader(runtime.get(name));
        order(r.getSuperName(), runtime, order, placed);
        for (String i : r.getInterfaces()) {
            order(i, runtime, order, placed);
        }
        order.add(name);
    }
}
