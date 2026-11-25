package dev.horizonlab.probe;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The loader-neutral probe (docs/design/java-mods-everything.md, P1).
 *
 * <p>A {@code ClassFileTransformer} runs at {@code defineClass}, after every transform a loader applies (mixins, access
 * wideners, coremods, the loader's own patches), so it sees each vanilla class as the game will run it, whichever loader
 * defined it. When the game's entry class is defined, its {@code main} is given a first step that hands the game's class
 * loader to {@link Probe#run}, which loads every other vanilla class, compares each with the pristine jar and exits
 * before the game starts.
 */
public final class Agent {
    /** The final bytes of each vanilla class, as the game's loader defined it. */
    static final Map<String, byte[]> FINAL = new ConcurrentHashMap<>();

    /** The game's entry classes: the client's and the dedicated server's. */
    static final Set<String> TRIGGERS = Set.of("net/minecraft/client/main/Main", "net/minecraft/server/Main");

    static final Set<String> VANILLA = ConcurrentHashMap.newKeySet();
    static Path vanillaJar;
    static Path out;

    private Agent() {}

    /** Arguments: {@code <vanilla jar>|<output folder>}. */
    public static void premain(String args, Instrumentation inst) throws Exception {
        String[] parts = args.split("\\|", 2);
        vanillaJar = Path.of(parts[0]);
        out = Path.of(parts[1]);
        try (ZipFile jar = new ZipFile(vanillaJar.toFile())) {
            jar.stream()
                    .map(ZipEntry::getName)
                    .filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/") && !n.equals("module-info.class"))
                    .forEach(n -> VANILLA.add(n.substring(0, n.length() - ".class".length())));
        }
        ClassLoader system = ClassLoader.getSystemClassLoader();
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                // The game's own loader only: never the bootstrap or the launcher's system loader.
                if (name == null || loader == null || loader == system || !VANILLA.contains(name)) {
                    return null;
                }
                FINAL.put(name, bytes.clone());
                return TRIGGERS.contains(name) ? hookMain(bytes) : null;
            }
        });
        System.out.println("[probe] agent ready: " + VANILLA.size() + " vanilla classes");
    }

    /** Puts {@code Probe.run(Main.class.getClassLoader())} at the head of {@code main}, through reflection. */
    static byte[] hookMain(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        String owner = reader.getClassName();
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!name.equals("main") || !desc.equals("([Ljava/lang/String;)V")) {
                            return mv;
                        }
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                emitCall(mv, owner);
                            }
                        };
                    }
                },
                0);
        return writer.toByteArray();
    }

    private static void emitCall(MethodVisitor mv, String owner) {
        // Class.forName("dev.horizonlab.probe.Probe", true, ClassLoader.getSystemClassLoader())
        mv.visitLdcInsn("dev.horizonlab.probe.Probe");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/ClassLoader", "getSystemClassLoader",
                "()Ljava/lang/ClassLoader;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        // .getMethod("run", new Class[] { ClassLoader.class })
        mv.visitLdcInsn("run");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLdcInsn(Type.getObjectType("java/lang/ClassLoader"));
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        // .invoke(null, new Object[] { Main.class.getClassLoader() })
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLdcInsn(Type.getObjectType(owner));
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                "()Ljava/lang/ClassLoader;", false);
        mv.visitInsn(Opcodes.AASTORE);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
    }
}
