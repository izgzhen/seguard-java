package edu.washington.cs.seguard;

import com.semantic_graph.writer.GexfWriter;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.log4j.PropertyConfigurator;

import edu.washington.cs.seguard.js.JSFlowGraph;
import edu.washington.cs.seguard.pe.JimpleRewriter;
import edu.washington.cs.seguard.util.StatManager;
import lombok.val;
import soot.jimple.infoflow.android.manifest.ProcessManifest;

public class App
{
    public static void main(String[] args) throws Exception
    {
        Options options = new Options();
        options.addOption(Option.builder().argName("mode").hasArg().longOpt("mode").desc("mode: deobfuscator, core, entrypoints").build());
        options.addOption(Option.builder().argName("lang").hasArg().longOpt("lang").desc("lang: java, js").build());
        options.addOption(Option.builder().argName("apk").hasArg().longOpt("apk").desc("apk path").build());
        options.addOption(Option.builder().argName("js").hasArg().longOpt("js").desc("JS path").build());
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
        String jsPath = cmd.getOptionValue("js");
        String newApkPath = cmd.getOptionValue("newApk");
        String mode = cmd.getOptionValue("mode");
        String lang = cmd.getOptionValue("lang");
        String outputPath = cmd.getOptionValue("outputPath");
        String sourceSinkFile = cmd.getOptionValue("sourceSinkFile");
        String androidPlatforms = cmd.getOptionValue("android");
        val config = Config.load("../config.yaml"); // FIXME: pass in through parameters
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
                if (lang == null || lang.equals("java")) {
//                    val dot = new BetterDot(new GraphBackend.DOT(), conditions);
                    System.out.println("Generating CallGraph (Spark)...");
//                    val flowGraph = new FlowGraph(conditions, statManager, dot, config);
                    SootOptionManager.Manager().buildOptionFlowGraph(
                            androidPlatforms, apkPath + ".out",
                            apkPath, "spark", new ProcessManifest(apkPath));
//                    flowGraph.Main();
                }
                else if (lang.equals("js")) {
                    val g = new GexfWriter<SeGuardNodeAttr$.Value, SeGuardEdgeAttr$.Value>();
                    val cg = JSFlowGraph.addCallGraph(g, jsPath);
                    JSFlowGraph.addDataFlowGraph(g, cg);
                    g.write(outputPath);
                }
                System.out.println("Written to " + outputPath);
                break;
            case "entrypoints":
                assert lang.equals("js");
                JSFlowGraph.getAllMethods(jsPath, outputPath);
                break;
            default:
                throw new RuntimeException("Unsupported mode: " + mode);
        }

        statManager.writeToDisk();
    }
}