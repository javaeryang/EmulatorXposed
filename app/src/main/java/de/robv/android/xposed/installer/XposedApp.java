package de.robv.android.xposed.installer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Application.ActivityLifecycleCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;

import de.robv.android.xposed.installer.installation.StatusInstallerFragment;
import de.robv.android.xposed.installer.util.AssetUtil;
import de.robv.android.xposed.installer.util.DownloadsUtil;
import de.robv.android.xposed.installer.util.InstallZipUtil;
import de.robv.android.xposed.installer.util.InstallZipUtil.XposedProp;
import de.robv.android.xposed.installer.util.NotificationUtil;
import de.robv.android.xposed.installer.util.RepoLoader;

public class XposedApp extends Application implements ActivityLifecycleCallbacks {
    public static final String TAG = "XposedInstaller";

    @SuppressLint("SdCardPath")
    private static final String BASE_DIR_LEGACY = "/data/data/de.robv.android.xposed.installer/";

    public static final String BASE_DIR = Build.VERSION.SDK_INT >= 24
            ? "/data/user_de/0/de.robv.android.xposed.installer/" : BASE_DIR_LEGACY;

    public static final String ENABLED_MODULES_LIST_FILE = XposedApp.BASE_DIR + "conf/enabled_modules.list";

    private static final String[] XPOSED_PROP_FILES = new String[]{
            "/su/xposed/xposed.prop", // official systemless
            "/system/xposed.prop",    // classical
    };

    public static int WRITE_EXTERNAL_PERMISSION = 69;
    private static XposedApp mInstance = null;
    private static Thread mUiThread;
    private static Handler mMainHandler;
    private boolean mIsUiLoaded = false;
    private SharedPreferences mPref;
    private XposedProp mXposedProp;

    public static XposedApp getInstance() {
        return mInstance;
    }

    public static void runOnUiThread(Runnable action) {
        if (Thread.currentThread() != mUiThread) {
            mMainHandler.post(action);
        } else {
            action.run();
        }
    }

    public static void postOnUiThread(Runnable action) {
        mMainHandler.post(action);
    }

    // This method is hooked by XposedBridge to return the current version
    public static int getActiveXposedVersion() {
        return -1;
    }

    public static int getInstalledXposedVersion() {
        XposedProp prop = getXposedProp();
        return prop != null ? prop.getVersionInt() : -1;
    }

    public static XposedProp getXposedProp() {
        synchronized (mInstance) {
            return mInstance.mXposedProp;
        }
    }

    public static SharedPreferences getPreferences() {
        return mInstance.mPref;
    }

    public static void installApk(Context context, DownloadsUtil.DownloadInfo info) {
        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri uri;
        if (Build.VERSION.SDK_INT >= 24) {
            uri = FileProvider.getUriForFile(context, "de.robv.android.xposed.installer.fileprovider", new File(info.localFilename));
            installIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(new File(info.localFilename));
        }
        installIntent.setDataAndType(uri, DownloadsUtil.MIME_TYPE_APK);
        installIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.getApplicationInfo().packageName);
        context.startActivity(installIntent);
    }

    public static String getDownloadPath() {
        return getPreferences().getString("download_location", Environment.getExternalStorageDirectory() + "/XposedInstaller");
    }

    public void onCreate() {
        super.onCreate();



        mInstance = this;

        //释放zip
        releaseZip(mInstance);

        mUiThread = Thread.currentThread();
        mMainHandler = new Handler();

        mPref = PreferenceManager.getDefaultSharedPreferences(this);
        reloadXposedProp();
        createDirectories();
        NotificationUtil.init();
        AssetUtil.removeBusybox();

        registerActivityLifecycleCallbacks(this);
    }

    private void releaseZip(final Context context){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    StatusInstallerFragment.log("current sdk "+Build.VERSION.SDK_INT);

                    AssetManager assetManager = context.getAssets();

                    String core_zip_name = "";

                    switch (Build.VERSION.SDK_INT){
                        case 21:
                            core_zip_name = "xposed-v89-sdk21-x86.zip";
                            break;
                        case 22:
                            core_zip_name = "xposed-v89-sdk22-x86.zip";
                            break;
                        case 23:
                            core_zip_name = "xposed-v89-sdk23-x86.zip";
                            break;
                        case 24:
                            core_zip_name = "xposed-v89-sdk24-x86.zip";
                            break;
                        case 25:
                            core_zip_name = "xposed-v89-sdk25-x86.zip";
                            break;
                        case 26:
                            core_zip_name = "xposed-v90-sdk26-x86-beta3.zip";
                            break;
                        case 27:
                            core_zip_name = "xposed-v90-sdk27-x86-beta3.zip";
                            break;
                    }
                    if (core_zip_name.isEmpty()){
                        postOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(context, "不支持的模拟器安卓版本:" + Build.VERSION.SDK_INT, Toast.LENGTH_LONG).show();
                            }
                        });
                        return;
                    }

                    InputStream is = assetManager.open(core_zip_name);
                    int len;
                    byte[] bytes = new byte[4096 * 10];

                    File dir = new File(new File(context.getCacheDir(), "downloads"), "framework");
                    if (!dir.exists()){
                        dir.mkdirs();
                        StatusInstallerFragment.log("dir===>mkdirs");
                    }

                    StatusInstallerFragment.log(dir.exists() + "===>dir===>"+dir);

                    File file = new File(dir, core_zip_name);

                    StatusInstallerFragment.log(file.exists() + "===>file===>"+file);

                    if (!file.exists() && file.length() < 1000){
                        file.createNewFile();
                    }else {
                        StatusInstallerFragment.log("不再释放文件");
                        return;
                    }
                    FileOutputStream fos = new FileOutputStream(file);
                    while ((len = is.read(bytes)) != -1){
                        fos.write(bytes, 0, len);
                    }
                    fos.flush();
                    fos.close();

                    Log.e("jaxposed", "释放===>suc===>" + file.getAbsolutePath());

                }catch (Throwable throwable){
                    Log.e("jaxposed", "释放===>fai===>"+throwable.toString());
                }
            }
        }).start();
    }

    private void createDirectories() {
        FileUtils.setPermissions(BASE_DIR, 00711, -1, -1);
        mkdirAndChmod("conf", 00771);
        mkdirAndChmod("log", 00777);

        if (Build.VERSION.SDK_INT >= 24) {
            try {
                Method deleteDir = FileUtils.class.getDeclaredMethod("deleteContentsAndDir", File.class);
                deleteDir.invoke(null, new File(BASE_DIR_LEGACY, "bin"));
                deleteDir.invoke(null, new File(BASE_DIR_LEGACY, "conf"));
                deleteDir.invoke(null, new File(BASE_DIR_LEGACY, "log"));
            } catch (ReflectiveOperationException e) {
                Log.w(XposedApp.TAG, "Failed to delete obsolete directories", e);
            }
        }
    }

    private void mkdirAndChmod(String dir, int permissions) {
        dir = BASE_DIR + dir;
        new File(dir).mkdir();
        FileUtils.setPermissions(dir, permissions, -1, -1);
    }

    public void reloadXposedProp() {
        XposedProp prop = null;

        for (String path : XPOSED_PROP_FILES) {
            File file = new File(path);
            if (file.canRead()) {
                FileInputStream is = null;
                try {
                    is = new FileInputStream(file);
                    prop = InstallZipUtil.parseXposedProp(is);
                    break;
                } catch (IOException e) {
                    Log.e(XposedApp.TAG, "Could not read " + file.getPath(), e);
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }

        synchronized (this) {
            mXposedProp = prop;
        }
    }

    // TODO find a better way to trigger actions only when any UI is shown for the first time
    @Override
    public synchronized void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (mIsUiLoaded)
            return;

        RepoLoader.getInstance().triggerFirstLoadIfNecessary();
        mIsUiLoaded = true;
    }

    @Override
    public synchronized void onActivityResumed(Activity activity) {
    }

    @Override
    public synchronized void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
