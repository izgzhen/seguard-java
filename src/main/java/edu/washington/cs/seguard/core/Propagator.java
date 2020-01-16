package edu.washington.cs.seguard.core;

import edu.washington.cs.seguard.Abstraction;
import edu.washington.cs.seguard.Config;
import soot.Value;
import soot.ValueBox;
import soot.jimple.IntConstant;
import soot.jimple.StringConstant;
import soot.toolkits.scalar.Pair;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

class Propagator {
    static Set<Pair<Value, Set<Abstraction>>> getTainted(Config config, List<ValueBox> fromValues, Value toValue, Value sourceValue, Set<Abstraction> sourceAbstractions) {
        Set<Pair<Value, Set<Abstraction>>> newFlow = new LinkedHashSet<>();
        if (toValue != null &&  fromValues != null) {
            Set<Abstraction> constants = new HashSet<>();
            for (ValueBox box : fromValues) {
                if (box.getValue() instanceof StringConstant) {
                    constants.add(Abstraction.v(config, ((StringConstant) box.getValue()).value));
                }
                if (box.getValue() instanceof IntConstant) {
                    constants.add(Abstraction.v((IntConstant) box.getValue()));
                }
                if (box.getValue().equals(sourceValue)) {
                    constants.addAll(sourceAbstractions);
                }
            }
            if (!toValue.equals(sourceValue)) {
                if (constants.size() > 0) {
                    newFlow.add(new Pair<>(toValue, constants));
                }
            }
        }

        return newFlow;
    }
}
