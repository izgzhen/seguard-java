package edu.washington.cs.seguard.pe;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;

import static edu.washington.cs.seguard.util.StatKey.*;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import java.util.*;

import edu.washington.cs.seguard.SootOptionManager;
import edu.washington.cs.seguard.util.StatManager;
import edu.washington.cs.seguard.Util;
import lombok.val;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.washington.cs.seguard.Util.SourceLines;

import java.net.MalformedURLException;
import java.net.URL;

import org.xmlpull.v1.XmlPullParserException;
import soot.*;
import soot.jimple.*;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.internal.JInvokeStmt;
import soot.jimple.toolkits.scalar.Evaluator;
import soot.toolkits.scalar.UnitValueBoxPair;
import soot.options.Options;
import soot.toolkits.graph.ExceptionalUnitGraph;
import soot.toolkits.graph.Orderer;
import soot.toolkits.graph.PseudoTopologicalOrderer;
import soot.toolkits.graph.UnitGraph;
import soot.toolkits.scalar.LocalDefs;
import soot.toolkits.scalar.LocalUses;
import soot.util.Switch;

public class JimpleRewriter  {
    private static class ConstantPropagatorAndFolder extends BodyTransformer {
        ConstantPropagatorAndFolder() {  }

        protected void internalTransform(Body b, String phaseName, Map<String, String> options) {
          UnitGraph g = new ExceptionalUnitGraph(b);
          LocalDefs localDefs = LocalDefs.Factory.newLocalDefs(g);

          // Perform a constant/local propagation pass.
          Orderer<Unit> orderer = new PseudoTopologicalOrderer<Unit>();

          // go through each use box in each statement
          for (Unit u : orderer.newList(g, false)) {

            // propagation pass
            for (ValueBox useBox : u.getUseBoxes()) {
              Value value = useBox.getValue();
              if (value instanceof Local) {
                Local local = (Local) value;
                List<Unit> defsOfUse = localDefs.getDefsOfAt(local, u);
                if (defsOfUse.size() == 1) {
                  DefinitionStmt defStmt = (DefinitionStmt) defsOfUse.get(0);
                  Value rhs = defStmt.getRightOp();
                  if (rhs instanceof NumericConstant || rhs instanceof StringConstant || rhs instanceof NullConstant ||
                      rhs instanceof ClassConstant || rhs instanceof MethodConstant) {
                    if (useBox.canContainValue(rhs)) {
                      useBox.setValue(rhs);
                    }
                  } else if (rhs instanceof CastExpr) {
                    CastExpr ce = (CastExpr) rhs;
                    if (ce.getCastType() instanceof RefType && ce.getOp() instanceof NullConstant) {
                      defStmt.getRightOpBox().setValue(NullConstant.v());
                    }
                  }
                }
              }
            }

            // folding pass
            for (ValueBox useBox : u.getUseBoxes()) {
              Value value = useBox.getValue();
              if (!(value instanceof Constant)) {
                if (Evaluator.isValueConstantValued(value)) {
                  Value constValue = Evaluator.getConstantValueOf(value);
                  if (useBox.canContainValue(constValue)) {
                    useBox.setValue(constValue);
                  }
                }
              }
            }
          }
        } // optimizeConstants
      }

    private static class MethodConstant extends Constant {
        private final String className;
        private final String methodName;

        MethodConstant(String className, String methodName) {
            if (className.startsWith("L")) {
                this.className = className.substring(1, className.length() - 1).replace("/", ".");
            } else {
                throw new RuntimeException("");
            }
            this.methodName = methodName;
        }

        /**
         * @return the className
         */
        public String getClassName() {
            return className;
        }

        /**
         * @return the methodName
         */
        public String getMethodName() {
            return methodName;
        }

        @Override
        public Type getType() {
            return RefType.v("java.lang.reflect.Method");
        }

        @Override
        public void apply(Switch sw) {

        }

        @Override
        public String toString() {
            return className + "." + methodName;
        }
    }

    private static class DeobfuscateTransformer extends BodyTransformer {
        final private ClassLoader loader;
        private Set<String> unloadedClasses;
        private Logger logger;
        private SootMethod getSensitiveStringMethod;
        private String mode;
        private StatManager statManager;

        DeobfuscateTransformer(String javaClassPath, StatManager statManager,
                               SootMethod getSensitiveStringMethod, String mode) {
            this.logger = LoggerFactory.getLogger(JimpleRewriter.class);
            val urls = new ArrayList<URL>();
            for (val p : javaClassPath.split(":")) {
                File clsFile = new File(p);
                try {
                    urls.add(clsFile.toURL());
                } catch (MalformedURLException e) {
                    throw new RuntimeException(e);
                }
            }
            this.loader = new URLClassLoader(urls.toArray(new URL[] {}));
            this.unloadedClasses = new HashSet<>();
            this.statManager = statManager;
            this.getSensitiveStringMethod = getSensitiveStringMethod;
            this.mode = mode;
        }

        static String getClassBase(InvokeExpr expr, Unit u, UnitGraph g) {
            if (!(expr instanceof VirtualInvokeExpr)) {
                return null;
            }
            VirtualInvokeExpr vexpr = (VirtualInvokeExpr) expr;
            Value value = vexpr.getBase();
            if (value instanceof ClassConstant) {
                return ((ClassConstant) value).value;
            }
            LocalDefs localDefs = LocalDefs.Factory.newLocalDefs(g);
            if (value instanceof Local) {
                Local local = (Local) value;
                List<Unit> defsOfUse = localDefs.getDefsOfAt(local, u);
                if (defsOfUse.size() == 1) {
                    DefinitionStmt defStmt = (DefinitionStmt) defsOfUse.get(0);
                    Value rhs = defStmt.getRightOp();
                    if (rhs instanceof ClassConstant) {
                        return ((ClassConstant) rhs).value;
                    }
                }
            }
            return null;
        }


        static MethodConstant getMethodBase(InvokeExpr expr, Unit u, UnitGraph g) {
            if (!(expr instanceof VirtualInvokeExpr)) {
                return null;
            }
            VirtualInvokeExpr vexpr = (VirtualInvokeExpr) expr;
            Value value = vexpr.getBase();
            if (value instanceof MethodConstant) {
                return ((MethodConstant) value);
            }
            LocalDefs localDefs = LocalDefs.Factory.newLocalDefs(g);
            if (value instanceof Local) {
                Local local = (Local) value;
                List<Unit> defsOfUse = localDefs.getDefsOfAt(local, u);
                if (defsOfUse.size() == 1) {
                    DefinitionStmt defStmt = (DefinitionStmt) defsOfUse.get(0);
                    Value rhs = defStmt.getRightOp();
                    if (rhs instanceof MethodConstant) {
                        return ((MethodConstant) rhs);
                    }
                }
            }
            return null;
        }

        static Value[] analyzeParams(Value params, Unit u, UnitGraph g) {
            LocalDefs localDefs = LocalDefs.Factory.newLocalDefs(g);
            LocalUses localUses = LocalUses.Factory.newLocalUses(g);
            if (params instanceof Local) {
                Local local = (Local) params;
                List<Unit> defsOfUse = localDefs.getDefsOfAt(local, u);
                if (defsOfUse.size() == 1) {
                    DefinitionStmt defStmt = (DefinitionStmt) defsOfUse.get(0);
                    NewArrayExpr rhs = (NewArrayExpr) defStmt.getRightOp();
                    if (rhs.getSize() instanceof IntConstant) {
                        int size = ((IntConstant)rhs.getSize()).value;
                        Value[] paramVals = new Value[size];
                        if (size > 0) {
                            for (UnitValueBoxPair p : localUses.getUsesOf(defsOfUse.get(0))) {
                                Stmt s = (Stmt) p.getUnit();
                                if (s instanceof AssignStmt) {
                                    AssignStmt as = (AssignStmt) s;
                                    if (as.getLeftOp() instanceof ArrayRef) {
                                        ArrayRef ref = (ArrayRef) as.getLeftOp();
                                        int index = ((IntConstant)ref.getIndex()).value;
                                        paramVals[index] = as.getRightOp();
                                    }
                                }
                            }
                        }
                        for (Value p : paramVals) {
                            if (p == null) {
                                throw new RuntimeException("");
                            }
                        }
                        return paramVals;
                    }
                }
            }
            return null;
        }

        static String getConstStringArgument(InvokeExpr expr, int index) {
            Value firstArgVal = expr.getArg(index);
            if (!(firstArgVal instanceof StringConstant)) {
                return null;
            }
            return ((StringConstant) firstArgVal).value;
        }

        void decrypt(String className, String methodName, InvokeExpr expr, AssignStmt aStmt) {
            if (!(expr instanceof StaticInvokeExpr)) {
                return;
            }
            if (expr.getArgCount() != 1) {
                return;
            }
            statManager.COUNT(INVOKE_APP_STATIC_METHOD);
            String argument = getConstStringArgument(expr, 0);
            if (argument == null) {
                return;
            }
            className = className.replace("/", ".");
            Class cls;
            Method m;
            try {
                cls = loader.loadClass(className);
            } catch (ClassNotFoundException e) {
//                e.printStackTrace();
                return;
            }
            try {
                m = cls.getMethod(methodName, String.class);
            } catch (NoSuchMethodException | NoClassDefFoundError e) {
                try {
                    // TODO: unzip android-28 jar and cherry-pick some class definitions to avoid errors
                    //       about android.content.Context
                    m = cls.getDeclaredMethod(methodName, String.class);
                } catch (NoSuchMethodException | NoClassDefFoundError ex) {
                    ex.printStackTrace();
                    return;
                }
            }

            if (!(m.getReturnType().getTypeName().equals("java.lang.String"))) {
                // FIXME: support indirect decryption using byte[]
                return;
            }
            try {
                String result = (String) m.invoke(null, argument);
                logger.info("decrypted: " + methodName + "(" + argument + ") => " + result);
                if (result != null) {
                    statManager.COUNT(INVOKE_APP_STATIC_METHOD_DECRYPTED);
                    aStmt.setRightOp(StringConstant.v(result));
                }
            } catch (IllegalAccessException ex) {
                ex.printStackTrace();
            } catch (InvocationTargetException ex) {
                ex.printStackTrace();
                ex.getTargetException().printStackTrace();
            }
        }

        void unrollForName(String className, String methodName, InvokeExpr expr, AssignStmt aStmt) {
            if (!(expr instanceof StaticInvokeExpr)) {
                return;
            }
            if (className.equals("java.lang.Class") && methodName.equals("forName")) {
                statManager.COUNT(CLASS_FORNAME);
                String argument = getConstStringArgument(expr, 0);
                if (argument == null) {
                    return;
                }
                logger.info("unfold forName: " + argument);
                statManager.COUNT(CLASS_FORNAME_UNREFLECT_OK);
                aStmt.setRightOp(ClassConstant.v(Util.toJNISig(argument)));
            }
        }

        Stmt unrollGetMethod(String className, String methodName, InvokeExpr expr, AssignStmt aStmt, Unit u, UnitGraph g) {
            if (!(expr instanceof VirtualInvokeExpr)) {
                return null;
            }
            if (methodName.equals("getMethod")) {
                statManager.COUNT(CLASS_GETMETHOD);
                String cls = getClassBase(expr, u, g);
                if (expr.getArgCount() != 1) {
                    return null;
                }
                String str = getConstStringArgument(expr, 0);
                if (cls == null || str == null) {
                    return null;
                }
                logger.info("unfold getMethod: " + cls + "." + str);
                statManager.COUNT(CLASS_GETMETHOD_UNREFLECT_OK);
                return Jimple.v().newAssignStmt(aStmt.getLeftOp(), new MethodConstant(cls, str));
            }
            return null;
        }

        void unrollInvoke(String className, String methodName, InvokeExpr expr, Stmt stmt, Unit u, UnitGraph g, PatchingChain<Unit> units, Body b) {
            if (className.equals("java.lang.reflect.Method") && methodName.equals("invoke")) {
                statManager.COUNT(METHOD_INVOKE);
                AssignStmt aStmt = (AssignStmt) stmt;
                VirtualInvokeExpr vexpr = (VirtualInvokeExpr) expr;
                MethodConstant methConst = getMethodBase(expr, u, g);
                if (methConst == null) {
                    return;
                }
                try {
                    Value target = vexpr.getArg(0);
                    Value params = vexpr.getArg(1); // we only need to know its length?
                    Value[] paramVals = analyzeParams(params, u, g);
                    SootClass cls = Scene.v().loadClassAndSupport(methConst.getClassName());
                    if (cls == null) {
                        logger.warn("Null class " + cls);
                    }
                    while (cls != null) {
                        logger.info("Searching class " + cls + "(" + cls.getMethodCount() + ")");
                        for (SootMethod meth : cls.getMethods()) {
                            if (!meth.getName().equals(methConst.getMethodName())) {
                                continue;
                            }
                            if (meth.getParameterCount() != paramVals.length) {
                                logger.warn("Unmatch: " + meth.getParameterCount() + " != " + paramVals.length);
                                continue;
                            }
                            if (paramVals != null && meth.makeRef() != null) {
                                logger.info("unfold invoke: " + methConst);
                                statManager.COUNT(METHOD_INVOKE_UNREFLECT_OK);
                                Value rightOp = null;
                                if (target instanceof NullConstant) {
                                    rightOp = Jimple.v().newStaticInvokeExpr(meth.makeRef(), Arrays.asList(paramVals));
                                }
                                if (cls.isInterface()) {
                                    rightOp = Jimple.v().newInterfaceInvokeExpr((Local) target, meth.makeRef(), Arrays.asList(paramVals));
                                }
                                if (rightOp == null) {
                                    rightOp = Jimple.v().newVirtualInvokeExpr((Local) target, meth.makeRef(), Arrays.asList(paramVals));
                                }
                                if (meth.getReturnType() instanceof PrimType) {
                                    String boxName = null;
                                    String castMethod = null;
                                    if (meth.getReturnType() instanceof IntType) {
                                        boxName = "java.lang.Integer";
                                        castMethod = "java.lang.Integer valueOf(int)";
                                    }
                                    if (meth.getReturnType() instanceof BooleanType) {
                                        boxName = "java.lang.Boolean";
                                        castMethod = "java.lang.Boolean valueOf(boolean)";
                                    }
                                    Local l = Jimple.v().newLocal("i", IntType.v());
                                    b.getLocals().add(l);
                                    SootClass intClass = Scene.v().loadClass(boxName, SootClass.SIGNATURES);
                                    SootMethod cons = intClass.getMethod(castMethod);
                                    List<Unit> toInsert = new ArrayList<>();
                                    toInsert.add(Jimple.v().newAssignStmt(l, rightOp));
                                    toInsert.add(Jimple.v().newAssignStmt(aStmt.getLeftOp(), Jimple.v().newStaticInvokeExpr(cons.makeRef(), l)));
                                    units.insertAfter(toInsert, u);
                                    return;
                                }
                                units.insertAfter(Jimple.v().newAssignStmt(aStmt.getLeftOp(), rightOp), u);
                                return;
                            } else {
                                logger.warn("Some null: " + paramVals + " or " + meth.makeRef());
                            }
                        }
                        cls = cls.getSuperclassUnsafe();
                    }
                    logger.warn("can't find good " + methConst);
                    unloadedClasses.add(methConst.getClassName());
                } catch (Exception e) {
                    logger.warn("can't load " + methConst);
                    logger.warn(e.toString());
                    unloadedClasses.add(methConst.getClassName());
                }
            }
            return;
        }

        enum PHASE {
            DECRYPT,
            UNROLLFORNAME,
            UNROLLGETMETHOD,
            UNROLLINVOKE
        }

        @Override
        protected void internalTransform(final Body b, String phaseName, @SuppressWarnings("rawtypes") Map options) {
            final PatchingChain units = b.getUnits();

            for (PHASE phase : PHASE.values()) {
                ConstantPropagatorAndFolder folder = new ConstantPropagatorAndFolder();
                folder.internalTransform(b, phaseName, options);
                UnitGraph g = new ExceptionalUnitGraph(b);
                for (Iterator<Unit> iter = units.snapshotIterator(); iter.hasNext();) {
                    final Unit u = iter.next();
                    Stmt stmt = (Stmt) u;
                    if (mode.equals("sensitiveRewrite")) {
                        for (ValueBox value : stmt.getUseBoxes()) {
                            if (value.getValue() instanceof StringConstant) {
                                StringConstant scons = (StringConstant) value.getValue();
                                if (scons.value.contains("http")) {
                                    System.out.println("constant " + scons.value);
                                    Local l = Jimple.v().newLocal("i", IntType.v());
                                    b.getLocals().add(l);
                                    Unit toInsert = Jimple.v().newAssignStmt(l, Jimple.v().newStaticInvokeExpr(getSensitiveStringMethod.makeRef(), new ArrayList<>()));
                                    units.insertBefore(toInsert, u);
                                    value.setValue(l);
                                }
                            }
                        }
                    } else {
                        if (!stmt.containsInvokeExpr()) {
                            continue;
                        }
                        InvokeExpr expr = stmt.getInvokeExpr();
                        if (stmt instanceof JInvokeStmt) {
                            continue;
                        }
                        SootMethod meth = expr.getMethod();
                        SootClass sootClass = meth.getDeclaringClass();
                        String className = sootClass.getName();
                        String methodName = meth.getName();
                        AssignStmt aStmt = (AssignStmt) stmt;

                        Stmt newStmt = null;
                        switch (phase) {
                            case DECRYPT: decrypt(className, methodName, expr, aStmt); break;
                            case UNROLLFORNAME: unrollForName(className, methodName, expr, aStmt); break;
                            case UNROLLGETMETHOD:
                                newStmt = unrollGetMethod(className, methodName, expr, aStmt, u, g);
                                if (newStmt != null) {
                                    units.insertAfter(newStmt, u);
                                }
                                break;
                            case UNROLLINVOKE:
                                unrollInvoke(className, methodName, expr, stmt, u, g, units, b);
                                break;
                        }
                    }
                }
                b.validate();
            }

            // Remove stmt like
            for (Iterator<Unit> iter = units.snapshotIterator(); iter.hasNext();) {
                final Unit u = iter.next();
                for (ValueBox box : u.getUseBoxes()) {
                    if (box.getValue() instanceof MethodConstant) {
                        units.remove(u);
                        break;
                    }
                }
            }
            b.validate();
        }
    }

    /**
     * Rewrite APK on apkPath and output to newApkPaths
     * @param javaClassPath path to java classes. Used for dynamic evaluation
     * @param classSigsFilePath path to a file containing the classes to load by soot manually.
     *                          FIXME: this is mostly a workaround
     */
    public static void Main(String androidPlatforms, String javaClassPath, String apkPath,
                            String classSigsFilePath, String newApkPath, String mode, StatManager statManager,
                            ProcessManifest manifest) throws IOException {
        SootOptionManager.Manager().buildOptionJimpleRewriter(androidPlatforms, apkPath, manifest);

        File newApkFile = new File(newApkPath);
        Options.v().set_output_dir(newApkFile.getParentFile().getAbsolutePath());

        SourceLines sl = new Util.SourceLines(classSigsFilePath);
        for (String className : sl.getLines()) {
            if (className.trim().length() == 0) {
                continue;
            }
            Scene.v().addBasicClass(className, SootClass.SIGNATURES);
        }

        SootClass sensitiveStringClass = new SootClass("SensitiveString");
        SootMethod getSensitiveStringMethod = new SootMethod("getSensitiveString", new ArrayList<>(), RefType.v("java.lang.String"), Modifier.STATIC | Modifier.PUBLIC);
        sensitiveStringClass.addMethod(getSensitiveStringMethod);
        Scene.v().addClass(sensitiveStringClass);

        DeobfuscateTransformer transformer = new DeobfuscateTransformer(
                javaClassPath + ":" + Options.v().soot_classpath(),
                statManager, getSensitiveStringMethod, mode);
        PackManager.v().getPack("jtp").add(new Transform("jtp.myInstrumenter", transformer));

        SootOptionManager.Manager().sootRunPacks();
        if (transformer.unloadedClasses.size() > 0) {
            System.out.println("======================= add following text to " + classSigsFilePath + " ===================");
            for (String className : transformer.unloadedClasses) {
                System.out.println(className);
            }
            System.out.println("================================================================================");
        }

        if (apkPath.endsWith(".dex")) {
            Files.move(Paths.get(apkPath + ".out/classes.dex"), Paths.get(newApkPath), REPLACE_EXISTING);
        }
    }
}