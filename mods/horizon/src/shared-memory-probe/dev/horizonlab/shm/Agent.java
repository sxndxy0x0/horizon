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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Puts the game's block storage in Rust's memory: every {@code new SimpleBitStorage(...)} in the game's classes
 * becomes {@code RustBitStorage.create(...)}, which returns a {@code SimpleBitStorage} whose words Rust owns.
 * Loader-neutral like the loader probe's agent: it works on the bytes any loader defines.
 */
public final class Agent {
    static final String STORAGE = "net/minecraft/util/SimpleBitStorage";
    static final String RUST = "dev/horizonlab/shm/RustBitStorage";
    static final AtomicInteger REWRITTEN = new AtomicInteger();
    static final List<String> SITES = new ArrayList<>();

    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain,
                    byte[] bytes) {
                if (name == null || !name.startsWith("net/minecraft/") || name.equals(STORAGE)) {
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

    /** Each NEW/DUP ... INVOKESPECIAL <init> of SimpleBitStorage becomes the arguments, then INVOKESTATIC create. */
    static byte[] rewrite(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!text.contains(STORAGE) && !text.contains("getRaw")) {
            return null;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean changed = false;
        for (MethodNode m : node.methods) {
            changed |= lendRaw(node, m);
            Deque<AbstractInsnNode> open = new ArrayDeque<>();
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals(STORAGE)
                        && next != null && next.getOpcode() == Opcodes.DUP) {
                    open.push(t);
                } else if (insn instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL
                        && c.owner.equals(STORAGE) && c.name.equals("<init>") && !open.isEmpty()) {
                    AbstractInsnNode created = open.pop();
                    m.instructions.remove(created.getNext());
                    m.instructions.remove(created);
                    Type[] args = Type.getArgumentTypes(c.desc);
                    String desc = Type.getMethodDescriptor(Type.getObjectType(STORAGE), args);
                    m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, RUST, "create", desc, false));
                    changed = true;
                    REWRITTEN.incrementAndGet();
                    synchronized (SITES) {
                        SITES.add(node.name + "." + m.name);
                    }
                }
                insn = next;
            }
        }
        if (!changed) {
            return null;
        }
        // Frames are kept (the rewrite adds no branch); maxs shrink by the removed NEW/DUP.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
