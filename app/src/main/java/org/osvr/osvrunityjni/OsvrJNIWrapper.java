/** @file
 @brief OSVR-Unity-JNI plugin adds OSVR RenderManager support to OSVR-Unity-Android.
 @date 2017
 @author Greg Aring
 Sensics, Inc.
 <http://sensics.com/osvr>
 */

// Copyright 2017 Sensics, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//        http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/// Both of these need to be enabled to force-enable logging to files.
package org.osvr.osvrunityjni;

import android.app.Activity;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import static android.opengl.EGL14.eglGetCurrentContext;
import static android.opengl.EGL14.eglGetCurrentDisplay;
import static android.opengl.EGL14.eglGetCurrentSurface;
import static android.opengl.EGL14.eglMakeCurrent;
import android.util.DisplayMetrics;
import java.lang.reflect.Field;

import com.osvr.common.jni.JNIBridge;
import com.osvr.common.jni.OSVRActivity;
import com.osvr.common.util.OSVRFileExtractor;

//import com.osvr.common.jni.JNIBridge;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.util.Log;
import android.view.KeyEvent;
import java.io.IOException;


public class OsvrJNIWrapper {
    private static long EGL_NO_DISPLAY_ERROR = 10;
    private static long EGL_INIT_ERROR = 11;
    private static long EGL_CONFIG_ERROR = 12;
    private static long EGL_CREATE_CONTEXT_ERROR = 13;

    public static EGLContext unityMainContext; //called from Unity rendering thread
    public static EGLContext mEglContext;
    public static EGLContext sharedContext;
    public static EGLDisplay mEglDisplay;
    public static EGLSurface mEglSurface;
    public static EGLConfig mEglConfig = null;
    private Class<?> unityPlayerClass;
    private Field unityCurrentActivity;
    private boolean librariesLoaded = false;
    private static boolean loggingEnabled = false;
   // private static JNIBridge bridge;

    //load OSVR libraries
    public void loadLibraries() {
        if(!librariesLoaded)
        {
            System.loadLibrary("gnustl_shared");
            System.loadLibrary("crystax");
            System.loadLibrary("jsoncpp");
            System.loadLibrary("usb1.0");
            System.loadLibrary("osvrUtil");
            System.loadLibrary("osvrCommon");
            System.loadLibrary("osvrClientKit");
            System.loadLibrary("osvrClient");
            System.loadLibrary("functionality");
            System.loadLibrary("osvrConnection");
            System.loadLibrary("osvrPluginHost");
            System.loadLibrary("osvrPluginKit");
            System.loadLibrary("osvrVRPNServer");
            System.loadLibrary("osvrServer");
            System.loadLibrary("osvrJointClientKit");
            System.loadLibrary("com_osvr_android_jniImaging");
            System.loadLibrary("com_osvr_android_sensorTracker");
            System.loadLibrary("org_osvr_android_moverio");
            System.loadLibrary("com_osvr_Multiserver");
            System.loadLibrary("org_osvr_filter_deadreckoningrotation");
            System.loadLibrary("org_osvr_filter_oneeuro");
            librariesLoaded = true;

        }

    }

    public void startCamera(){
        JNIBridge.enableCamera();
        JNIBridge.onSurfaceChanged();
    }
    public static void logMsg(String msg) {
        if (loggingEnabled) {
            android.util.Log.i("Unity", msg);
        }
    }

    //get the UnityPlayer class at startup
    public void init () {

        //Get the UnityPlayer class using Reflection
        try {
            unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        //Get the currentActivity field
        try {
            unityCurrentActivity= unityPlayerClass.getField("currentActivity");
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
    }

    //Use this method to get UnityPlayer.currentActivity
    public Activity currentActivity () {
        try {
            Activity activity = (Activity) unityCurrentActivity.get(unityPlayerClass);
            return activity;
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    //get the display width
    public static int getDisplayWidth(){
        DisplayMetrics displayMetrics = new DisplayMetrics();
        return displayMetrics.widthPixels;
    }

    //get the display height
    public static int getDisplayHeight(){
        DisplayMetrics displayMetrics = new DisplayMetrics();
        return displayMetrics.heightPixels;
    }

    //get the current rendering context
    public static long getCurrentContext(){
        EGLContext cont = eglGetCurrentContext();
        return cont.getNativeHandle();
    }

    //set the local unityMainContext to the current context
    public static long setUnityMainContext(){
        unityMainContext = eglGetCurrentContext();
        return unityMainContext.getNativeHandle();
    }

    //get the native handle to the Unity GL context
    public static long getUnityMainContext(){
        return unityMainContext.getNativeHandle();
    }

    //make the Unity context current
    public static void makeUnityMainContextCurrent()
    {
        eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, unityMainContext);
    }

    //make the toolkit context current
    public static void makeToolkitContextCurrent()
    {
        eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext);
    }

    //create a gl context for the opengl toolkit
    public static long createContext() {
        mEglDisplay = eglGetCurrentDisplay();
        mEglSurface = eglGetCurrentSurface(EGL14.EGL_DRAW);
        mEglConfig = null;
        if (mEglDisplay == null) {
            mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEglDisplay == EGL14.EGL_NO_DISPLAY) {
                // logMsg("eglGetDisplay");
                return EGL_NO_DISPLAY_ERROR;
            }

            int[] version = new int[2];
            if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
                mEglDisplay = null;
                //logMsg("eglInitialize");
                return EGL_INIT_ERROR;
            }
        }

        if (mEglConfig == null) {
            int[] eglConfigAttribList = new int[]{
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };
            int[] numEglConfigs = new int[1];
            EGLConfig[] eglConfigs = new EGLConfig[1];
            if (!EGL14.eglChooseConfig(mEglDisplay, eglConfigAttribList, 0,
                    eglConfigs, 0, eglConfigs.length, numEglConfigs, 0)) {
                //logMsg("eglChooseConfig");
                return EGL_CONFIG_ERROR;
            }
            mEglConfig = eglConfigs[0];
        }

        if (mEglContext == null) {
            int[] eglContextAttribList = new int[]{
                    EGL14.EGL_NONE
            };
            mEglContext = EGL14.eglCreateContext(mEglDisplay, mEglConfig,
                    sharedContext, eglContextAttribList, 0);
            if (mEglContext == null) {
                //logMsg("eglCreateContext");
                return EGL_CREATE_CONTEXT_ERROR;
            }
        }
        return mEglContext.getNativeHandle();
    }

}
