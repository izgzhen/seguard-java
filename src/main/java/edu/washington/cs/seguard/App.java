package edu.washington.cs.seguard;

import com.semantic_graph.writer.GexfWriter;
import edu.washington.cs.seguard.apk_core.FlowGraph;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.log4j.PropertyConfigurator;

import edu.washington.cs.seguard.pe.JimpleRewriter;
import edu.washington.cs.seguard.util.StatManager;
import lombok.val;
import soot.jimple.infoflow.android.manifest.ProcessManifest;

public class App {
    /**
     * The entry points to all analyses
     */
    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addOption(Option.builder().argName("mode").hasArg().longOpt("mode").desc("mode: deobfuscator, core, entrypoints").build());
        options.addOption(Option.builder().argName("apk").hasArg().longOpt("apk").desc("apk path").build());
        options.addOption(Option.builder().argName("config").hasArg().longOpt("config").desc("config path").build());
        options.addOption(Option.builder().argName("newApk").hasArg().longOpt("newApk").desc("new apk path").build());
        options.addOption(Option.builder().argName("outputPath").hasArg().longOpt("outputPath").desc("output path").build());
        options.addOption(Option.builder().argName("apkclasses").hasArg().longOpt("apkclasses").desc("apk classes file path").build());
        options.addOption(Option.builder().argName("java").hasArg().longOpt("java").desc("java path").build());
        options.addOption("d", false, "Turn on debug which will" +
                        "dumps the full abstraction at Config.abstractionDumpPath and " +
                        "dumps the call graph at ???");
        options.addOption(Option.builder().argName("android").hasArg().longOpt("android").desc("android platforms dir").build());
        options.addOption(Option.builder().argName("sourceSinkFile").hasArg()
               .longOpt("sourceSinkFile").desc("Sources and sinks config (xml/txt)").build());
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);
        PropertyConfigurator.configure("log4j.properties");
        String javaClassPath = cmd.getOptionValue("java");
        String apkPath = cmd.getOptionValue("apk");
        String newApkPath = cmd.getOptionValue("newApk");
        String mode = cmd.getOptionValue("mode");
        String outputPath = cmd.getOptionValue("outputPath");
        String sourceSinkFile = cmd.getOptionValue("sourceSinkFile");
        String androidPlatforms = cmd.getOptionValue("android");
        val config = Config.load(cmd.getOptionValue("config"));
        config.setDebug(cmd.hasOption("d"));
        config.setAbstractionDumpPath(apkPath + ".abstraction.txt");
        config.setCallGraphDumpPath(apkPath + ".callgraph.txt");
        val statManager = new StatManager(apkPath, mode);

        switch (mode) {
            case "deobfuscator":
                JimpleRewriter.Main(androidPlatforms, javaClassPath, apkPath,
                        cmd.getOptionValue("apkclasses"), newApkPath,
                        mode, statManager, new ProcessManifest(apkPath));
                break;
            case "core":
                Conditions conditions = new Conditions(sourceSinkFile, config);
                val graphWriter = new GexfWriter<SeGuardNodeAttr$.Value, SeGuardEdgeAttr$.Value>();
                System.out.println("Generating CallGraph (Spark)...");
                val flowGraph = new FlowGraph(conditions, statManager, graphWriter, config);
                SootOptionManager.Manager().buildOptionFlowGraph(
                        androidPlatforms, apkPath + ".out",
                        apkPath, "spark", new ProcessManifest(apkPath));
                flowGraph.Main();
                graphWriter.write(outputPath);
                System.out.println("Written to " + outputPath);
                break;
            default:
                throw new RuntimeException("Unsupported mode: " + mode);
        }

        statManager.writeToDisk();
    }
}