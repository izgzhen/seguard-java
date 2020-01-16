package edu.washington.cs.seguard;

import soot.SootClass;
import soot.SootMethod;

import java.io.IOException;

public class Conditions {
    private final SourceSinkManager manager;
    private final Config config;

    public Conditions(String sourceSinkFile, Config config) throws IOException {
        manager = new SourceSinkManager(sourceSinkFile);
        this.config = config;
    }

    private boolean isSensitiveMethodName(String name) {
        return config.sensitiveMethodNames.contains(name);
    }

    public boolean isDataflowMethod(SootMethod method) {
        return config.dataflowClassNames.contains(method.getClass().getName());
    }

    public boolean isSensitiveMethod(SootMethod method) {
        if (method.getDeclaringClass().isApplicationClass()) {
            return false;
        }
        if (manager.sources.contains(method.getSignature()) || manager.sinks.contains(method.getSignature())) {
            return true;
        }
        if (isSensitiveMethodName(method.getName())) {
            return true;
        }
        String packageName = method.getDeclaringClass().getPackageName();
        return isSensitivePackageName(packageName);
    }

    private boolean isSensitiveEntrypointClass(SootClass cls) {
        String name = cls.getName();
        for (String keyword : config.sensitiveEntrypointClassKeywords) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSensitivePackageName(String name) {
        for (String keyword : config.sensitivePackageNameKeywords) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public boolean isSensitiveEntrypointParent(SootClass cls) {
        if (isSensitiveEntrypointClass(cls)) {
            return true;
        }
        if (cls.isApplicationClass()) {
            return false;
        }
        if (cls.isJavaLibraryClass()) {
            return false;
        }
        // FIXME: generalize this
        return !cls.getPackageName().equals("android.media");
    }

    public SootClass getSensitiveParentClassWithMethod(SootClass baseClass, String methodSubSig) {
        if (!baseClass.hasSuperclass() && baseClass.getInterfaceCount() == 0) {
            return null;
        }
        if (baseClass.hasSuperclass()) {
            SootClass superClass = baseClass.getSuperclass();
            if (isSensitiveEntrypointParent(superClass)) {
                SootMethod m = superClass.getMethodUnsafe(methodSubSig);
                if (m != null) {
                    return superClass;
                }
            }
            superClass = getSensitiveParentClassWithMethod(superClass, methodSubSig);
            if (superClass != null) {
                return superClass;
            }
        }
        for (SootClass iface : baseClass.getInterfaces()) {
            if (isSensitiveEntrypointParent(iface)) {
                SootMethod m = iface.getMethodUnsafe(methodSubSig);
                if (m != null) {
                    return iface;
                }
            }
            SootClass ifaceRet = getSensitiveParentClassWithMethod(iface, methodSubSig);
            if (ifaceRet != null) {
                return ifaceRet;
            }
        }
        return null;
    }

    public boolean blacklisted(SootClass cls) {
        for (String blacklistedPackagePrefix : config.blacklistedPackagePrefixes) {
            if (cls.getPackageName().startsWith(blacklistedPackagePrefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean blacklisted(SootMethod method) {
        return blacklisted(method.getDeclaringClass());
    }
}
