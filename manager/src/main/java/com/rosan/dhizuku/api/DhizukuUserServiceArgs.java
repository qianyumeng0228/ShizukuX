package com.rosan.dhizuku.api;

import android.content.ComponentName;
import android.os.Build;
import android.os.Bundle;

/**
 * Server-side mirror of Dhizuku-API's client class. The client serializes a Bundle with
 * the component under {@link #PARAM_COMPONENT}; we only need to read it back and echo
 * the same bundle to the connection callback.
 */
public class DhizukuUserServiceArgs {
    /** Same value as DhizukuVariables.PARAM_COMPONENT upstream. */
    public static final String PARAM_COMPONENT = "component";

    private final Bundle bundle;

    public DhizukuUserServiceArgs(DhizukuUserServiceArgs args) {
        this(args.bundle);
    }

    public DhizukuUserServiceArgs(ComponentName name) {
        bundle = new Bundle();
        setComponentName(name);
    }

    public DhizukuUserServiceArgs(Bundle bundle) {
        // defensive copy: the caller keeps ownership of the original bundle
        this.bundle = new Bundle(bundle);
    }

    public DhizukuUserServiceArgs setComponentName(ComponentName name) {
        bundle.putParcelable(PARAM_COMPONENT, name);
        return this;
    }

    public ComponentName getComponentName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return bundle.getParcelable(PARAM_COMPONENT, ComponentName.class);
        return bundle.getParcelable(PARAM_COMPONENT);
    }

    public Bundle build() {
        Bundle bundle = new Bundle();
        bundle.putAll(this.bundle);
        return bundle;
    }

    public String flattenToShortString() {
        ComponentName component = getComponentName();
        return component == null ? "null" : component.flattenToShortString();
    }
}
