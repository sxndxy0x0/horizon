import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools import loader_probe as probe


def row(code, refs, access="1", mixins=""):
    return {"code": code, "refs": refs, "access": access, "mixins": mixins}


def jar_bytes(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, data in files.items():
            zf.writestr(name, data)
    return buf.getvalue()


class ClassifyTests(unittest.TestCase):
    def test_a_recompile_is_told_from_a_patch_by_what_a_method_references(self):
        before = {("a/A", "m()V"): row("c1", "r1"), ("a/A", "n()V"): row("c2", "r2"), ("a/A", "o()V"): row("c3", "r3"),
                  ("a/A", "lambda$m$0()V"): row("l", "l"), ("a/A", "lambda$n$1()V"): row("k", "k")}
        after = {("a/A", "m()V"): row("c1x", "r1"), ("a/A", "n()V"): row("c2x", "r2x"), ("a/A", "o()V"): row("c3", "r3", "9"),
                 ("a/A", "lambda$m$3()V"): row("l", "l"), ("a/A", "lambda$n$1()V"): row("other", "other")}
        self.assertEqual(probe.classify(before, after, recompiled=True),
                         {("a/A", "m()V"): "recompile", ("a/A", "n()V"): "changed", ("a/A", "o()V"): "access"})

    def test_renumbered_anonymous_classes_are_matched_by_shape(self):
        before = {("a/A$1", "run()V"): row("x", "go"), ("a/A$2", "run()V"): row("y", "stop")}
        same = {("a/A$7", "run()V"): row("x2", "stop"), ("a/A$8", "run()V"): row("y2", "go")}
        self.assertEqual(probe.classify(before, same, recompiled=True), {})
        patched = {("a/A$7", "run()V"): row("x2", "stop"), ("a/A$8", "run()V"): row("z", "hook")}
        self.assertEqual(probe.classify(before, patched, recompiled=True), {("a/A$8", "run()V"): "changed"})

    def test_against_the_same_loader_any_code_change_counts_and_lambdas_too(self):
        before = {("a/A", "m()V"): row("c1", "r1"), ("a/A", "gone()V"): row("g", "g")}
        after = {("a/A", "m()V"): row("c1x", "r1"), ("a/A", "lambda$m$0()V"): row("l", "l")}
        self.assertEqual(probe.classify(before, after, recompiled=False),
                         {("a/A", "m()V"): "changed", ("a/A", "lambda$m$0()V"): "added", ("a/A", "gone()V"): "removed"})


class SettleTests(unittest.TestCase):
    def test_a_class_no_source_patch_names_is_the_recompile_only(self):
        status = {("net/A", "m()V"): "changed", ("net/A$1", "run()V"): "changed", ("net/B", "m()V"): "changed",
                  ("net/B", "n()V"): "access"}
        settled, count = probe.settle(status, {"net/A": None})
        self.assertEqual(settled, {("net/A", "m()V"): "changed", ("net/A$1", "run()V"): "changed",
                                   ("net/B", "m()V"): "recompile", ("net/B", "n()V"): "access"})
        self.assertEqual(count, 1)

    def test_with_the_patch_files_a_method_is_changed_exactly_where_its_lines_are_patched(self):
        patch = ("--- a/A.java\n+++ b/A.java\n@@ -10,4 +10,5 @@\n context\n-        x < 1\n+        x <= 1\n"
                 "+        // Neo\n context\n@@ -40,2 +41,3 @@\n context\n+@Annotation\n context\n")
        lines = probe.patch_lines(patch)
        self.assertEqual(lines, {10, 11, 12, 42})
        # The same patch as NeoForge writes it, without the new side's starts: 40 + (2 added - 1 removed) = 41.
        self.assertEqual(probe.patch_lines(patch.replace("+10,5", "+_,5").replace("+41,3", "+_,3")), lines)
        disk = {("net/A", "near()V"): {"lines": "9-13"}, ("net/A", "far()V"): {"lines": "20-30"},
                ("net/A", "<class>"): {"lines": ""}}
        # near() moved only by the recompile, by its references, but the patch changed a comparison in it.
        status = {("net/A", "near()V"): "recompile", ("net/A", "far()V"): "changed"}
        settled, count = probe.settle(status, {"net/A": lines}, disk)
        self.assertEqual(settled, {("net/A", "near()V"): "changed", ("net/A", "far()V"): "recompile",
                                   ("net/A", "<class>"): "changed"})
        self.assertEqual(count, 2)


class AttributeTests(unittest.TestCase):
    def test_each_kind_of_evidence_names_its_owner(self):
        classes = {"a.AMixin": "a.jar", "b.BMixin": "b.jar", "b.CMixin": "b.jar"}
        access = {("net/X", "run"): {"aw.jar"}}
        coremods = {"net/Y": {"core.jar"}}
        vanilla = {("net/X", "run()V"): {}, ("net/Y", "go()V"): {}, ("net/Z", "f()V"): {}}

        def who(key, mixins, status="changed"):
            return probe.attribute(key, mixins, status, "(unattributed)", classes, access, coremods, vanilla)

        self.assertEqual(who(("net/X", "run()V"), "a.AMixin,?b.BMixin"), {"a.jar"})
        self.assertEqual(who(("net/X", "run()V"), "", "access"), {"aw.jar"})
        self.assertEqual(who(("net/Y", "go()V"), ""), {"core.jar"})
        self.assertEqual(who(("net/Z", "f()V"), "?b.BMixin,?b.CMixin"), {"b.jar"})
        self.assertEqual(who(("net/Z", "f()V"), "?a.AMixin,?b.BMixin"), {"one of: a.jar, b.jar"})
        self.assertEqual(who(("net/Z", "create(Ljava/lang/String;)V"), ""), {"(the loader, on a method it added)"})
        self.assertEqual(who(("net/Z", "f()V"), ""), {"(unattributed)"})
        # An interface a mod's jar holds names that mod; the loader's own interfaces are no evidence.
        classes["fab.api.FabricFlag"] = "fabric-api.jar"
        self.assertEqual(who(("net/Z", "<class>"), "@fab.api.FabricFlag,@neo.IExtension"), {"fabric-api.jar"})
        self.assertEqual(who(("net/Z", "<class>"), "@neo.IExtension,?b.BMixin"), {"b.jar"})


class ProfileTests(unittest.TestCase):
    def test_a_loader_profile_replaces_its_parents_library_of_the_same_artifact(self):
        with tempfile.TemporaryDirectory() as tmp:
            versions, libs = Path(tmp) / "versions", Path(tmp) / "libraries"
            (versions / "26.3").mkdir(parents=True)
            (versions / "loader").mkdir()
            (versions / "26.3" / "26.3.json").write_text(json.dumps({"mainClass": "net.minecraft.client.main.Main",
                "libraries": [{"name": "org.ow2.asm:asm:9.10"}, {"name": "com.mojang:blaze:1"},
                              {"name": "org.lwjgl:lwjgl:3:natives-windows"}]}))
            (versions / "loader" / "loader.json").write_text(json.dumps({"inheritsFrom": "26.3", "mainClass": "x.Knot",
                "libraries": [{"name": "org.ow2.asm:asm:9.11"}]}))
            jars, main, game = probe.classpath(versions / "loader" / "loader.json", versions, libs)
            self.assertEqual(main, "x.Knot")
            self.assertEqual(sorted(p.name for p in jars), ["asm-9.11.jar", "blaze-1.jar"])
            self.assertEqual(game, versions / "26.3" / "26.3.jar")
            self.assertEqual(probe.vanilla_jar(versions / "loader" / "loader.json", versions), versions / "26.3" / "26.3.jar")


class ModFactsTests(unittest.TestCase):
    def test_classes_and_access_entries_are_found_in_nested_jars(self):
        inner = jar_bytes({
            "fabric.mod.json": json.dumps({"accessWidener": "x.accesswidener"}),
            "x.accesswidener": "accessWidener v2 named\naccessible method net/minecraft/A run ()V\n"
                               "accessible class net/minecraft/B # all of it\n",
            "dev/x/mixin/AMixin.class": b"",
        })
        outer = {
            "META-INF/jars/inner.jar": inner,
            "META-INF/neoforge.mods.toml": '[[accessTransformers]]\nfile="META-INF/at.cfg"\n',
            "META-INF/at.cfg": "public net.minecraft.C tick()V\npublic-f net.minecraft.C speed\npublic net.minecraft.D *()\n",
        }
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "mod.jar").write_bytes(jar_bytes(outer))
            classes, access, coremods = probe.mod_facts(Path(tmp))
        self.assertEqual(classes["dev.x.mixin.AMixin"], "mod.jar")
        self.assertEqual(set(access), {("net/minecraft/A", "run"), ("net/minecraft/B", None),
                                       ("net/minecraft/C", "tick"), ("net/minecraft/C", "speed"),
                                       ("net/minecraft/D", None)})


if __name__ == "__main__":
    unittest.main()
