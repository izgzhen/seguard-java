package edu.washington.cs.seguard

object NodeType extends Enumeration {
    val METHOD,
    EXPR,
    SENSITIVE_PARENT,
    SENSITIVE_METHOD,
    STMT,
    STATIC_STRING,
    LIBRARY,
    merged,
    CONST_STRING,
    CONST_INT,
    CONSTANT,
    entrypoint = Value
}

object EdgeType extends Enumeration {
    val FROM_SENSITIVE_PARENT_TO_SENSITIVE_API,
    CALL,
    METHOD_BODY_CONTAINS,
    DATAFLOW,
    FROM_CLINIT_TO_ATTRIBUTE,
    USE_CONST_STRING,
    DOMINATE,
    DEP = Value
}

object SeGuardNodeAttr extends Enumeration with Serializable {
    type SeGuardNodeAttr = Value
    val DOMAIN = Value("domain")
    val TYPE = Value("type")
}

object SeGuardEdgeAttr extends Enumeration with Serializable {
    type SeGuardEdgeAttr = Value
    val TYPE = Value("type")
}
