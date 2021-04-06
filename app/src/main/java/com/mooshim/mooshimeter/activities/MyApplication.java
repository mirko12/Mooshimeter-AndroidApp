package com.mooshim.mooshimeter.activities;

/**
 * Created by First on 2/3/2015.
 */

import android.app.Application;

import com.mooshim.mooshimeter.common.Util;

//import io.fabric.sdk.android.Fabric;

//import com.crashlytics.android.Crashlytics;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        //Fabric.with(this, new Crashlytics());
        Util.init(this);
    }
}



