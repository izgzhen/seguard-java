package edu.washington.cs.seguard

object NodeType extends Enumeration {
    val METHOD,
    SENSITIVE_PARENT,
    SENSITIVE_METHOD,
    STMT,
    CONST_STRING,
    CONST_INT,
    CONSTANT = Value
}

object EdgeType extends Enumeration {
    val FROM_SENSITIVE_PARENT_TO_SENSITIVE_API, CALL, DATAFLOW, DOMINATE = Value
}

object SeGuardNodeAttr extends Enumeration with Serializable {
    type SeGuardNodeAttr = Value
    val DOMAIN = Value("domain")
    val TYPE = Value("type")
    val TAG = Value("tag")
}

object SeGuardEdgeAttr extends Enumeration with Serializable {
    type SeGuardEdgeAttr = Value
    val TYPE = Value("type")
    val TAG = Value("tag")
}