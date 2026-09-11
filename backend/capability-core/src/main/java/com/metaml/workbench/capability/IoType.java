package com.metaml.workbench.capability;

// Declared type of a capability input or output. UNKNOWN means the source did not declare a type -
// it is not a default and carries no business value. See CapabilitySatisfaction.typeCompatible for
// how UNKNOWN widens compatibility instead of narrowing it.
public enum IoType {
    BOOLEAN,
    NUMBER,
    STRING,
    STRUCT,
    UNKNOWN
}
