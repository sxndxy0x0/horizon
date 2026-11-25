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
            out.write(c.name + "\t" + m.name + m.desc + "\t" + hash(code(m)) + "\t" + m.access + "\t" + hash(refs(m, byKey, c.name)) + "\t"
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
                    } else if (name.endsWith(".json")) {
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
            if (out.isEmpty()) {
                // None of their members is used here (a renamed @Unique, a static initialiser's helper): every mixin on
                // the class is a candidate, marked "?" for the report to narrow down by mod.
                for (Ref ref : candidates) {
                    out.add("?" + ref.mixin());
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
     * What a method references, as a set: calls, field accesses, types and string constants, with the bodies of the
     * lambdas it creates folded in. Left out are branches, locals, line numbers, stack shapes, numeric constants, and
     * the names a recompile renumbers (lambdas, anonymous classes, synthetic accessors). Equal for a method a
     * decompile-and-recompile moved around; different when a patch calls or reads something new.
     */
    static String refs(MethodNode m, Map<String, MethodNode> byKey, String owner) {
        Set<String> out = new TreeSet<>();
        collect(m, byKey, owner, out, new HashSet<>());
        return String.join("|", out);
    }

    private static void collect(MethodNode m, Map<String, MethodNode> byKey, String owner, Set<String> out, Set<String> seen) {
        if (!seen.add(m.name + m.desc)) {
            return;
        }
        for (AbstractInsnNode insn : m.instructions) {
            switch (insn) {
                case MethodInsnNode c -> {
                    // javac's null check on a qualified `new` or a method reference, and the default branch it adds to
                    // a pattern switch: both come and go with a recompile.
                    boolean noise = c.owner.equals("java/util/Objects") && c.name.equals("requireNonNull")
                            || c.owner.equals("java/lang/MatchException");
                    if (!noise) {
                        out.add("M" + renumbered(c.owner) + "." + accessor(c.name) + c.desc);
                    }
                }
                case FieldInsnNode f -> out.add("F" + f.getOpcode() + renumbered(f.owner) + "." + f.name);
                case TypeInsnNode t -> {
                    if (!t.desc.equals("java/lang/MatchException")) {
                        out.add("T" + t.getOpcode() + renumbered(t.desc));
                    }
                }
                case LdcInsnNode l -> {
                    if (l.cst instanceof String s) {
                        out.add("L" + s);
                    } else if (l.cst instanceof Type t) {
                        out.add("Ltype:" + renumbered(t.getDescriptor()));
                    }
                    // Numbers: a recompile folds or unfolds constant expressions (180F / (float) Math.PI).
                }
                case MultiANewArrayInsnNode a -> out.add("A" + a.desc + a.dims);
                case InvokeDynamicInsnNode d -> indy(d, byKey, owner, out, seen);
                default -> {
                    // Stack shuffles, small constants, arithmetic and returns: a recompile reshapes them.
                }
            }
        }
        for (TryCatchBlockNode block : m.tryCatchBlocks) {
            out.add("X" + block.type);
        }
    }

    /**
     * A lambda or method reference counts as what it calls, so {@code font::width} and {@code s -> this.font.width(s)}
     * compare equal: a lambda of this class is folded in, any other target is a call. String concatenation counts as
     * one step, its recipe being a constant a recompile may fold differently.
     */
    private static void indy(InvokeDynamicInsnNode d, Map<String, MethodNode> byKey, String owner, Set<String> out,
            Set<String> seen) {
        String bsm = d.bsm.getOwner();
        if (bsm.equals("java/lang/invoke/LambdaMetafactory") && d.bsmArgs.length > 1 && d.bsmArgs[1] instanceof Handle impl) {
            out.add("D" + d.name + Type.getReturnType(d.desc).getDescriptor());
            MethodNode body = impl.getOwner().equals(owner) ? byKey.get(impl.getName() + impl.getDesc()) : null;
            if (body != null && (impl.getName().startsWith("lambda$") || (body.access & org.objectweb.asm.Opcodes.ACC_SYNTHETIC) != 0)) {
                collect(body, byKey, owner, out, seen);
            } else {
                out.add("M" + renumbered(impl.getOwner()) + "." + accessor(impl.getName()) + impl.getDesc());
            }
        } else if (bsm.equals("java/lang/invoke/StringConcatFactory")) {
            out.add("Dconcat");
        } else {
            out.add("D" + bsm + "." + d.bsm.getName() + ":" + d.name);
        }
    }

    /** {@code access$100}, a synthetic accessor whose number a recompile changes, as {@code access$}. */
    static String accessor(String name) {
        return name.startsWith("access$") && name.substring(7).chars().allMatch(Character::isDigit) ? "access$" : name;
    }

    /** An anonymous class's number ({@code Outer$3}), which a recompile changes, as {@code Outer$#}. */
    static String renumbered(String name) {
        return ANONYMOUS.matcher(name).replaceAll("\\$#");
    }

    static final java.util.regex.Pattern ANONYMOUS = java.util.regex.Pattern.compile("\\$\\d+(?=$|[$;/])");

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
