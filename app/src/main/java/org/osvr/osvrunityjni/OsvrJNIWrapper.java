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

    private static boolean sPaused = false;
    private static boolean sCameraEnabled = false;
    private static boolean sIsMissingNativeButtonFuncs = false; // native button jni funcs may not be loaded

    static SurfaceTexture sCameraTexture;
    static int sCameraPreviewWidth = -1;
    static int sCameraPreviewHeight = -1;
    static Camera sCamera;

    public static native void reportFrame(byte[] data, long width, long height);

    public static native void reportKeyDown(int keyCode); // might change the signature
    public static native void reportKeyUp(int keyUp); // might change the signature

    static Camera.PreviewCallback sPreviewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            //Log.d(TAG, "Got onPreviewFrame");
            if(!sPaused) {
                OsvrJNIWrapper.reportFrame(
                        data, sCameraPreviewWidth, sCameraPreviewHeight);
            }
        }
    };

    private static void setCameraParams() {
        Camera.Parameters parms = sCamera.getParameters();
        parms.setRecordingHint(true);
        parms.setVideoStabilization(false);
        parms.setPreviewSize(640, 480);
        Camera.Size size = parms.getPreviewSize();
        sCameraPreviewWidth = size.width;
        sCameraPreviewHeight = size.height;
        sCamera.setParameters(parms);

        int[] fpsRange = new int[2];
        Camera.Size mCameraPreviewSize = parms.getPreviewSize();
        parms.getPreviewFpsRange(fpsRange);
        String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
        if (fpsRange[0] == fpsRange[1]) {
            previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
        } else {
            previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                    " - " + (fpsRange[1] / 1000.0) + "] fps";
        }
       // Log.i(TAG, "Camera config: " + previewFacts);

        sCameraPreviewWidth = mCameraPreviewSize.width;
        sCameraPreviewHeight = mCameraPreviewSize.height;
    }

    private static void openCamera() {
        if(sCameraEnabled && sCamera == null) {
            sCameraTexture = new SurfaceTexture(123);
            sCamera = Camera.open();
            setCameraParams();
            try {
                sCamera.setPreviewTexture(sCameraTexture);
            } catch (IOException ex) {
                //Log.d(TAG, "Error on setPreviewTexture: " + ex.getMessage());
                throw new RuntimeException("error during setPreviewTexture");
            }
            //mCamera.setPreviewCallbackWithBuffer(this);
            sCamera.setPreviewCallback(sPreviewCallback);
            sCamera.startPreview();
        }
    }

    protected static void stopCamera() {
        if(sCamera != null) {
            sCamera.setPreviewCallback(null);
            sCamera.stopPreview();
            sCamera.release();
            sCamera = null;
        }
    }

    public static void enableCamera() {
        sCameraEnabled = true;
    }

    public static void disableCamera() {
        sCameraEnabled = false;
        stopCamera();
    }

    public static void onResume() {
        sPaused = false;
        openCamera();
    }

    public static void onStop() {
        sPaused = true;
        stopCamera();
    }

    public static void onPause() {
        sPaused = true;
        stopCamera();
    }

    public static boolean onKeyDown(int keyCode, KeyEvent keyEvent) {
        // The native code to push key down events might not be loaded,
        // we'll try to call it and gracefully recover if we fail.
        // After a failure, we'll stop trying
        if(sIsMissingNativeButtonFuncs) {
            return false;
        }
        try {
            reportKeyDown(keyCode);
            return true;
        } catch(Exception e) { // @TODO: narrow down this exception?
            sIsMissingNativeButtonFuncs = true;
            return false;
        }
    }

    public static boolean onKeyUp(int keyCode, KeyEvent keyEvent) {
        // The native code to push key down events might not be loaded,
        // we'll try to call it and gracefully recover if we fail.
        // After a failure, we'll stop trying
        if(sIsMissingNativeButtonFuncs) {
            return false;
        }
        try {
            reportKeyUp(keyCode);
            return true;
        } catch(Exception e) { // @TODO: narrow down this exception?
            sIsMissingNativeButtonFuncs = true;
            return false;
        }
    }

    public static void onSurfaceChanged() {
        openCamera();
    }

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
