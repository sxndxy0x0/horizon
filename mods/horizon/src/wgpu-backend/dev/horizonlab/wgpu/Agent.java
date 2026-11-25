package dev.horizonlab.wgpu;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Picks the game's graphics backend ({@code hz.render}: {@code wgpu}, {@code opengl} or {@code vulkan}) by
 * rewriting {@code PreferredGraphicsApi.getBackendsToTry} to return that one alone; works on the bytes any loader
 * defines, like the other probes' agents.
 *
 * <p>With {@code hz.capture} (an output path) it also hands every client tick to {@link Capture}, which saves the
 * title screen once it has drawn for a while and exits, and takes the random splash text off that screen.
 */
public final class Agent {
    private static final String API = "net/minecraft/client/PreferredGraphicsApi";
    private static final String GPU_BACKEND = "com/mojang/renderpearl/api/device/GpuBackend";

    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain,
                    byte[] bytes) {
                if (name == null) {
                    return null;
                }
                try {
                    return switch (name) {
                        case API -> rewrite(bytes, Agent::backends);
                        case "net/minecraft/client/Minecraft" -> System.getProperty("hz.capture") == null ? null
                                : rewrite(bytes, Agent::tick);
                        case "net/minecraft/client/resources/SplashManager" ->
                                System.getProperty("hz.capture") == null ? null : rewrite(bytes, Agent::noSplash);
                        default -> null;
                    };
                } catch (RuntimeException e) {
                    System.out.println("[horizon-wgpu] could not rewrite " + name + ": " + e);
                    return null;
                }
            }
        });
    }

    interface Edit {
        boolean apply(ClassNode node);
    }

    static byte[] rewrite(byte[] bytes, Edit edit) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if (!edit.apply(node)) {
            return null;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code return new GpuBackend[] {new <chosen>()};} */
    static boolean backends(ClassNode node) {
        String chosen = switch (System.getProperty("hz.render", "")) {
            case "wgpu" -> "dev/horizonlab/wgpu/WgpuBackend";
            case "opengl" -> "com/mojang/renderpearl/backend/opengl/GlBackend";
            case "vulkan" -> "com/mojang/renderpearl/backend/vulkan/VulkanBackend";
            default -> null;
        };
        if (chosen == null) {
            return false;
        }
        for (MethodNode m : node.methods) {
            if (m.name.equals("getBackendsToTry")) {
                InsnList body = new InsnList();
                body.add(new InsnNode(Opcodes.ICONST_1));
                body.add(new TypeInsnNode(Opcodes.ANEWARRAY, GPU_BACKEND));
                body.add(new InsnNode(Opcodes.DUP));
                body.add(new InsnNode(Opcodes.ICONST_0));
                body.add(new TypeInsnNode(Opcodes.NEW, chosen));
                body.add(new InsnNode(Opcodes.DUP));
                body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, chosen, "<init>", "()V", false));
                body.add(new InsnNode(Opcodes.AASTORE));
                body.add(new InsnNode(Opcodes.ARETURN));
                m.instructions = body;
                m.tryCatchBlocks.clear();
                m.localVariables = null;
                System.out.println("[horizon-wgpu] graphics backend: " + chosen);
                return true;
            }
        }
        return false;
    }

    /** {@code Capture.tick(this)} at the start of {@code Minecraft.runTick}. */
    static boolean tick(ClassNode node) {
        for (MethodNode m : node.methods) {
            if (m.name.equals("runTick") && m.desc.equals("(Z)V")) {
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/horizonlab/wgpu/Capture", "tick",
                        "(Lnet/minecraft/client/Minecraft;)V", false));
                m.instructions.insert(call);
                return true;
            }
        }
        return false;
    }

    /** {@code SplashManager.getSplash} returns null: the title screen draws no splash text. */
    static boolean noSplash(ClassNode node) {
        for (MethodNode m : node.methods) {
            if (m.name.equals("getSplash") && m.desc.startsWith("()")) {
                InsnList body = new InsnList();
                body.add(new InsnNode(Opcodes.ACONST_NULL));
                body.add(new InsnNode(Opcodes.ARETURN));
                m.instructions = body;
                m.tryCatchBlocks.clear();
                m.localVariables = null;
                return true;
            }
        }
        return false;
    }
}
