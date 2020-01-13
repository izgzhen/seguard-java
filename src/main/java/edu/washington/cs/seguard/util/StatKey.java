package edu.washington.cs.seguard.util;

public enum StatKey {
    // number of application static method that takes one argument
    INVOKE_APP_STATIC_METHOD,
    INVOKE_APP_STATIC_METHOD_DECRYPTED,
    // number of Class.forName invocations
    CLASS_FORNAME,
    CLASS_FORNAME_UNREFLECT_OK,
    // number of Class.getMethod invocations
    CLASS_GETMETHOD,
    CLASS_GETMETHOD_UNREFLECT_OK,
    // number of Method.inoke invocations
    METHOD_INVOKE,
    METHOD_INVOKE_UNREFLECT_OK,

    BASIC_CLASSES,
    CLASSES,
    PHANTON_CLASSES,
    LIBRARY_CLASSES,
    ORIGINAL_NUM_ENTRYPOINTS,
    NEW_NUM_ENTRYPOINTS,
    CG_SIZE,
    MS_NUM
}