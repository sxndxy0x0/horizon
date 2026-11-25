package dev.horizonlab.probe;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * Loads every vanilla class through the game's loader, then compares each method's final code with the pristine jar.
 *
 * <p>Writes {@code methods.tsv} (one row per vanilla method that is not unchanged: class, method, status, the mixins
 * that touched it), {@code added.tsv} (methods a mixin merged in) and {@code summary.tsv}, then halts the JVM before the
 * game starts.
 */
public final class Probe {
    static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

    private Probe() {}

    public static void run(ClassLoader game) {
        try {
            long start = System.nanoTime();
            List<String> failed = loadAll(game);
            long loaded = System.nanoTime();
            diff(failed);
            System.out.printf("[probe] loaded in %d ms, compared in %d ms%n",
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
                failed.add(name + "\t" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).replace('\t', ' ').replace('\n', ' '));
            }
        }
        return failed;
    }

    static void diff(List<String> failed) throws IOException {
        Files.createDirectories(Agent.out);
        int classes = 0, sameClasses = 0, changedClasses = 0, notLoaded = 0;
        int methods = 0, changed = 0, accessOnly = 0, removed = 0, added = 0;
        try (ZipFile jar = new ZipFile(Agent.vanillaJar.toFile());
                BufferedWriter rows = writer("methods.tsv");
                BufferedWriter adds = writer("added.tsv");
                BufferedWriter codes = writer("code.tsv");
                BufferedWriter fails = writer("failed.tsv")) {
            rows.write("class\tmethod\tstatus\tmixins\n");
            codes.write("class\tmethod\tcode\tmixins\n");
            // Every method as the game runs it, for comparing two runs of one loader (with and without mods): a loader
            // that recompiles the game (NeoForge) differs from the vanilla jar everywhere, but not from itself.
            for (String name : new TreeSet<>(Agent.VANILLA)) {
                byte[] after = Agent.FINAL.get(name);
                if (after == null) {
                    continue;
                }
                ClassNode b = node(after);
                Map<String, MethodNode> finals = new HashMap<>();
                for (MethodNode m : b.methods) {
                    finals.put(m.name + m.desc, m);
                }
                for (MethodNode m : b.methods) {
                    codes.write(name + "\t" + m.name + m.desc + "\t" + hash(m.access + "|" + code(m)) + "\t"
                            + String.join(",", touchedBy(m, b, finals)) + "\n");
                }
            }
            adds.write("class\tmethod\tmixin\n");
            for (String f : failed) {
                fails.write(f + "\n");
            }
            for (String name : new TreeSet<>(Agent.VANILLA)) {
                classes++;
                byte[] after = Agent.FINAL.get(name);
                if (after == null) {
                    notLoaded++;
                    continue;
                }
                byte[] before = jar.getInputStream(jar.getEntry(name + ".class")).readAllBytes();
                if (Arrays.equals(before, after)) {
                    sameClasses++;
                    continue;
                }
                ClassNode a = node(before);
                ClassNode b = node(after);
                Map<String, MethodNode> finals = new HashMap<>();
                for (MethodNode m : b.methods) {
                    finals.put(m.name + m.desc, m);
                }
                boolean any = false;
                Set<String> seen = new LinkedHashSet<>();
                for (MethodNode m : a.methods) {
                    methods++;
                    String key = m.name + m.desc;
                    seen.add(key);
                    MethodNode f = finals.get(key);
                    String status;
                    if (f == null) {
                        status = "removed";
                        removed++;
                    } else if (!code(m).equals(code(f))) {
                        status = "changed";
                        changed++;
                    } else if (m.access != f.access) {
                        status = "access";
                        accessOnly++;
                    } else {
                        continue;
                    }
                    any = true;
                    String mixins = f == null ? "" : String.join(",", touchedBy(f, b, finals));
                    rows.write(name + "\t" + key + "\t" + status + "\t" + mixins + "\n");
                }
                for (MethodNode f : b.methods) {
                    String key = f.name + f.desc;
                    if (seen.contains(key)) {
                        continue;
                    }
                    added++;
                    any = true;
                    String mixin = mergedFrom(f);
                    adds.write(name + "\t" + key + "\t" + (mixin == null ? "" : mixin) + "\n");
                }
                if (any) {
                    changedClasses++;
                } else {
                    sameClasses++;
                }
            }
        }
        try (BufferedWriter s = writer("summary.tsv")) {
            s.write("vanilla_classes\t" + classes + "\n");
            s.write("loaded_classes\t" + (classes - notLoaded) + "\n");
            s.write("not_loaded\t" + notLoaded + "\n");
            s.write("load_failures\t" + failed.size() + "\n");
            s.write("unchanged_classes\t" + sameClasses + "\n");
            s.write("changed_classes\t" + changedClasses + "\n");
            s.write("vanilla_methods_in_changed_classes\t" + methods + "\n");
            s.write("methods_changed\t" + changed + "\n");
            s.write("methods_access_only\t" + accessOnly + "\n");
            s.write("methods_removed\t" + removed + "\n");
            s.write("methods_added\t" + added + "\n");
        }
        System.out.printf("[probe] %d vanilla classes, %d changed, %d methods changed%n", classes, changedClasses, changed);
    }

    /** The mixins whose merged methods this method calls, or which merged this method itself (an overwrite). */
    static Set<String> touchedBy(MethodNode m, ClassNode owner, Map<String, MethodNode> finals) {
        Set<String> out = new TreeSet<>();
        String self = mergedFrom(m);
        if (self != null) {
            out.add(self);
        }
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner.name)) {
                MethodNode target = finals.get(call.name + call.desc);
                String from = target == null ? null : mergedFrom(target);
                if (from != null) {
                    out.add(from);
                }
            }
        }
        return out;
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

    static String hash(String text) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d, 0, 10);
        } catch (java.security.NoSuchAlgorithmException e) {
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
