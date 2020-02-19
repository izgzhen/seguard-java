package edu.washington.cs.seguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

import android.util.Base64;
import com.google.common.collect.Sets;
import com.semantic_graph.writer.GexfWriter;
import edu.washington.cs.seguard.core.IFDSDataFlowTransformer;
import edu.washington.cs.seguard.js.JSFlowGraph;
import edu.washington.cs.seguard.pe.AliasRewriter;
import lombok.val;
import org.junit.Test;

import scala.Enumeration;
import soot.*;

import soot.jimple.InstanceInvokeExpr;
import soot.jimple.Stmt;
import soot.options.Options;

import soot.toolkits.scalar.Pair;
import soot.util.dot.DotGraph;

/**
 * Unit tests.
 */
public class AppTest
{
    @Test
    public void shouldAnswerWithTrue()
    {
        SootClass cls = new SootClass("TestClass");
        SootMethod m = new SootMethod("testMethod", Collections.emptyList(), VoidType.v());
        cls.addMethod(m);
        SootField f = new SootField("testStaticStringField", RefType.v("String"));
        f.setModifiers(Modifier.STATIC);
        cls.addField(f);
    }

    @Test
    public void getStaticStringsTest() {
        soot.G.reset();
        Options.v().set_process_dir(Collections.singletonList("src/test/resources"));
        Options.v().set_soot_classpath("src/test/resources:lib/rt.jar");
        Options.v().set_whole_program(true);
        Options.v().set_allow_phantom_refs(true);
        Options.v().set_no_bodies_for_excluded(true);
        Options.v().set_ignore_resolution_errors(true);
        Scene.v().loadNecessaryClasses();
        SootClass testClass = Scene.v().getSootClass("Test");
        assertEquals(testClass.getMethods().toString(), 4, testClass.getMethods().size());
        assertEquals(testClass.getFields().toString(), 1, testClass.getFields().size());
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.test", new SceneTransformer() {
            @Override
            protected void internalTransform(String s, Map<String, String> map) {
                val strings = Util.getStaticStrings(testClass);
                assertEquals(strings.toString(), 1, strings.size());
            }
        }));
    }

    @Test
    public void testAnalysis() throws IOException {
        SootOptionManager.Manager().buildOptionTest();
        Scene.v().loadNecessaryClasses();
        Scene.v().addBasicClass("dummyMainClass", SootClass.BODIES);
        Scene.v().loadClassAndSupport("dummyMainClass");
        SootClass testClass = Scene.v().getSootClass("dummyMainClass");
        SootMethod method = testClass.getMethodByName("dummyMainMethod");
        method.retrieveActiveBody();
        Scene.v().setEntryPoints(Collections.singletonList(method));
        val config = Config.load("src/test/resources/config.yaml");
        val conditions = new Conditions("SourcesAndSinks.txt", config);
        IFDSDataFlowTransformer transformer = new IFDSDataFlowTransformer(conditions, config);
        System.out.println(method.getActiveBody());
        val rewriter = new AliasRewriter(conditions);
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.aliasrewriter", rewriter));
        PackManager.v().getPack("wjtp").add(new Transform("wjtp.herosifds", transformer));
        PackManager.v().getPack("jtp").add(new Transform("jtp.myTransform", new BodyTransformer() {
            @Override
            protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
                val className = b.getMethod().getDeclaringClass().getName();
                for (Unit u : b.getUnits()) {
                    Stmt s = (Stmt) u;
                    if (s.containsInvokeExpr() && s.getInvokeExpr() instanceof InstanceInvokeExpr) {
                        InstanceInvokeExpr e = (InstanceInvokeExpr) s.getInvokeExpr();
                        if (e.getMethod().getName().equals("println") && className.equals("dummyMainClass")) {
                            boolean equals = false;
                            val constants = "[[other]]";
                            for (val result : transformer.getSolver().ifdsResultsAt(u)) {
                                if (result.getO1().equals(e.getArg(0))) {
                                    System.out.println(result.getO2().toString());
                                    val str = result.getO2().toString();
                                    if (str.equals(constants)) {
                                        equals = true;
                                    }
                                }
                            }
                            assertTrue(s.toString(), equals);
                        }
                    }
                }
            }
        }));
        PackManager.v().runPacks();
    }

    @Test
    public void testAndroidAPI() {
        System.out.println(new String(Base64.decode("aHR0cDovL3poZWthcHR5LmNvbQ==", 0)));
    }
}
