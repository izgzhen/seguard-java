package edu.washington.cs.seguard;

import java.util.*;

import lombok.val;
import soot.Body;
import soot.RefType;
import soot.SootClass;
import soot.SootField;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.AssignStmt;
import soot.jimple.FieldRef;
import soot.jimple.Stmt;
import soot.jimple.StringConstant;

import java.io.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Util {
    public enum Lang {
        JAVA,
        JS
    }

    private static Util instance = null;


    private static Logger logger = LoggerFactory.getLogger(SourceSinkManager.class);

    static public class SourceLines {
        private String[] lines;

        public SourceLines(String filename) throws IOException {
            ArrayList<String> lines = new ArrayList<>();
            BufferedReader br = new BufferedReader(new FileReader(filename));
            String line;
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }
            br.close();
            this.lines = lines.toArray(new String[0]);
        }

        public String[] getLines() {
            return lines;
        }
    }

    public static List<String> readLines(String filename) throws IOException {
        ArrayList<String> lines = new ArrayList<>();
        BufferedReader br = new BufferedReader(new FileReader(filename));
        String line;
        while ((line = br.readLine()) != null) {
            lines.add(line);
        }
        br.close();
        return lines;
    }

    public static void writeLines(String filename, Collection<String> lines) throws IOException {
        val br = new BufferedWriter(new FileWriter(filename));
        for (val line : lines) {
            br.write(line + "\n");
        }
        br.close();
    }

    public static class SimpleTimer {
        private long startNanoSeconds;
        public SimpleTimer() {
            startNanoSeconds = System.nanoTime();
        }
        public double secondsElapsed() {
            return (System.nanoTime() - startNanoSeconds) * 10^(-9);
        }
    }

    public static Map<String, String> getStaticStrings(SootClass cls) {
        ArrayList<String> fieldNames = new ArrayList<>();

        for (SootField f : cls.getFields()) {
            if (f.isStatic() && f.getType().equals(RefType.v("java.lang.String"))) {
                fieldNames.add(f.getName());
            }
        }

        Map<String, String> constantStrings = new TreeMap<>();
        SootMethod clinit = cls.getMethodByNameUnsafe("<clinit>");
        if (clinit == null) {
            return constantStrings;
        }
        Body body = clinit.retrieveActiveBody();
        for (Unit u : body.getUnits()) {
            Stmt s = (Stmt) u;
            if (s.containsFieldRef() && s instanceof AssignStmt) {
                AssignStmt as = (AssignStmt) s;
                if (!(as.getLeftOp() instanceof FieldRef)) {
                    continue;
                }
                FieldRef fRef = (FieldRef) as.getLeftOp();
                String fieldName = fRef.getField().getName();
                if (!fieldNames.contains(fieldName)) {
                    continue;
                }
                if (!(as.getRightOp() instanceof StringConstant)) {
                    continue;
                }
                StringConstant constant = (StringConstant) as.getRightOp();
                constantStrings.put(fieldName, constant.value);
            }
        }
        return constantStrings;
    }

    public static String toJNISig(String sig) {
        return "L" + sig.replace(".", "/") + ";";
    }

    public static Util v() {
        if (instance == null) {
            instance = new Util();
        }
        return instance;
    }

    public static String fixedDotStr(String s) {
        if (s.trim().equals("") || s.trim().equals("%")) {
            return null;
        }
        return s.replaceAll("[^a-zA-Z\\d\\s:]", "#");
    }
}
