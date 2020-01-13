package edu.washington.cs.seguard;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.options.Options;

import java.util.Collections;

public class SootOptionManager {
    private static SootOptionManager instanceSootOptionManager;

    public static SootOptionManager Manager() {
        if (instanceSootOptionManager == null) {
            synchronized (SootOptionManager.class) {
                if (instanceSootOptionManager == null) {
                    instanceSootOptionManager = new SootOptionManager();
                }
            }
        }
        return instanceSootOptionManager;
    }

    private void buildOption(String androidPlatforms, String apkPath, ProcessManifest manifest) {
        // Resets the option.
        soot.G.reset();
        Options.v().set_process_dir(Collections.singletonList(apkPath));
        Options.v().set_android_jars(androidPlatforms);
        int targetSdkVersion = 28;
        if (manifest.targetSdkVersion() != -1) {
            targetSdkVersion = manifest.targetSdkVersion();
        }
        Options.v().set_soot_classpath(String.format("%s/android-%d/android.jar", androidPlatforms, targetSdkVersion));
        Options.v().set_src_prec(Options.src_prec_apk);
        Options.v().set_whole_program(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_ignore_resolution_errors(true);
        Options.v().set_process_multiple_dex(true);
        Options.v().set_no_writeout_body_releasing(true);
    }

    public void buildOptionFlowGraph(String androidPlatforms, String outPath, String apkPath, String cgalgo, ProcessManifest manifest) {
        buildOption(androidPlatforms, apkPath, manifest);
        Options.v().set_output_format(Options.output_format_none);
        Options.v().set_output_dir(outPath);
        Options.v().setPhaseOption("cg." + cgalgo, "on");
        Options.v().set_no_bodies_for_excluded(true);
        Options.v().set_omit_excepting_unit_edges(true);
    }

    public void buildOptionJimpleRewriter(String androidPlatforms, String apkPath, ProcessManifest manifest) {
        buildOption(androidPlatforms, apkPath, manifest);
        Options.v().set_output_format(Options.output_format_dex);
        Options.v().set_force_overwrite(true);
    }

    public void sootRunPacks() {
        soot.Scene.v().loadNecessaryClasses();
        soot.PackManager.v().runPacks();
        if (!Options.v().oaat()) {
            soot.PackManager.v().writeOutput();
        }
    }

    public void buildOptionTest() {
        soot.G.reset();
        Options.v().set_process_dir(Collections.singletonList("src/test/resources"));
        Options.v().set_soot_classpath("src/test/resources:lib/rt.jar");
        Options.v().set_whole_program(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_no_bodies_for_excluded(true);
        Options.v().setPhaseOption("cg.spark", "on");
        Options.v().set_no_writeout_body_releasing(true);
    }
}
