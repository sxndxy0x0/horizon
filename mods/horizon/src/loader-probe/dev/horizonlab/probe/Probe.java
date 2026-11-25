package dev.horizonlab.probe;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * Loads every vanilla class through the game's loader, then writes the facts the runner classifies.
 *
 * <ul>
 *   <li>{@code vanilla.tsv} and {@code code.tsv}: one row per method of the pristine jar and of the game as it runs.
 *       {@code code} hashes the instructions (exact), {@code refs} hashes what the method references (calls, fields,
 *       types, constants), which a decompile-and-recompile keeps while it moves branches and locals around.
 *   <li>{@code mixins} in {@code code.tsv}: the mixins behind a method, from the merged methods it calls or is, or else
 *       from the mixins applied to its class whose own members it uses.
 *   <li>{@code summary.tsv} and {@code failed.tsv}: what loaded.
 * </ul>
 *
 * Then halts the JVM before the game starts.
 */
public final class Probe {
    static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    static final String HEAD = "class\tmethod\tcode\taccess\trefs\tmixins\n";

    private Probe() {}

    public static void run(ClassLoader game) {
        try {
            long start = System.nanoTime();
            List<String> failed = loadAll(game);
            long loaded = System.nanoTime();
            write(game, failed);
            System.out.printf("[probe] loaded in %d ms, written in %d ms%n",
                    (loaded - start) / 1_000_000, (System.nanoTime() - loaded) / 1_000_000);
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.out.flush();
        System.err.flush();
        // Before the game and its mods start: nothing else runs.
        Runtime.getRuntime().halt(0);
    }

    /** Defines every vanilla class without initialising it, so each goes through the loader's transforms. */
    static List<String> loadAll(ClassLoader game) {
        List<String> failed = new ArrayList<>();
        for (String name : new TreeSet<>(Agent.VANILLA)) {
            if (Agent.FINAL.containsKey(name)) {
                continue;
            }
            try {
                Class.forName(name.replace('/', '.'), false, game);
            } catch (Throwable t) {
                failed.add(name + "\t" + t.getClass().getSimpleName() + ": "
                        + String.valueOf(t.getMessage()).replace('\t', ' ').replace('\n', ' '));
            }
        }
        return failed;
    }

    static void write(ClassLoader game, List<String> failed) throws IOException {
        Files.createDirectories(Agent.out);
        Mixins mixins = new Mixins(Agent.mods);
        int loaded = 0;
        try (ZipFile jar = new ZipFile(Agent.vanillaJar.toFile());
                BufferedWriter vanilla = writer("vanilla.tsv");
                BufferedWriter codes = writer("code.tsv");
                BufferedWriter fails = writer("failed.tsv")) {
            vanilla.write(HEAD);
            codes.write(HEAD);
            for (String f : failed) {
                fails.write(f + "\n");
            }
            for (String name : new TreeSet<>(Agent.VANILLA)) {
                ZipEntry entry = jar.getEntry(name + ".class");
                try (InputStream in = jar.getInputStream(entry)) {
                    rows(vanilla, node(in.readAllBytes()), null);
                }
                byte[] after = Agent.FINAL.get(name);
                if (after != null) {
                    loaded++;
                    rows(codes, node(after), mixins);
                }
            }
        }
        try (BufferedWriter s = writer("summary.tsv")) {
            s.write("vanilla_classes\t" + Agent.VANILLA.size() + "\n");
            s.write("loaded_classes\t" + loaded + "\n");
            s.write("load_failures\t" + failed.size() + "\n");
            s.write("mixin_classes_in_mods\t" + mixins.seen() + "\n");
            s.write("vanilla_classes_mixins_target\t" + mixins.targets() + "\n");
        }
        System.out.printf("[probe] %d vanilla classes, %d loaded, %d failed%n", Agent.VANILLA.size(), loaded, failed.size());
    }

    static void rows(BufferedWriter out, ClassNode c, Mixins mixins) throws IOException {
        Map<String, MethodNode> byKey = new HashMap<>();
        for (MethodNode m : c.methods) {
            byKey.put(m.name + m.desc, m);
        }
        for (MethodNode m : c.methods) {
            String who = mixins == null ? "" : String.join(",", mixins.behind(c, m, byKey));
            out.write(c.name + "\t" + m.name + m.desc + "\t" + hash(code(m)) + "\t" + m.access + "\t" + hash(refs(m)) + "\t"
                    + who + "\n");
        }
    }

    /**
     * The mixins each vanilla class is the target of, read from the mods' own jars (nested jars included): every
     * mixin config's classes and their {@code @Mixin} targets. Loader-neutral, as every loader here runs Mixin.
     */
    static final class Mixins {
        record Ref(String mixin, Set<String> members) {}

        private final Map<String, List<Ref>> byTarget = new HashMap<>();
        private int mixinClasses;

        Mixins(java.nio.file.Path mods) {
            if (mods == null || !Files.isDirectory(mods)) {
                return;
            }
            try (var jars = Files.list(mods)) {
                for (java.nio.file.Path jar : jars.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                    scan(Files.readAllBytes(jar));
                }
            } catch (IOException e) {
                System.out.println("[probe] mods unreadable: " + e);
            }
        }

        int seen() {
            return mixinClasses;
        }

        int targets() {
            return byTarget.size();
        }

        private void scan(byte[] jar) throws IOException {
            Map<String, byte[]> classes = new HashMap<>();
            List<String> configs = new ArrayList<>();
            try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jar))) {
                for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                    String name = e.getName();
                    if (name.endsWith(".jar")) {
                        scan(in.readAllBytes());
                    } else if (name.endsWith(".class")) {
                        classes.put(name.substring(0, name.length() - 6), in.readAllBytes());
                    } else if (name.endsWith(".json") && !name.contains("/")) {
                        configs.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            for (String config : configs) {
                java.util.regex.Matcher pkg = java.util.regex.Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"").matcher(config);
                if (!pkg.find()) {
                    continue;
                }
                String base = pkg.group(1).replace('.', '/') + "/";
                java.util.regex.Matcher lists = java.util.regex.Pattern
                        .compile("\"(?:mixins|client|server)\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(config);
                while (lists.find()) {
                    java.util.regex.Matcher item = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(lists.group(1));
                    while (item.find()) {
                        String internal = base + item.group(1).replace('.', '/');
                        byte[] bytes = classes.get(internal);
                        if (bytes != null) {
                            add(internal.replace('/', '.'), node(bytes));
                        }
                    }
                }
            }
        }

        private void add(String mixin, ClassNode node) {
            mixinClasses++;
            Set<String> own = new HashSet<>();
            for (FieldNode f : node.fields) {
                if (!annotated(f.visibleAnnotations, f.invisibleAnnotations, "Shadow;")) {
                    own.add(f.name);
                }
            }
            for (MethodNode m : node.methods) {
                if (!m.name.startsWith("<") && !annotated(m.visibleAnnotations, m.invisibleAnnotations, "Shadow;")) {
                    own.add(m.name);
                }
            }
            Ref ref = new Ref(mixin, own);
            for (List<AnnotationNode> list : Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
                if (list == null) {
                    continue;
                }
                for (AnnotationNode a : list) {
                    if (!a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || a.values == null) {
                        continue;
                    }
                    for (int i = 0; i + 1 < a.values.size(); i += 2) {
                        if (a.values.get(i + 1) instanceof List<?> values) {
                            for (Object v : values) {
                                String target = v instanceof Type t ? t.getInternalName() : String.valueOf(v).replace('.', '/');
                                byTarget.computeIfAbsent(target, k -> new ArrayList<>()).add(ref);
                            }
                        }
                    }
                }
            }
        }

        /** Merged methods it calls or is; else the mixins on its class whose own members it uses. */
        Set<String> behind(ClassNode c, MethodNode m, Map<String, MethodNode> byKey) {
            Set<String> out = new TreeSet<>();
            String self = mergedFrom(m);
            if (self != null) {
                out.add(self);
            }
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(c.name)) {
                    MethodNode target = byKey.get(call.name + call.desc);
                    String from = target == null ? null : mergedFrom(target);
                    if (from != null) {
                        out.add(from);
                    }
                }
            }
            List<Ref> candidates = byTarget.get(c.name);
            if (!out.isEmpty() || candidates == null) {
                return out;
            }
            Set<String> used = new HashSet<>();
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof FieldInsnNode f && f.owner.equals(c.name)) {
                    used.add(f.name);
                } else if (insn instanceof MethodInsnNode call && call.owner.equals(c.name)) {
                    used.add(call.name);
                }
            }
            for (Ref ref : candidates) {
                if (ref.members().stream().anyMatch(used::contains)) {
                    out.add(ref.mixin());
                }
            }
            return out;
        }
    }

    static boolean annotated(List<AnnotationNode> a, List<AnnotationNode> b, String suffix) {
        for (List<AnnotationNode> list : Arrays.asList(a, b)) {
            if (list != null) {
                for (AnnotationNode n : list) {
                    if (n.desc.endsWith(suffix)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static String mergedFrom(MethodNode m) {
        for (List<AnnotationNode> list : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
            if (list == null) {
                continue;
            }
            for (AnnotationNode a : list) {
                if (a.desc.equals(MERGED) && a.values != null) {
                    for (int i = 0; i + 1 < a.values.size(); i += 2) {
                        if ("mixin".equals(a.values.get(i))) {
                            return String.valueOf(a.values.get(i + 1));
                        }
                    }
                }
            }
        }
        return null;
    }

    /** A method's instructions and handlers as text: frames and maxs left out, as a rewrite recomputes them. */
    static String code(MethodNode m) {
        Printer printer = new Textifier();
        TraceMethodVisitor trace = new TraceMethodVisitor(printer);
        for (AbstractInsnNode insn : m.instructions) {
            insn.accept(trace);
        }
        for (TryCatchBlockNode block : m.tryCatchBlocks) {
            block.accept(trace);
        }
        StringBuilder text = new StringBuilder();
        for (Object line : printer.getText()) {
            text.append(line);
        }
        return text.toString();
    }

    /**
     * What a method references, sorted: calls, field accesses, types and pooled constants, without branches, locals,
     * line numbers, stack shapes or a lambda's synthetic name. Equal for a method a decompile-and-recompile moved around;
     * different when a patch calls something else or adds a step.
     */
    static String refs(MethodNode m) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            String r = switch (insn) {
                case MethodInsnNode c -> "M" + c.getOpcode() + c.owner + "." + c.name + c.desc;
                case FieldInsnNode f -> "F" + f.getOpcode() + f.owner + "." + f.name;
                case TypeInsnNode t -> "T" + t.getOpcode() + t.desc;
                case LdcInsnNode l -> "L" + constant(l.cst);
                case MultiANewArrayInsnNode a -> "A" + a.desc + a.dims;
                case InvokeDynamicInsnNode d -> "D" + d.name + d.desc + indyArgs(d.bsmArgs);
                // Stack shuffles, small constants, arithmetic and returns are left out: a recompile reshapes them
                // (merged returns, ternaries), and a patch that changes behaviour nearly always calls or reads something.
                default -> null;
            };
            if (r != null) {
                out.add(r);
            }
        }
        for (TryCatchBlockNode block : m.tryCatchBlocks) {
            out.add("X" + block.type);
        }
        out.sort(null);
        return String.join("|", out);
    }

    static String indyArgs(Object[] args) {
        StringBuilder s = new StringBuilder();
        for (Object a : args) {
            s.append(',').append(a instanceof Handle h && h.getName().startsWith("lambda$")
                    ? "lambda" + h.getDesc() : constant(a));
        }
        return s.toString();
    }

    static String constant(Object c) {
        return c instanceof Type t ? "type:" + t.getDescriptor() : c instanceof Handle h
                ? h.getOwner() + "." + (h.getName().startsWith("lambda$") ? "lambda" : h.getName()) + h.getDesc()
                : c.getClass().getSimpleName() + ":" + c;
    }

    static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        return node;
    }

    static BufferedWriter writer(String file) throws IOException {
        return Files.newBufferedWriter(Agent.out.resolve(file), StandardCharsets.UTF_8);
    }
}
