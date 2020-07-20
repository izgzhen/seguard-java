package edu.washington.cs.seguard.core;

import edu.washington.cs.seguard.Abstraction;
import edu.washington.cs.seguard.Conditions;
import edu.washington.cs.seguard.Config;
import heros.DefaultSeeds;
import heros.FlowFunction;
import heros.FlowFunctions;
import heros.InterproceduralCFG;
import heros.flowfunc.Identity;
import heros.flowfunc.KillAll;

import java.util.*;

import lombok.val;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.jimple.internal.JimpleLocal;
import soot.jimple.toolkits.ide.DefaultJimpleIFDSTabulationProblem;
import soot.toolkits.scalar.Pair;


public class DataFlowAnalysis
        extends DefaultJimpleIFDSTabulationProblem<Pair<Value, Set<Abstraction>>, InterproceduralCFG<Unit, SootMethod>> {
    private final Conditions conditions;
    private final Map<Unit, Map<Value, Set<Abstraction>>> unitAbstractionMap = new TreeMap<>(Comparator.comparing(Object::toString));
    private final Map<Unit, Map<Value, Set<Abstraction>>> unitAbstractionAfterMap = new TreeMap<>(Comparator.comparing(Object::toString));
    private final Set<SootMethod> visitedMethods = new TreeSet<>(Comparator.comparing(Object::toString));
    private final Config config;
    private Logger logger = LoggerFactory.getLogger(DataFlowAnalysis.class);

    DataFlowAnalysis(InterproceduralCFG<Unit, SootMethod> icfg, Conditions conditions, Config config) {
        super(icfg);
        logger.info("Init DataFlowAnalysis");
        this.config = config;
        this.conditions = conditions;
    }

    private void putUnitAbstractions(Unit u, Value value, Set<Abstraction> abstractions) {
        unitAbstractionMap.computeIfAbsent(u, unit -> new TreeMap<>(Comparator.comparing(Object::toString))).computeIfAbsent(
                value, v -> new TreeSet<>(Comparator.comparing(Object::toString))).addAll(abstractions);
        visitedMethods.add(interproceduralCFG().getMethodOf(u));
    }

    private void putUnitAbstractionsAfter(Unit u, Set<Pair<Value, Set<Abstraction>>> flow) {
        for (val pair : flow) {
            unitAbstractionAfterMap.computeIfAbsent(u, unit -> new TreeMap<>(Comparator.comparing(Object::toString))).computeIfAbsent(
                    pair.getO1(), v -> new TreeSet<>(Comparator.comparing(Object::toString))).addAll(pair.getO2());
        }
        visitedMethods.add(interproceduralCFG().getMethodOf(u));
    }

    Map<Unit, Map<Value, Set<Abstraction>>> getUnitAbstractionAfterMap() {
        return unitAbstractionAfterMap;
    }

    Map<Unit, Map<Value, Set<Abstraction>>> getUnitAbstractionMap() {
        return unitAbstractionMap;
    }

    Set<SootMethod> getVisitedMethods() {
        return visitedMethods;
    }


    private Set<Abstraction> getInterestingConstantAbstraction(Config config, Value expr) {
        Set<Abstraction> constants = new HashSet<>();
        Set<Value> values = new HashSet<>(Collections.singleton(expr));
        for (ValueBox box : expr.getUseBoxes()) {
            values.add(box.getValue());
        }
        for (Value value : values) {
            if (value instanceof StringConstant) {
                constants.add(Abstraction.v(config, ((StringConstant) value).value));
            }
            if (value instanceof IntConstant) {
                constants.add(Abstraction.v((IntConstant) value));
            }
            // FIXME: this is a hack
            if (value.toString().contains("android.net.Uri CONTENT_URI")) {
                constants.add(Abstraction.v(config, "CONTENT_URI"));
            }
        }
        return constants;
    }

    @Override
    public FlowFunctions<Unit, Pair<Value, Set<Abstraction>>, SootMethod> createFlowFunctionsFactory() {
        return new FlowFunctions<Unit, Pair<Value, Set<Abstraction>>, SootMethod>() {

            @Override
            public FlowFunction<Pair<Value, Set<Abstraction>>> getNormalFlowFunction(final Unit curr, Unit successor) {
                if (curr instanceof DefinitionStmt) {
                    final DefinitionStmt assignment = (DefinitionStmt) curr;

                    return source -> {
                        Set<Pair<Value, Set<Abstraction>>> newFlow = new LinkedHashSet<>();

                        if (!source.equals(zeroValue())) {
                            putUnitAbstractions(curr, source.getO1(), source.getO2());

                            // propagate the flow
                            // (1) curr is a definition that kills previous definitions to source's LHS
                            if (source.getO1().equivTo(assignment.getLeftOp())) {
                                return Collections.emptySet();
                            }
                            // (2) doesn't kill, propagate
                            if (source.getO1().equivTo(assignment.getRightOp())) {
                                return Collections.singleton(new Pair<>(assignment.getLeftOp(), source.getO2()));
                            }
                            newFlow.add(source);
                            putUnitAbstractionsAfter(curr, newFlow);
                            return newFlow;
                        } else {
                            // generate new flow
                            val constants = getInterestingConstantAbstraction(config, assignment.getRightOp());
                            if (constants.size() > 0) {
                                return Collections.singleton(new Pair<>(assignment.getLeftOp(), constants));
                            }
                            return Collections.emptySet();
                        }
                    };
                }

                // identity function
                return Identity.v();
            }

            @Override
            public FlowFunction<Pair<Value, Set<Abstraction>>> getCallFlowFunction(Unit callStmt, final SootMethod destinationMethod) {
                Stmt stmt = (Stmt) callStmt;
                InvokeExpr invokeExpr = stmt.getInvokeExpr();
                final List<Value> args = invokeExpr.getArgs();

                return source -> {
                    LinkedHashSet<Pair<Value, Set<Abstraction>>> newFlow = new LinkedHashSet<>();
                    if (destinationMethod.getName().equals("<clinit>")
                            || destinationMethod.getSubSignature().equals("void run()")
                            || conditions.blacklisted(destinationMethod)) {
                        return Collections.singleton(source);
                    }
                    if (source != zeroValue()) {
                        putUnitAbstractions(callStmt, source.getO1(), source.getO2());

                        // passing the definition to a local variable to the passed in the arguments in the function body

                        if (args.contains(source.getO1())) {
                            int paramIndex = args.indexOf(source.getO1());
                            Pair<Value, Set<Abstraction>> pair = new Pair<>(
                                    new EquivalentValue(
                                            Jimple.v().newParameterRef(destinationMethod.getParameterType(paramIndex), paramIndex)),
                                    source.getO2());
                            newFlow.add(pair);
                        }
                    } else {
                        for (val arg : args) {
                            if (! (arg instanceof Local)) {
                                val constants = getInterestingConstantAbstraction(config, arg);
                                if (constants.size() > 0) {
                                    int paramIndex = args.indexOf(arg);
                                    Pair<Value, Set<Abstraction>> pair = new Pair<>(
                                            new EquivalentValue(
                                                    Jimple.v().newParameterRef(destinationMethod.getParameterType(paramIndex), paramIndex)),
                                            constants);
                                    newFlow.add(pair);
                                }
                            }
                        }
                    }
                    putUnitAbstractionsAfter(callStmt, newFlow);
                    return newFlow;
                };
            }


            @Override
            public FlowFunction<Pair<Value, Set<Abstraction>>> getReturnFlowFunction(final Unit callSite,
                                                                                     SootMethod calleeMethod, final Unit exitStmt, Unit returnSite) {
                if (!(callSite instanceof DefinitionStmt)) {
                    // return value is not stored back
                    return KillAll.v();
                }

                if (exitStmt instanceof ReturnVoidStmt) {
                    // no return value at all
                    return KillAll.v();
                }

                return source -> {
                    if (source != zeroValue()) {
                        putUnitAbstractions(exitStmt, source.getO1(), source.getO2());
                    }
                    if (exitStmt instanceof ReturnStmt) {
                        ReturnStmt returnStmt = (ReturnStmt) exitStmt;
                        if (returnStmt.getOp().equivTo(source.getO1())) {
                            DefinitionStmt definitionStmt = (DefinitionStmt) callSite;
                            Pair<Value, Set<Abstraction>> pair
                                    = new Pair<>(definitionStmt.getLeftOp(), source.getO2());
                            putUnitAbstractionsAfter(exitStmt, Collections.singleton(pair));
                            return Collections.singleton(pair);
                        }
                    }
                    return Collections.emptySet();
                };
            }

            @Override
            public FlowFunction<Pair<Value, Set<Abstraction>>> getCallToReturnFlowFunction(Unit callSite, Unit returnSite) {
                // it is just that definition might reach from things not relevant to the current call, just like
                // normal function (empty one)
                if (!(callSite instanceof DefinitionStmt || callSite instanceof InvokeStmt)) {
                    return Identity.v();
                }
                Stmt callSiteStmt = (Stmt) callSite;
                InvokeExpr invokeExpr = callSiteStmt.getInvokeExpr();
                if (invokeExpr.getMethod().getDeclaringClass().isApplicationClass()) {
                    // FIXME: Will this be a bug?
                    return Identity.v();
                }

                return source -> {
                    Set<Pair<Value, Set<Abstraction>>> newFlow = new LinkedHashSet<>();

                    if (source != zeroValue()) {
                        putUnitAbstractions(callSite, source.getO1(), source.getO2());

                        // Propagate to base
                        if (((Stmt) callSite).getInvokeExpr() instanceof InstanceInvokeExpr) {
                            InstanceInvokeExpr expr = (InstanceInvokeExpr) ((Stmt) callSite).getInvokeExpr();
                            if (!conditions.blacklisted(expr.getMethod())) {
                                newFlow.addAll(Propagator.getTainted(config, expr.getUseBoxes(), expr.getBase(), source.getO1(), source.getO2()));
                            }
                        }

                        // Propagate to the left hand side
                        if (callSite instanceof DefinitionStmt) {
                            final DefinitionStmt definitionStmt = (DefinitionStmt) callSite;
                            newFlow.addAll(Propagator.getTainted(config, definitionStmt.getUseBoxes(), definitionStmt.getLeftOp(), source.getO1(), source.getO2()));
                        }
                    } else {
                        if (callSite instanceof DefinitionStmt) {
                            final DefinitionStmt assignment = (DefinitionStmt) callSite;
                            val constants = getInterestingConstantAbstraction(config, assignment.getRightOp());
                            if (constants.size() > 0) {
                                newFlow.add(new Pair<>(assignment.getLeftOp(), constants));
                            }

                            if (conditions.isSensitiveMethod(invokeExpr.getMethod())) {
                                newFlow.add(new Pair<>(assignment.getLeftOp(), Collections.singleton(Abstraction.v(invokeExpr.getMethod()))));
                            }
                        }
                    }
                    newFlow.add(source);
                    putUnitAbstractionsAfter(callSite, newFlow);
                    return newFlow;
                };
            }
        };
    }


    public Map<Unit, Set<Pair<Value, Set<Abstraction>>>> initialSeeds() {
        List<Unit> units = new ArrayList<>();
        for (SootMethod m : Scene.v().getEntryPoints()) {
            if (m.hasActiveBody()) {
                units.add(m.getActiveBody().getUnits().getFirst());
            }
        }
        return DefaultSeeds.make(units, zeroValue());
    }

    public Pair<Value, Set<Abstraction>> createZeroValue() {
        return new Pair<>(new JimpleLocal("<<zero>>", NullType.v()),
                Collections.emptySet());
    }

}

