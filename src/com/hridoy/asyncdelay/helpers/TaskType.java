package com.hridoy.asyncdelay.helpers;

import java.util.HashMap;
import java.util.Map;
import com.google.appinventor.components.common.OptionList;

public enum TaskType implements OptionList<String> {
    Intervals("INTERVALS"),
    Delays("DELAYS"),
    Debounces("DEBOUNCES"),
    Throttles("THROTTLES"),
    LockedGates("LOCKED_GATES");

    private final String value;

    TaskType(String value) {
        this.value = value;
    }

    @Override
    public String toUnderlyingValue() {
        return value;
    }

    private static final Map<String, TaskType> lookup = new HashMap<>();

    static {
        for (TaskType type : values()) {
            lookup.put(type.toUnderlyingValue(), type);
        }
    }

    public static TaskType fromUnderlyingValue(String value) {
        return lookup.get(value.toUpperCase());
    }
}