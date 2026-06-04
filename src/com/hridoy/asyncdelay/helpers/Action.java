package com.hridoy.asyncdelay.helpers;

import com.google.appinventor.components.common.OptionList;

import java.util.HashMap;
import java.util.Map;

public enum Action implements OptionList<String> {
    IntervalPause("INTERVAL_PAUSE"),
    IntervalResume("INTERVAL_RESUME"),
    IntervalCancel("INTERVAL_CANCEL"),
    DebounceCancel("DEBOUNCE_CANCEL"),
    ThrottleCancel("THROTTLE_CANCEL"),
    DelayCancel("DELAY_CANCEL");

    private final String action;
    Action(String action){
        this.action = action;
    }

    @Override
    public String toUnderlyingValue() {
        return action;
    }
    private static final Map<String, Action> lookup = new HashMap<>();
    static {
        for(Action action1 : Action.values()){
            lookup.put(action1.toUnderlyingValue(), action1);
        }
    }
    public static Action fromUnderlyingValue(String action1){
        return lookup.get(action1);
    }
}
