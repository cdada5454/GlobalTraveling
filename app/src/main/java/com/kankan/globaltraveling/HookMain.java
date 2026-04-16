package com.kankan.globaltraveling;

import android.bluetooth.BluetoothAdapter;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookMain implements IXposedHookLoadPackage {

    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";
    private static final Random RANDOM = new Random();
    private static final ScheduledExecutorService LOCATION_PUSHER =
            Executors.newSingleThreadScheduledExecutor();
    private static final Map<Integer, Object> ACTIVE_LISTENERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Object> ACTIVE_FUSED_CALLBACKS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Integer, Object> ACTIVE_TENCENT_LISTENERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicBoolean PUSH_LOOP_STARTED = new AtomicBoolean(false);

    private static String cachedMac = "00:11:22:33:44:55";
    private static String cachedSSID = "Mandba-WiFi";
    private static String cachedPlaceName = "";
    private static String cachedAddress = "";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if ("com.kankan.globaltraveling".equals(lpparam.packageName)) {
            return;
        }

        XC_MethodHook universalLocHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                applyLocationFix((Location) param.thisObject);
            }
        };
        XposedHelpers.findAndHookConstructor(Location.class, String.class, universalLocHook);
        XposedHelpers.findAndHookMethod(Location.class, "set", Location.class, universalLocHook);

        XC_MethodHook getterHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                double[] c = readFromTmp();
                if (c == null) return;

                double d = getDrift();
                String name = param.method.getName();

                if ("getLatitude".equals(name)) {
                    param.setResult(c[0] + d);
                } else if ("getLongitude".equals(name)) {
                    param.setResult(c[1] + d);
                } else if ("getAccuracy".equals(name)) {
                    param.setResult(3.0f + RANDOM.nextFloat());
                } else if ("getSpeed".equals(name)) {
                    param.setResult(0.0f);
                } else if ("getAltitude".equals(name)) {
                    param.setResult(50.0d);
                }
            }
        };
        XposedHelpers.findAndHookMethod(Location.class, "getLatitude", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getLongitude", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getAccuracy", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getSpeed", getterHook);
        XposedHelpers.findAndHookMethod(Location.class, "getAltitude", getterHook);

        XposedHelpers.findAndHookMethod(Location.class, "isFromMockProvider",
                XC_MethodReplacement.returnConstant(false));

        XposedBridge.hookAllMethods(LocationManager.class, "getLastKnownLocation", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Location loc = (Location) param.getResult();
                if (loc == null && hasSpoofLocation()) {
                    loc = new Location(LocationManager.GPS_PROVIDER);
                    param.setResult(loc);
                }
                if (loc != null) {
                    applyLocationFix(loc);
                }
            }
        });

        XposedBridge.hookAllMethods(LocationManager.class, "requestLocationUpdates", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                for (Object arg : param.args) {
                    if (arg == null || !looksLikeLocationListener(arg)) {
                        continue;
                    }

                    hookListenerCallback(arg);
                    registerLocationListener(arg);

                    if (!hasSpoofLocation()) {
                        continue;
                    }

                    Location fastLoc = new Location(LocationManager.GPS_PROVIDER);
                    applyLocationFix(fastLoc);
                    notifyLocationListener(arg, fastLoc);
                }
            }
        });

        XposedBridge.hookAllMethods(LocationManager.class, "removeUpdates", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args == null) return;
                for (Object arg : param.args) {
                    unregisterLocationListener(arg);
                }
            }
        });

        XposedHelpers.findAndHookMethod(LocationManager.class.getName(), lpparam.classLoader,
                "isProviderEnabled", String.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!hasSpoofLocation()) return;
                        String provider = (String) param.args[0];
                        if (LocationManager.NETWORK_PROVIDER.equals(provider)
                                || LocationManager.GPS_PROVIDER.equals(provider)
                                || LocationManager.PASSIVE_PROVIDER.equals(provider)) {
                            param.setResult(true);
                        }
                    }
                });

        try {
            XposedHelpers.findAndHookMethod(LocationManager.class.getName(), lpparam.classLoader,
                    "isLocationEnabled", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (hasSpoofLocation()) {
                                param.setResult(true);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(LocationManager.class.getName(), lpparam.classLoader,
                    "hasProvider", String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!hasSpoofLocation()) return;
                            String provider = (String) param.args[0];
                            if (LocationManager.NETWORK_PROVIDER.equals(provider)
                                    || LocationManager.GPS_PROVIDER.equals(provider)
                                    || LocationManager.PASSIVE_PROVIDER.equals(provider)) {
                                param.setResult(true);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            XposedBridge.hookAllMethods(LocationManager.class, "getProviders", new XC_MethodHook() {
                @Override
                @SuppressWarnings("unchecked")
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!hasSpoofLocation()) return;
                    Object result = param.getResult();
                    if (!(result instanceof List)) return;

                    List<String> providers = new ArrayList<>((List<String>) result);
                    ensureProvider(providers, LocationManager.GPS_PROVIDER);
                    ensureProvider(providers, LocationManager.NETWORK_PROVIDER);
                    ensureProvider(providers, LocationManager.PASSIVE_PROVIDER);
                    param.setResult(providers);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedBridge.hookAllMethods(LocationManager.class, "getBestProvider", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation()) {
                        param.setResult(LocationManager.GPS_PROVIDER);
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(LocationManager.class, "getCurrentLocation",
                    String.class, CancellationSignal.class, Executor.class, Consumer.class,
                    new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!hasSpoofLocation()) return;
                            dispatchCurrentLocation(param, (String) param.args[0],
                                    (Executor) param.args[2], (Consumer<Location>) param.args[3]);
                        }
                    });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(LocationManager.class, "getCurrentLocation",
                    String.class, LocationRequest.class, CancellationSignal.class, Executor.class, Consumer.class,
                    new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!hasSpoofLocation()) return;
                            dispatchCurrentLocation(param, (String) param.args[0],
                                    (Executor) param.args[3], (Consumer<Location>) param.args[4]);
                        }
                    });
        } catch (Throwable ignored) {
        }

        XposedHelpers.findAndHookMethod(WifiManager.class.getName(), lpparam.classLoader,
                "isWifiEnabled", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (hasSpoofLocation()) {
                            param.setResult(true);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(WifiManager.class.getName(), lpparam.classLoader,
                "getWifiState", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (hasSpoofLocation()) {
                            param.setResult(WifiManager.WIFI_STATE_ENABLED);
                        }
                    }
                });

        try {
            XposedHelpers.findAndHookMethod(WifiManager.class.getName(), lpparam.classLoader,
                    "startScan", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (hasSpoofLocation()) {
                                param.setResult(true);
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }

        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getNetworkType",
                XC_MethodReplacement.returnConstant(TelephonyManager.NETWORK_TYPE_UNKNOWN));

        try {
            XposedHelpers.findAndHookMethod(ConnectivityManager.class, "getActiveNetworkInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!hasSpoofLocation()) return;

                    NetworkInfo info = (NetworkInfo) param.getResult();
                    if (info == null) {
                        try {
                            Constructor<NetworkInfo> constructor = NetworkInfo.class.getDeclaredConstructor(
                                    int.class, int.class, String.class, String.class);
                            constructor.setAccessible(true);
                            info = constructor.newInstance(ConnectivityManager.TYPE_WIFI, 0, "WIFI", "");
                            XposedHelpers.setObjectField(info, "mState", NetworkInfo.State.CONNECTED);
                            XposedHelpers.setObjectField(info, "mDetailedState", NetworkInfo.DetailedState.CONNECTED);
                            XposedHelpers.setBooleanField(info, "mIsAvailable", true);
                            param.setResult(info);
                        } catch (Throwable ignored) {
                        }
                        return;
                    }

                    XposedHelpers.setIntField(info, "mNetworkType", ConnectivityManager.TYPE_WIFI);
                    XposedHelpers.setObjectField(info, "mTypeName", "WIFI");
                    XposedHelpers.setObjectField(info, "mState", NetworkInfo.State.CONNECTED);
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(NetworkCapabilities.class, "hasTransport", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation()
                            && ((Integer) param.args[0]) == NetworkCapabilities.TRANSPORT_WIFI) {
                        param.setResult(true);
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        XposedHelpers.findAndHookMethod(WifiManager.class, "getConnectionInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!hasSpoofLocation()) return;

                WifiInfo info = (WifiInfo) param.getResult();
                if (info == null) {
                    info = buildFakeWifiInfo();
                } else {
                    fillWifiInfo(info);
                }
                param.setResult(info);
            }
        });

        try {
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation()) {
                        param.setResult("\"" + cachedSSID + "\"");
                    }
                }
            });
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getBSSID", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation()) {
                        param.setResult(cachedMac);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getMacAddress", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation()) {
                        param.setResult("02:00:00:00:00:00");
                    }
                }
            });
            XposedHelpers.findAndHookMethod(WifiInfo.class, "getRssi", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation()) {
                        param.setResult(-45 - RANDOM.nextInt(10));
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        XposedHelpers.findAndHookMethod(WifiManager.class, "getScanResults", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (hasSpoofLocation()) {
                    param.setResult(buildFakeScanResults());
                }
            }
        });

        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getSimState",
                XC_MethodReplacement.returnConstant(TelephonyManager.SIM_STATE_ABSENT));
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getSimState", int.class,
                XC_MethodReplacement.returnConstant(TelephonyManager.SIM_STATE_ABSENT));
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getAllCellInfo",
                XC_MethodReplacement.returnConstant(new ArrayList<CellInfo>()));
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getCellLocation",
                XC_MethodReplacement.returnConstant(null));
        XposedHelpers.findAndHookMethod(TelephonyManager.class, "getNetworkOperator",
                XC_MethodReplacement.returnConstant(""));

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                XposedHelpers.findAndHookMethod(TelephonyManager.class, "requestCellInfoUpdate",
                        Executor.class, TelephonyManager.CellInfoCallback.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!hasSpoofLocation()) return;
                                Executor executor = (Executor) param.args[0];
                                TelephonyManager.CellInfoCallback callback =
                                        (TelephonyManager.CellInfoCallback) param.args[1];
                                if (executor != null && callback != null) {
                                    executor.execute(() -> callback.onCellInfo(new ArrayList<>()));
                                }
                                param.setResult(null);
                            }
                        });
            } catch (Throwable ignored) {
            }
        }

        XposedHelpers.findAndHookMethod(TelephonyManager.class, "listen",
                PhoneStateListener.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (hasSpoofLocation()) {
                            param.args[1] = PhoneStateListener.LISTEN_NONE;
                        }
                    }
                });

        try {
            XposedHelpers.findAndHookMethod(BluetoothAdapter.class, "getBondedDevices", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation()) {
                        param.setResult(Collections.emptySet());
                    }
                }
            });
            XposedHelpers.findAndHookMethod(BluetoothAdapter.class, "startDiscovery",
                    XC_MethodReplacement.returnConstant(false));
        } catch (Throwable ignored) {
        }

        XposedBridge.hookAllMethods(LocationManager.class, "addNmeaListener",
                XC_MethodReplacement.returnConstant(true));
        if (Build.VERSION.SDK_INT >= 24) {
            XposedHelpers.findAndHookMethod(GnssStatus.class, "getSatelliteCount", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation()) {
                        param.setResult(15);
                    }
                }
            });
        }

        try {
            Class<?> amap = XposedHelpers.findClass("com.amap.api.location.AMapLocation", lpparam.classLoader);
            hookSDK(amap, getterHook);
            XposedHelpers.findAndHookMethod(amap, "getLocationType",
                    XC_MethodReplacement.returnConstant(1));
        } catch (Throwable ignored) {
        }

        try {
            Class<?> tencent = XposedHelpers.findClass(
                    "com.tencent.map.geolocation.TencentLocation", lpparam.classLoader);
            hookSDK(tencent, getterHook);
            XposedHelpers.findAndHookMethod(tencent, "getProvider",
                    XC_MethodReplacement.returnConstant("gps"));
        } catch (Throwable ignored) {
        }

        hookTencentLocation(lpparam.classLoader);
        hookGoogleFusedLocation(lpparam.classLoader);
    }

    private void hookSDK(Class<?> clazz, XC_MethodHook hook) {
        try {
            XposedHelpers.findAndHookMethod(clazz, "getLatitude", hook);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(clazz, "getLongitude", hook);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.findAndHookMethod(clazz, "getAccuracy", hook);
        } catch (Throwable ignored) {
        }
    }

    private void hookTencentLocation(ClassLoader classLoader) {
        final Class<?> tencentLocationClass;
        final Class<?> tencentListenerClass;
        try {
            tencentLocationClass = XposedHelpers.findClass(
                    "com.tencent.map.geolocation.TencentLocation", classLoader);
            tencentListenerClass = XposedHelpers.findClass(
                    "com.tencent.map.geolocation.TencentLocationListener", classLoader);
        } catch (Throwable ignored) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(tencentLocationClass, "getName", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation() && !cachedPlaceName.isEmpty()) {
                        param.setResult(cachedPlaceName);
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(tencentLocationClass, "getAddress", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation() && !cachedAddress.isEmpty()) {
                        param.setResult(cachedAddress);
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            XposedHelpers.findAndHookMethod(tencentLocationClass, "getCity", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation() && !cachedAddress.isEmpty()) {
                        param.setResult(cachedAddress);
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            Class<?> tencentManagerClass = XposedHelpers.findClass(
                    "com.tencent.map.geolocation.TencentLocationManager", classLoader);

            XposedBridge.hookAllMethods(tencentManagerClass, "requestLocationUpdates", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null) {
                        return;
                    }
                    for (Object arg : param.args) {
                        if (!tencentListenerClass.isInstance(arg)) {
                            continue;
                        }
                        hookTencentListenerCallback(arg);
                        registerTencentListener(arg);
                        if (hasSpoofLocation()) {
                            notifyTencentListener(arg);
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation() && param.getResult() instanceof Integer) {
                        param.setResult(0);
                    }
                }
            });

            XposedBridge.hookAllMethods(tencentManagerClass, "requestSingleFreshLocation", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null) {
                        return;
                    }
                    for (Object arg : param.args) {
                        if (!tencentListenerClass.isInstance(arg)) {
                            continue;
                        }
                        hookTencentListenerCallback(arg);
                        registerTencentListener(arg);
                        if (hasSpoofLocation()) {
                            notifyTencentListener(arg);
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (hasSpoofLocation() && param.getResult() instanceof Integer) {
                        param.setResult(0);
                    }
                }
            });

            XposedBridge.hookAllMethods(tencentManagerClass, "removeUpdates", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null) {
                        return;
                    }
                    for (Object arg : param.args) {
                        unregisterTencentListener(arg);
                    }
                }
            });

            XposedBridge.hookAllMethods(tencentManagerClass, "getLastKnownLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!hasSpoofLocation()) {
                        return;
                    }
                    Object tencentLocation = buildTencentLocationProxy(classLoader);
                    if (tencentLocation != null) {
                        param.setResult(tencentLocation);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void hookGoogleFusedLocation(ClassLoader classLoader) {
        try {
            final Class<?> fusedClientClass = XposedHelpers.findClass(
                    "com.google.android.gms.location.FusedLocationProviderClient", classLoader);

            XposedBridge.hookAllMethods(fusedClientClass, "requestLocationUpdates", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null) {
                        return;
                    }
                    for (Object arg : param.args) {
                        if (!looksLikeFusedLocationCallback(arg)) {
                            continue;
                        }
                        hookFusedCallback(arg);
                        registerFusedCallback(arg);
                        if (hasSpoofLocation()) {
                            notifyFusedCallback(arg, buildSpoofedLocation(LocationManager.GPS_PROVIDER));
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(fusedClientClass, "removeLocationUpdates", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null) {
                        return;
                    }
                    for (Object arg : param.args) {
                        unregisterFusedCallback(arg);
                    }
                }
            });
        } catch (Throwable ignored) {
        }

        try {
            final Class<?> locationResultClass = XposedHelpers.findClass(
                    "com.google.android.gms.location.LocationResult", classLoader);

            XposedBridge.hookAllMethods(locationResultClass, "getLastLocation", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!hasSpoofLocation()) {
                        return;
                    }
                    Location location = (Location) param.getResult();
                    if (location == null) {
                        location = buildSpoofedLocation(LocationManager.GPS_PROVIDER);
                    } else {
                        applyLocationFix(location);
                    }
                    param.setResult(location);
                }
            });

            XposedBridge.hookAllMethods(locationResultClass, "getLocations", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!hasSpoofLocation()) {
                        return;
                    }
                    List<Location> locations = new ArrayList<>();
                    Location location = buildSpoofedLocation(LocationManager.GPS_PROVIDER);
                    if (location != null) {
                        locations.add(location);
                    }
                    param.setResult(locations);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean looksLikeLocationListener(Object candidate) {
        if (candidate == null) {
            return false;
        }
        if (candidate instanceof LocationListener) {
            return true;
        }
        return XposedHelpers.findMethodExactIfExists(candidate.getClass(),
                "onLocationChanged", Location.class) != null;
    }

    private boolean looksLikeFusedLocationCallback(Object candidate) {
        if (candidate == null) {
            return false;
        }
        Class<?> locationResultClass = resolveLocationResultClass(candidate.getClass().getClassLoader());
        if (locationResultClass == null) {
            return false;
        }
        return XposedHelpers.findMethodExactIfExists(candidate.getClass(),
                "onLocationResult", locationResultClass) != null;
    }

    private void hookListenerCallback(Object listener) {
        if (listener == null) {
            return;
        }
        try {
            if (XposedHelpers.getAdditionalInstanceField(listener, "shadow_listener_hooked") != null) {
                return;
            }
            XposedHelpers.findAndHookMethod(listener.getClass(), "onLocationChanged",
                    Location.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            applyLocationFix((Location) param.args[0]);
                        }
                    });
            XposedHelpers.setAdditionalInstanceField(listener, "shadow_listener_hooked", Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private void hookFusedCallback(Object callback) {
        if (callback == null) {
            return;
        }
        try {
            if (XposedHelpers.getAdditionalInstanceField(callback, "shadow_fused_hooked") != null) {
                return;
            }
            Class<?> locationResultClass = resolveLocationResultClass(callback.getClass().getClassLoader());
            if (locationResultClass == null) {
                return;
            }
            XposedHelpers.findAndHookMethod(callback.getClass(), "onLocationResult",
                    locationResultClass, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!hasSpoofLocation()) {
                                return;
                            }
                            Location location = buildSpoofedLocation(LocationManager.GPS_PROVIDER);
                            Object result = buildLocationResult(
                                    callback.getClass().getClassLoader(), location);
                            if (result != null) {
                                param.args[0] = result;
                            }
                        }
                    });
            XposedHelpers.setAdditionalInstanceField(callback, "shadow_fused_hooked", Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private void hookTencentListenerCallback(Object listener) {
        if (listener == null) {
            return;
        }
        try {
            if (XposedHelpers.getAdditionalInstanceField(listener, "shadow_tencent_hooked") != null) {
                return;
            }
            ClassLoader classLoader = listener.getClass().getClassLoader();
            Class<?> tencentLocationClass = XposedHelpers.findClass(
                    "com.tencent.map.geolocation.TencentLocation", classLoader);
            XposedHelpers.findAndHookMethod(listener.getClass(), "onLocationChanged",
                    tencentLocationClass, int.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!hasSpoofLocation()) {
                                return;
                            }
                            Object spoofedLocation = buildTencentLocationProxy(classLoader);
                            if (spoofedLocation != null) {
                                param.args[0] = spoofedLocation;
                            }
                            param.args[1] = 0;
                            param.args[2] = "OK";
                        }
                    });
            XposedHelpers.setAdditionalInstanceField(listener, "shadow_tencent_hooked", Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    private void registerLocationListener(Object listener) {
        if (listener == null) {
            return;
        }
        ACTIVE_LISTENERS.put(System.identityHashCode(listener), listener);
        startPushLoopIfNeeded();
    }

    private void registerFusedCallback(Object callback) {
        if (callback == null) {
            return;
        }
        ACTIVE_FUSED_CALLBACKS.put(System.identityHashCode(callback), callback);
        startPushLoopIfNeeded();
    }

    private void registerTencentListener(Object listener) {
        if (listener == null) {
            return;
        }
        ACTIVE_TENCENT_LISTENERS.put(System.identityHashCode(listener), listener);
        startPushLoopIfNeeded();
    }

    private void unregisterLocationListener(Object listener) {
        if (listener == null) {
            return;
        }
        ACTIVE_LISTENERS.remove(System.identityHashCode(listener));
    }

    private void unregisterFusedCallback(Object callback) {
        if (callback == null) {
            return;
        }
        ACTIVE_FUSED_CALLBACKS.remove(System.identityHashCode(callback));
    }

    private void unregisterTencentListener(Object listener) {
        if (listener == null) {
            return;
        }
        ACTIVE_TENCENT_LISTENERS.remove(System.identityHashCode(listener));
    }

    private void startPushLoopIfNeeded() {
        if (!PUSH_LOOP_STARTED.compareAndSet(false, true)) {
            return;
        }
        LOCATION_PUSHER.scheduleWithFixedDelay(() -> {
            if (!hasSpoofLocation()
                    || (ACTIVE_LISTENERS.isEmpty()
                    && ACTIVE_FUSED_CALLBACKS.isEmpty()
                    && ACTIVE_TENCENT_LISTENERS.isEmpty())) {
                return;
            }
            Location location = buildSpoofedLocation(LocationManager.GPS_PROVIDER);
            if (location == null) {
                return;
            }
            for (Object listener : new ArrayList<>(ACTIVE_LISTENERS.values())) {
                notifyLocationListener(listener, new Location(location));
            }
            for (Object callback : new ArrayList<>(ACTIVE_FUSED_CALLBACKS.values())) {
                notifyFusedCallback(callback, new Location(location));
            }
            for (Object listener : new ArrayList<>(ACTIVE_TENCENT_LISTENERS.values())) {
                notifyTencentListener(listener);
            }
        }, 0, 1500, TimeUnit.MILLISECONDS);
    }

    private void notifyLocationListener(Object listener, Location location) {
        if (listener == null || location == null) {
            return;
        }
        try {
            if (listener instanceof LocationListener) {
                ((LocationListener) listener).onLocationChanged(location);
                return;
            }
            XposedHelpers.callMethod(listener, "onLocationChanged", location);
        } catch (Throwable ignored) {
        }
    }

    private void notifyFusedCallback(Object callback, Location location) {
        if (callback == null || location == null) {
            return;
        }
        try {
            Object result = buildLocationResult(callback.getClass().getClassLoader(), location);
            if (result == null) {
                return;
            }
            XposedHelpers.callMethod(callback, "onLocationResult", result);
        } catch (Throwable ignored) {
        }
    }

    private void notifyTencentListener(Object listener) {
        if (listener == null || !hasSpoofLocation()) {
            return;
        }
        try {
            Object tencentLocation = buildTencentLocationProxy(listener.getClass().getClassLoader());
            if (tencentLocation == null) {
                return;
            }
            XposedHelpers.callMethod(listener, "onLocationChanged", tencentLocation, 0, "OK");
        } catch (Throwable ignored) {
        }
    }

    private Class<?> resolveLocationResultClass(ClassLoader classLoader) {
        try {
            return XposedHelpers.findClass("com.google.android.gms.location.LocationResult", classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object buildLocationResult(ClassLoader classLoader, Location location) {
        if (location == null) {
            return null;
        }
        Class<?> locationResultClass = resolveLocationResultClass(classLoader);
        if (locationResultClass == null) {
            return null;
        }

        List<Location> locations = new ArrayList<>();
        locations.add(location);

        try {
            return XposedHelpers.callStaticMethod(locationResultClass, "create", locations);
        } catch (Throwable ignored) {
        }

        try {
            Constructor<?> constructor = locationResultClass.getDeclaredConstructor(List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(locations);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object buildTencentLocationProxy(ClassLoader classLoader) {
        double[] c = readFromTmp();
        if (c == null) {
            return null;
        }
        try {
            Class<?> tencentLocationClass = XposedHelpers.findClass(
                    "com.tencent.map.geolocation.TencentLocation", classLoader);
            double lat = c[0] + getDrift();
            double lng = c[1] + getDrift();
            float accuracy = 3.0f + RANDOM.nextFloat();
            String name = cachedPlaceName.isEmpty() ? cachedAddress : cachedPlaceName;
            String address = cachedAddress.isEmpty() ? name : cachedAddress;

            return Proxy.newProxyInstance(classLoader, new Class[]{tencentLocationClass}, (proxy, method, args) -> {
                String methodName = method.getName();
                switch (methodName) {
                    case "getLatitude":
                        return lat;
                    case "getLongitude":
                        return lng;
                    case "getAccuracy":
                        return accuracy;
                    case "getProvider":
                        return "gps";
                    case "getName":
                        return name;
                    case "getAddress":
                        return address;
                    case "getCity":
                    case "getProvince":
                    case "getDistrict":
                    case "getStreet":
                    case "getStreetNo":
                    case "getVillage":
                    case "getTown":
                        return address;
                    case "getNation":
                        return "中国";
                    case "getTime":
                        return System.currentTimeMillis();
                    case "getAltitude":
                    case "getBearing":
                    case "getSpeed":
                        return 0.0d;
                    case "getGPSRssi":
                    case "getIndoorLocationSource":
                        return 0;
                    case "getExtra":
                        return null;
                    case "isMockGps":
                        return false;
                    case "toString":
                        return "TencentLocationProxy(" + lat + "," + lng + ")";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == null || !returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == int.class || returnType == short.class || returnType == byte.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0f;
        }
        if (returnType == double.class) {
            return 0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private void ensureProvider(List<String> providers, String provider) {
        if (!providers.contains(provider)) {
            providers.add(provider);
        }
    }

    private boolean hasSpoofLocation() {
        return readFromTmp() != null;
    }

    private void dispatchCurrentLocation(XC_MethodHook.MethodHookParam param, String provider,
                                         Executor executor, Consumer<Location> consumer) {
        Location location = buildSpoofedLocation(provider);
        if (consumer == null || location == null) {
            param.setResult(null);
            return;
        }

        if (executor != null) {
            executor.execute(() -> consumer.accept(location));
        } else {
            consumer.accept(location);
        }
        param.setResult(null);
    }

    private Location buildSpoofedLocation(String provider) {
        if (!hasSpoofLocation()) {
            return null;
        }
        String actualProvider = provider;
        if (actualProvider == null || actualProvider.isEmpty()) {
            actualProvider = LocationManager.GPS_PROVIDER;
        }
        Location location = new Location(actualProvider);
        applyLocationFix(location);
        return location;
    }

    private WifiInfo buildFakeWifiInfo() {
        try {
            Constructor<?> constructor = WifiInfo.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            WifiInfo info = (WifiInfo) constructor.newInstance();
            fillWifiInfo(info);
            return info;
        } catch (Throwable ignored) {
            try {
                WifiInfo info = (WifiInfo) XposedHelpers.newInstance(WifiInfo.class);
                fillWifiInfo(info);
                return info;
            } catch (Throwable innerIgnored) {
                return null;
            }
        }
    }

    private void fillWifiInfo(WifiInfo info) {
        if (info == null) return;
        try {
            XposedHelpers.setObjectField(info, "mBSSID", cachedMac);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setObjectField(info, "mSSID", "\"" + cachedSSID + "\"");
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setObjectField(info, "mMacAddress", "02:00:00:00:00:00");
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setObjectField(info, "mRssi", -45 - RANDOM.nextInt(10));
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setIntField(info, "mNetworkId", 1);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setIntField(info, "mFrequency", 2412);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setIntField(info, "mLinkSpeed", 150);
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setObjectField(info, "mSupplicantState", SupplicantState.COMPLETED);
        } catch (Throwable ignored) {
        }
    }

    private List<ScanResult> buildFakeScanResults() {
        List<ScanResult> list = new ArrayList<>();
        try {
            Constructor<ScanResult> ctor = ScanResult.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            for (int i = 0; i < 5; i++) {
                ScanResult result = ctor.newInstance();
                result.SSID = (i == 0) ? cachedSSID : "Fake_WLAN_" + i;
                result.BSSID = (i == 0) ? cachedMac : "02:00:00:00:00:0" + i;
                result.level = -40 - RANDOM.nextInt(30);
                result.capabilities = "[WPA2-PSK-CCMP][ESS]";
                result.frequency = 2412;
                if (Build.VERSION.SDK_INT >= 17) {
                    result.timestamp = SystemClock.elapsedRealtime() * 1000;
                }
                list.add(result);
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    private void applyLocationFix(Location loc) {
        if (loc == null) return;
        double[] c = readFromTmp();
        if (c == null) return;

        double d = getDrift();
        double lat = c[0] + d;
        double lng = c[1] + d;

        try {
            XposedHelpers.setDoubleField(loc, "mLatitude", lat);
            XposedHelpers.setDoubleField(loc, "mLongitude", lng);
        } catch (Throwable ignored) {
            loc.setLatitude(lat);
            loc.setLongitude(lng);
        }

        loc.setAccuracy(3.0f + RANDOM.nextFloat());
        loc.setProvider(LocationManager.GPS_PROVIDER);
        loc.setSpeed(0.0f);
        loc.setAltitude(50.0d);
        loc.setTime(System.currentTimeMillis());
        if (Build.VERSION.SDK_INT >= 17) {
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        }
        try {
            XposedHelpers.setBooleanField(loc, "mIsFromMockProvider", false);
        } catch (Throwable ignored) {
        }
    }

    private double getDrift() {
        return (RANDOM.nextDouble() - 0.5d) * 0.00002d;
    }

    private double[] readFromTmp() {
        try {
            File file = new File(FILE_PATH);
            if (!file.exists() || !file.canRead()) return null;

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            String line = raf.readLine();
            raf.close();

            if (line == null) return null;
            String[] parts = line.split(",");
            if (parts.length < 3 || !"1".equals(parts[2])) return null;

            if (parts.length >= 5) {
                cachedMac = parts[3];
                cachedSSID = parts[4];
            }
            if (parts.length >= 6) {
                cachedPlaceName = decodeField(parts[5]);
            } else {
                cachedPlaceName = "";
            }
            if (parts.length >= 7) {
                cachedAddress = decodeField(parts[6]);
            } else {
                cachedAddress = cachedPlaceName;
            }

            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
        } catch (Exception ignored) {
            return null;
        }
    }

    private String decodeField(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return value;
        }
    }
}
