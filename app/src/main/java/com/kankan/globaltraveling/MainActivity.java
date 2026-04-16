package com.kankan.globaltraveling;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.TextUtils.TruncateAt;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.widget.TextViewCompat;

import com.amap.api.maps.AMap;
import com.amap.api.maps.CameraUpdateFactory;
import com.amap.api.maps.MapView;
import com.amap.api.maps.MapsInitializer;
import com.amap.api.maps.model.LatLng;
import com.amap.api.maps.model.MarkerOptions;
import com.amap.api.services.core.ServiceSettings;
import com.amap.api.services.help.Inputtips;
import com.amap.api.services.help.InputtipsQuery;
import com.amap.api.services.help.Tip;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.DataOutputStream;
import java.io.File;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements Inputtips.InputtipsListener {

    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";
    private static final String PREF_NAME = "IdentityConfig";
    private static final String PREF_HISTORY = "LocHistory";
    private static final String KEY_HISTORY = "history_list";
    private static final String KEY_DEF_LAT = "def_lat";
    private static final String KEY_DEF_LNG = "def_lng";
    private static final String KEY_DEF_NAME = "def_name";
    private static final String STOP_SIMULATION_PAYLOAD = "0,0,0,0,0,0,0";
    private static final int REQ_LOCATION_PERMISSION = 1001;
    private static final int MAX_HISTORY_ITEMS = 20;
    private static final float THREE_BUTTON_NAV_INSET_THRESHOLD_DP = 24f;
    private static final int SIM_DOT_COLOR_ACTIVE = Color.parseColor("#00E676");
    private static final int SIM_DOT_COLOR_INACTIVE = Color.parseColor("#FF1744");
    private static final LatLng DEFAULT_CENTER = new LatLng(39.9042, 116.4074);

    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final List<HistoryItem> historyItems = new ArrayList<>();

    private MapView mapView;
    private AMap aMap;
    private View rootLayout;
    private View topFogView;
    private ImageView simDotView;
    private CardView searchCard;
    private CardView bottomPanel;
    private TextView tvSimState;
    private TextView tvStatus;
    private AutoCompleteTextView etSearch;
    private LinearLayout historyContainer;
    private ImageButton btnHistory;
    private MaterialButton btnGoCurrent;
    private MaterialButton btnToggleSim;
    private Runnable searchRunnable;
    private String fixedMac;
    private String fixedSSID;
    private double selectLat;
    private double selectLng;
    private int bottomPanelBaseMarginBottom = Integer.MIN_VALUE;
    private int topFogBaseHeight = Integer.MIN_VALUE;
    private String currentName = "手动选点";
    private String currentAddress = "手动选点";
    private Bundle savedState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        MapsInitializer.updatePrivacyShow(this, true, true);
        MapsInitializer.updatePrivacyAgree(this, true);

        try {
            ServiceSettings.updatePrivacyShow(this, true, true);
            ServiceSettings.updatePrivacyAgree(this, true);
        } catch (Throwable ignored) {
        }

        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        savedState = savedInstanceState;
        applyEdgeToEdgeSystemBars();
        initApp();
    }

    private void applyEdgeToEdgeSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attributes);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }

        boolean useLightSystemBarIcons = !isDarkMode();
        int uiFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && useLightSystemBarIcons) {
            uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && useLightSystemBarIcons) {
            uiFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(uiFlags);
    }

    private void initApp() {
        setContentView(R.layout.activity_main);
        rootLayout = findViewById(R.id.root_layout);
        topFogView = findViewById(R.id.view_top_fog);
        simDotView = findViewById(R.id.view_sim_dot);
        searchCard = findViewById(R.id.search_card);
        bottomPanel = findViewById(R.id.bottom_panel);
        tvSimState = findViewById(R.id.tv_sim_state);
        tvStatus = findViewById(R.id.tv_status);
        setStatusText(tvStatus.getText());
        etSearch = findViewById(R.id.et_search);
        if (etSearch != null) {
            etSearch.setDropDownBackgroundResource(R.drawable.bg_search_dropdown_rounded);
        }
        historyContainer = findViewById(R.id.history_container);
        btnHistory = findViewById(R.id.btn_history);
        btnGoCurrent = findViewById(R.id.btn_go_current);
        btnToggleSim = findViewById(R.id.btn_toggle_sim);
        mapView = findViewById(R.id.map);
        bindBottomPanelInsets();

        initIdentity();
        loadHistory();
        bindSearch();
        bindActions();
        initMapSafely();
        bindMap();
        loadDefaultLocation();
        applyMaterialYouTheme();
        refreshSimulationButton();
    }

    private void bindBottomPanelInsets() {
        if (bottomPanel == null) {
            return;
        }

        ViewGroup.LayoutParams params = bottomPanel.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        bottomPanelBaseMarginBottom = ((ViewGroup.MarginLayoutParams) params).bottomMargin;

        View insetTarget = rootLayout != null ? rootLayout : bottomPanel;
        ViewCompat.setOnApplyWindowInsetsListener(insetTarget, (view, windowInsets) -> {
            applyTopFogInset(windowInsets);
            applyBottomPanelInset(windowInsets);
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(insetTarget);
    }

    private void applyTopFogInset(WindowInsetsCompat windowInsets) {
        if (topFogView == null) {
            return;
        }

        ViewGroup.LayoutParams params = topFogView.getLayoutParams();
        if (params == null) {
            return;
        }
        if (topFogBaseHeight == Integer.MIN_VALUE) {
            topFogBaseHeight = params.height;
        }

        Insets topInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() | WindowInsetsCompat.Type.displayCutout()
        );
        int insetTop = topInsets.top;
        int targetHeight = topFogBaseHeight + insetTop;
        if (params.height != targetHeight) {
            params.height = targetHeight;
            topFogView.setLayoutParams(params);
        }
        if (topFogView.getTranslationY() != -insetTop) {
            topFogView.setTranslationY(-insetTop);
        }
    }

    private void applyBottomPanelInset(WindowInsetsCompat windowInsets) {
        if (bottomPanel == null) {
            return;
        }

        ViewGroup.LayoutParams params = bottomPanel.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) params;
        if (bottomPanelBaseMarginBottom == Integer.MIN_VALUE) {
            bottomPanelBaseMarginBottom = marginParams.bottomMargin;
        }

        Insets navInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars());
        int navBottom = navInsets.bottom;
        int extraLift = navBottom > dp2px(THREE_BUTTON_NAV_INSET_THRESHOLD_DP) ? navBottom : 0;
        int targetBottomMargin = bottomPanelBaseMarginBottom + extraLift;
        if (marginParams.bottomMargin != targetBottomMargin) {
            marginParams.bottomMargin = targetBottomMargin;
            bottomPanel.setLayoutParams(marginParams);
        }
    }

    private void initMapSafely() {
        if (mapView == null) {
            showMapUnavailableState();
            return;
        }

        try {
            mapView.onCreate(savedState);
            aMap = mapView.getMap();
            if (aMap == null) {
                showMapUnavailableState();
                return;
            }
            aMap.getUiSettings().setZoomControlsEnabled(false);
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, 10f));
        } catch (Throwable ignored) {
            showMapUnavailableState();
        }
    }

    private void showMapUnavailableState() {
        aMap = null;
        if (mapView != null) {
            mapView.setVisibility(View.INVISIBLE);
        }
        setStatusText(getString(R.string.map_init_failed));
        Toast.makeText(this, R.string.map_feature_limited, Toast.LENGTH_SHORT).show();
    }

    private void bindMap() {
        if (aMap == null) {
            return;
        }
        aMap.setOnMapLongClickListener(latLng ->
                updateSelection(latLng.latitude, latLng.longitude,
                        getString(R.string.manual_point),
                        getString(R.string.manual_point)));
    }

    private void bindSearch() {
        etSearch.setThreshold(1);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) {
                    searchHandler.removeCallbacks(searchRunnable);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                searchRunnable = () -> {
                    String keyword = s.toString().trim();
                    if (keyword.isEmpty()) {
                        etSearch.dismissDropDown();
                        return;
                    }

                    InputtipsQuery query = new InputtipsQuery(keyword, "");
                    query.setCityLimit(false);
                    Inputtips inputTips = new Inputtips(MainActivity.this, query);
                    inputTips.setInputtipsListener(MainActivity.this);
                    inputTips.requestInputtipsAsyn();
                };
                searchHandler.postDelayed(searchRunnable, 600L);
            }
        });

        etSearch.setOnItemClickListener((parent, view, position, id) -> {
            Tip tip = (Tip) parent.getItemAtPosition(position);
            if (tip == null || tip.getPoint() == null) {
                return;
            }

            HistoryItem item = new HistoryItem(
                    safeText(tip.getName(), getString(R.string.target_location)),
                    buildAddressText(tip),
                    tip.getPoint().getLatitude(),
                    tip.getPoint().getLongitude()
            );
            applyHistorySelection(item);

            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            }

            etSearch.setText("");
            etSearch.clearFocus();
        });
    }

    private void bindActions() {
        btnHistory.setOnClickListener(v -> showHistoryDialog());
        btnGoCurrent.setOnClickListener(v -> goToCurrentLocation());
        btnToggleSim.setOnClickListener(v -> toggleSimulation());
        findViewById(R.id.btn_set_default).setOnClickListener(v -> {
            if (!hasSelectedPoint()) {
                Toast.makeText(this, R.string.please_select_point_first, Toast.LENGTH_SHORT).show();
                return;
            }

            getSharedPreferences(PREF_HISTORY, MODE_PRIVATE)
                    .edit()
                    .putFloat(KEY_DEF_LAT, (float) selectLat)
                    .putFloat(KEY_DEF_LNG, (float) selectLng)
                    .putString(KEY_DEF_NAME, currentName)
                    .apply();
            Toast.makeText(this, R.string.default_location_saved, Toast.LENGTH_SHORT).show();
        });
    }

    private void toggleSimulation() {
        if (isSimulationRunning()) {
            writeToSystemTmp(STOP_SIMULATION_PAYLOAD, false);
            return;
        }

        if (!hasSelectedPoint()) {
            Toast.makeText(this, R.string.please_select_point_first, Toast.LENGTH_SHORT).show();
            return;
        }

        String payload = selectLat + "," + selectLng + ",1," + fixedMac + "," + fixedSSID
                + "," + encodeField(currentName) + "," + encodeField(currentAddress);
        writeToSystemTmp(payload, true);
        saveHistory(new HistoryItem(currentName, currentAddress, selectLat, selectLng));
    }

    private boolean hasSelectedPoint() {
        return selectLat != 0d || selectLng != 0d;
    }

    private void goToCurrentLocation() {
        if (isSimulationRunning()) {
            if (!syncSimulatedSelection(true)) {
                Toast.makeText(this, R.string.no_active_simulated_location, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQ_LOCATION_PERMISSION
            );
            return;
        }

        Location realLocation = getBestLastKnownLocation();
        if (realLocation == null) {
            Toast.makeText(this, R.string.real_location_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

        LatLng latLng = new LatLng(realLocation.getLatitude(), realLocation.getLongitude());
        if (aMap != null) {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f));
            aMap.clear();
            aMap.addMarker(new MarkerOptions().position(latLng).title(getString(R.string.current_location)));
        }
        setStatusText(getString(R.string.back_to_real_location));
        refreshStateSummary(getString(R.string.current_location), false);
    }

    private boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == 0
                || ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == 0;
    }

    private Location getBestLastKnownLocation() {
        try {
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager == null) {
                return null;
            }

            Location best = null;
            String[] providers = {
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
            };

            for (String provider : providers) {
                try {
                    if (LocationManager.PASSIVE_PROVIDER.equals(provider) || locationManager.isProviderEnabled(provider)) {
                        Location location = locationManager.getLastKnownLocation(provider);
                        if (location != null && (best == null || location.getTime() > best.getTime())) {
                            best = location;
                        }
                    }
                } catch (SecurityException e) {
                    return null;
                } catch (Exception ignored) {
                }
            }

            return best;
        } catch (Exception ignored) {
            return null;
        }
    }

    private SimulatedState readSimulatedState() {
        try {
            File file = new File(FILE_PATH);
            if (!file.exists() || !file.canRead()) {
                return null;
            }

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            String line = raf.readLine();
            raf.close();
            if (line == null || line.isEmpty()) {
                return null;
            }

            String[] parts = line.split(",", 7);
            if (parts.length < 3 || !"1".equals(parts[2])) {
                return null;
            }

            double lat = Double.parseDouble(parts[0].trim());
            double lng = Double.parseDouble(parts[1].trim());
            String fallbackName = getString(R.string.simulated_location);
            String name = parts.length >= 6 ? safeText(decodeField(parts[5]), fallbackName) : fallbackName;
            String address = parts.length >= 7 ? safeText(decodeField(parts[6]), name) : name;
            return new SimulatedState(lat, lng, name, address);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean syncSimulatedSelection(boolean moveCamera) {
        SimulatedState simulated = readSimulatedState();
        if (simulated == null) {
            return false;
        }

        selectLat = simulated.lat;
        selectLng = simulated.lng;
        currentName = simulated.name;
        currentAddress = simulated.address;

        if (aMap == null) {
            refreshStateSummary(simulated.name, true);
            return true;
        }

        if (moveCamera) {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(simulated.lat, simulated.lng), 16f));
        }

        updateSelection(simulated.lat, simulated.lng, simulated.name, simulated.address);
        return true;
    }

    private void refreshSimulationButton() {
        boolean simRunning = isSimulationRunning();
        if (simRunning) {
            SimulatedState simulated = readSimulatedState();
            if (simulated != null) {
                selectLat = simulated.lat;
                selectLng = simulated.lng;
                currentName = simulated.name;
                currentAddress = simulated.address;
                updateSimulationUi(true, simulated.name);
                return;
            }
        }

        updateSimulationUi(simRunning, hasSelectedPoint() ? currentName : "");
    }

    private void updateSimulationUi(boolean simRunning, String locationText) {
        if (btnToggleSim != null) {
            btnToggleSim.setText(simRunning ? R.string.stop_simulation : R.string.start_simulation);
        }
        refreshStateSummary(locationText, simRunning);
    }

    private void applyMaterialYouTheme() {
        int colorBackground = MaterialColors.getColor(btnToggleSim, android.R.attr.colorBackground, Color.parseColor("#F8FAFF"));
        int surface = MaterialColors.getColor(btnToggleSim, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        int surfaceContainer = MaterialColors.getColor(btnToggleSim, com.google.android.material.R.attr.colorSurfaceContainer, surface);
        int surfaceContainerHigh = MaterialColors.getColor(btnToggleSim, com.google.android.material.R.attr.colorSurfaceContainerHigh, surfaceContainer);
        int onSurface = MaterialColors.getColor(btnToggleSim, com.google.android.material.R.attr.colorOnSurface, Color.parseColor("#1A1C1E"));
        int onSurfaceVariant = MaterialColors.getColor(btnToggleSim, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#44474E"));
        int primary = MaterialColors.getColor(btnToggleSim, com.google.android.material.R.attr.colorPrimary, Color.parseColor("#6750A4"));
        int tertiary = MaterialColors.getColor(btnToggleSim, com.google.android.material.R.attr.colorTertiary, Color.parseColor("#7D5260"));
        int outlineVariant = MaterialColors.getColor(btnToggleSim, com.google.android.material.R.attr.colorOutlineVariant, Color.parseColor("#D9DDE3"));
        int cardSurface = withAlpha(surfaceContainerHigh, isDarkMode() ? 230 : 246);
        int subtleStroke = Color.TRANSPARENT;
        int softRipple = primary;

        if (rootLayout != null) {
            rootLayout.setBackgroundColor(colorBackground);
        }
        if (searchCard != null) {
            searchCard.setCardBackgroundColor(cardSurface);
            applyCardShadow(searchCard, 27f, 28f);
        }
        if (bottomPanel != null) {
            bottomPanel.setCardBackgroundColor(cardSurface);
            applyCardShadow(bottomPanel, 24f, 30f);
        }
        if (etSearch != null) {
            etSearch.setTextColor(onSurface);
            etSearch.setHintTextColor(onSurfaceVariant);
            TextViewCompat.setCompoundDrawableTintList(etSearch, ColorStateList.valueOf(onSurfaceVariant));
        }
        if (tvStatus != null) {
            tvStatus.setTextColor(onSurfaceVariant);
        }
        if (tvSimState != null) {
            tvSimState.setTextColor(tertiary);
        }
        if (simDotView != null) {
            simDotView.setImageTintList(ColorStateList.valueOf(isSimulationRunning()
                    ? SIM_DOT_COLOR_ACTIVE
                    : SIM_DOT_COLOR_INACTIVE));
        }

        TextView btnSetDefault = findViewById(R.id.btn_set_default);
        if (btnSetDefault != null) {
            btnSetDefault.setTextColor(primary);
            btnSetDefault.setBackgroundResource(resolveThemeResId(android.R.attr.selectableItemBackgroundBorderless));
        }
        if (btnGoCurrent != null) {
            applyFlatButtonStyle(
                    btnGoCurrent,
                    surface,
                    onSurface,
                    subtleStroke,
                    softRipple,
                    28f
            );
        }
        if (btnToggleSim != null) {
            applyFlatButtonStyle(
                    btnToggleSim,
                    surface,
                    onSurface,
                    subtleStroke,
                    tertiary,
                    28f
            );
        }
        if (btnHistory != null) {
            btnHistory.setBackgroundResource(resolveThemeResId(android.R.attr.selectableItemBackgroundBorderless));
            btnHistory.setImageTintList(ColorStateList.valueOf(onSurfaceVariant));
            btnHistory.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            btnHistory.setPadding(dp2px(4f), dp2px(4f), dp2px(4f), dp2px(4f));
            btnHistory.setElevation(0f);
            btnHistory.setStateListAnimator(null);
        }

        View searchCardView = findViewById(R.id.search_card);
        if (searchCardView != null) {
            searchCardView.setBackgroundTintList(null);
        }
    }

    private void applyFlatButtonStyle(MaterialButton button, int fillColor, int textColor, int strokeColor, int rippleColor, float radiusDp) {
        if (button == null) {
            return;
        }

        button.setBackgroundTintList(null);
        button.setBackground(buildFlatBackground(fillColor, strokeColor, rippleColor, radiusDp));
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setLetterSpacing(0.02f);
        button.setElevation(0f);
        button.setStateListAnimator(null);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setMinHeight(dp2px(56f));
        button.setMinimumHeight(dp2px(56f));
        button.setPadding(dp2px(18f), dp2px(10f), dp2px(18f), dp2px(10f));
    }

    private RippleDrawable buildFlatBackground(int fillColor, int strokeColor, int rippleColor, float radiusDp) {
        int radius = dp2px(radiusDp);
        int stroke = Math.max(1, dp2px(1f));

        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(radius);
        shape.setColor(fillColor);
        shape.setStroke(stroke, strokeColor);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(radius);
        mask.setColor(Color.WHITE);

        return new RippleDrawable(ColorStateList.valueOf(withAlpha(rippleColor, 32)), shape, mask);
    }

    private void applyCardShadow(CardView card, float radiusDp, float elevationDp) {
        if (card == null) {
            return;
        }

        int elevation = dp2px(elevationDp);
        card.setRadius(dp2px(radiusDp));
        card.setCardElevation(elevation);
        card.setMaxCardElevation(elevation);
        card.setUseCompatPadding(false);
        card.setPreventCornerOverlap(true);
        card.setTranslationZ(elevation);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            card.setOutlineAmbientShadowColor(withAlpha(Color.BLACK, 235));
            card.setOutlineSpotShadowColor(withAlpha(Color.BLACK, 255));
        }
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(
                Math.max(0, Math.min(255, alpha)),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }

    private boolean isDarkMode() {
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    private int resolveThemeResId(int attrResId) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attrResId, value, true)) {
            return value.resourceId;
        }
        return 0;
    }

    private boolean isSimulationRunning() {
        try {
            File file = new File(FILE_PATH);
            if (!file.exists() || !file.canRead()) {
                return false;
            }

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            String line = raf.readLine();
            raf.close();
            if (line == null || line.isEmpty()) {
                return false;
            }

            String[] parts = line.split(",");
            return parts.length >= 3 && "1".equals(parts[2]);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void loadDefaultLocation() {
        if (isSimulationRunning()) {
            syncSimulatedSelection(true);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREF_HISTORY, MODE_PRIVATE);
        float lat = prefs.getFloat(KEY_DEF_LAT, 0f);
        float lng = prefs.getFloat(KEY_DEF_LNG, 0f);
        String name = prefs.getString(KEY_DEF_NAME, getString(R.string.default_location));
        if (lat != 0f || lng != 0f) {
            applyHistorySelection(new HistoryItem(name, name, lat, lng));
        }
    }

    private void loadHistory() {
        historyItems.clear();
        if (historyContainer == null) {
            return;
        }
        historyContainer.removeAllViews();
        SharedPreferences prefs = getSharedPreferences(PREF_HISTORY, MODE_PRIVATE);
        String raw = prefs.getString(KEY_HISTORY, "");
        if (TextUtils.isEmpty(raw)) {
            return;
        }

        for (String line : raw.split("\n")) {
            HistoryItem item = HistoryItem.deserialize(line);
            if (item != null) {
                historyItems.add(item);
            }
        }

        for (HistoryItem item : historyItems) {
            addHistoryChip(item);
        }
    }

    private void persistHistory() {
        List<String> lines = new ArrayList<>();
        for (HistoryItem item : historyItems) {
            lines.add(item.serialize());
        }
        getSharedPreferences(PREF_HISTORY, MODE_PRIVATE)
                .edit()
                .putString(KEY_HISTORY, TextUtils.join("\n", lines))
                .apply();
    }

    private void saveHistory(HistoryItem item) {
        if (item == null || getString(R.string.manual_point).equals(item.name)) {
            return;
        }

        historyItems.removeIf(existing -> existing.sameAs(item));
        historyItems.add(0, item);
        if (historyItems.size() > MAX_HISTORY_ITEMS) {
            historyItems.subList(MAX_HISTORY_ITEMS, historyItems.size()).clear();
        }
        persistHistory();
        loadHistory();
    }

    private void deleteHistory(HistoryItem item) {
        if (item == null) {
            return;
        }
        historyItems.removeIf(existing -> existing.sameAs(item));
        persistHistory();
        loadHistory();
    }

    private void addHistoryChip(HistoryItem item) {
        MaterialCardView card = new MaterialCardView(this);
        int chipSurface = MaterialColors.getColor(historyContainer, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.WHITE);
        int chipText = MaterialColors.getColor(historyContainer, com.google.android.material.R.attr.colorOnSurface, Color.parseColor("#1A1C1E"));
        int chipMuted = MaterialColors.getColor(historyContainer, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#6B7280"));
        int chipStroke = withAlpha(MaterialColors.getColor(historyContainer, com.google.android.material.R.attr.colorOutlineVariant, Color.parseColor("#D9DDE3")), 170);

        card.setCardBackgroundColor(chipSurface);
        card.setRadius(dp2px(20f));
        card.setCardElevation(0f);
        card.setStrokeColor(chipStroke);
        card.setStrokeWidth(dp2px(1f));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp2px(4f), dp2px(12f), dp2px(4f));
        card.setLayoutParams(params);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp2px(14f), dp2px(8f), dp2px(10f), dp2px(8f));

        TextView title = new TextView(this);
        title.setText(item.name);
        title.setTextColor(chipText);
        title.setTextSize(14f);
        title.setTypeface(null, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TruncateAt.END);
        title.setMaxWidth(dp2px(88f));
        title.setOnClickListener(v -> handleHistorySelection(item));

        ImageView delete = new ImageView(this);
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp2px(16f), dp2px(16f));
        deleteParams.setMargins(dp2px(10f), 0, 0, 0);
        delete.setLayoutParams(deleteParams);
        delete.setScaleType(ImageView.ScaleType.FIT_CENTER);
        delete.setImageResource(R.drawable.ic_close_line);
        delete.setImageTintList(ColorStateList.valueOf(chipMuted));
        delete.setContentDescription(getString(R.string.delete_short));
        delete.setOnClickListener(v -> deleteHistory(item));

        layout.addView(title);
        layout.addView(delete);
        card.addView(layout);
        historyContainer.addView(card);
    }

    private void showHistoryDialog() {
        if (historyItems.isEmpty()) {
            Toast.makeText(this, R.string.no_history_records, Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_history_records, null, false);
        TextView title = dialogView.findViewById(R.id.tv_history_dialog_title);
        TextView subtitle = dialogView.findViewById(R.id.tv_history_dialog_subtitle);
        TextView btnClose = dialogView.findViewById(R.id.btn_history_dialog_close);
        LinearLayout container = dialogView.findViewById(R.id.history_dialog_container);

        int dialogBaseSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        int dialogSurfaceContainer = MaterialColors.getColor(container, com.google.android.material.R.attr.colorSurfaceContainerHigh, dialogBaseSurface);
        int dialogSurface = withAlpha(dialogSurfaceContainer, isDarkMode() ? 230 : 246);
        int itemSurface = dialogBaseSurface;
        int onSurface = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface, Color.parseColor("#1D1B20"));
        int onSurfaceVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.parseColor("#49454F"));
        int outlineVariant = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOutlineVariant, Color.parseColor("#CAC4D0"));
        int tertiary = MaterialColors.getColor(container, com.google.android.material.R.attr.colorTertiary, Color.parseColor("#7D5260"));

        if (dialogView instanceof MaterialCardView) {
            ((MaterialCardView) dialogView).setCardBackgroundColor(dialogSurface);
        }

        title.setTextColor(onSurface);
        subtitle.setTextColor(onSurfaceVariant);
        btnClose.setTextColor(tertiary);
        btnClose.setBackgroundResource(resolveThemeResId(android.R.attr.selectableItemBackgroundBorderless));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(dialogView).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        for (HistoryItem item : historyItems) {
            View itemView = LayoutInflater.from(this).inflate(R.layout.item_history_record, container, false);
            MaterialCardView card = itemView.findViewById(R.id.history_item_card);
            TextView name = itemView.findViewById(R.id.tv_history_item_name);
            TextView address = itemView.findViewById(R.id.tv_history_item_address);
            TextView hint = itemView.findViewById(R.id.tv_history_item_hint);

            card.setCardBackgroundColor(itemSurface);
            card.setStrokeColor(withAlpha(outlineVariant, 170));
            card.setStrokeWidth(dp2px(1f));
            name.setText(item.name);
            name.setTextColor(onSurface);
            address.setText(item.address);
            address.setTextColor(onSurfaceVariant);
            hint.setTextColor(tertiary);

            itemView.setOnClickListener(v -> {
                handleHistorySelection(item);
                dialog.dismiss();
            });
            itemView.setOnLongClickListener(v -> {
                showHistoryDetailDialog(item);
                return true;
            });
            container.addView(itemView);
        }

        btnClose.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showHistoryDetailDialog(HistoryItem item) {
        if (item == null) {
            return;
        }

        String message = getString(R.string.history_detail_message, item.address, item.lng, item.lat);
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.name)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private void applyHistorySelection(HistoryItem item) {
        if (item == null) {
            return;
        }
        if (aMap != null) {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(item.lat, item.lng), 16f));
        }
        updateSelection(item.lat, item.lng, item.name, item.address);
        saveHistory(item);
    }

    private void handleHistorySelection(HistoryItem item) {
        if (item == null) {
            return;
        }

        boolean wasSimulating = isSimulationRunning();
        applyHistorySelection(item);
        if (wasSimulating) {
            updateSimulationUi(false, currentName);
            writeToSystemTmp(STOP_SIMULATION_PAYLOAD, false, false);
        }
    }

    @Override
    public void onGetInputtips(List<Tip> tipList, int rCode) {
        if (rCode != 1000 || tipList == null) {
            return;
        }

        ArrayAdapter<Tip> adapter = new TipAdapter(tipList);
        etSearch.setAdapter(adapter);
        adapter.notifyDataSetChanged();
        runOnUiThread(() -> {
            if (etSearch.hasFocus() && etSearch.getText().length() > 0) {
                etSearch.showDropDown();
            }
        });
    }

    private void updateSelection(double lat, double lng, String title, String address) {
        selectLat = lat;
        selectLng = lng;
        currentName = safeText(title, getString(R.string.target_location));
        currentAddress = safeText(address, currentName);

        if (aMap != null) {
            aMap.clear();
            aMap.addMarker(new MarkerOptions().position(new LatLng(lat, lng)).title(currentName));
        }
        setStatusText(getString(R.string.selected_location, currentName));
        refreshStateSummary(currentName, isSimulationRunning());
    }

    private void refreshStateSummary(String locationText) {
        refreshStateSummary(locationText, isSimulationRunning());
    }

    private void refreshStateSummary(String locationText, boolean simRunning) {
        if (tvSimState == null) {
            return;
        }

        String location = safeText(locationText, "");
        if (!simRunning && !hasSelectedPoint()) {
            tvSimState.setText(R.string.sim_not_started);
        } else if (location.isEmpty()) {
            tvSimState.setText(simRunning ? R.string.sim_running : R.string.sim_not_running);
        } else {
            tvSimState.setText(getString(
                    simRunning ? R.string.sim_running_with_location : R.string.sim_not_running_with_location,
                    location
            ));
        }

        if (simDotView != null) {
            simDotView.setImageTintList(ColorStateList.valueOf(
                    simRunning ? SIM_DOT_COLOR_ACTIVE : SIM_DOT_COLOR_INACTIVE
            ));
        }
    }

    private void setStatusText(CharSequence text) {
        if (tvStatus == null) {
            return;
        }
        tvStatus.setText(text);
        tvStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
        tvStatus.setCompoundDrawablePadding(0);
    }

    private String buildAddressText(Tip tip) {
        String district = safeText(tip.getDistrict(), "");
        String address = safeText(tip.getAddress(), "");
        if (!district.isEmpty() && !address.isEmpty()) {
            return district + address;
        }
        if (!address.isEmpty()) {
            return address;
        }
        return !district.isEmpty() ? district : safeText(tip.getName(), getString(R.string.target_location));
    }

    private String safeText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim();
        return text.isEmpty() ? fallback : text;
    }

    private String encodeField(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String decodeField(String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private void initIdentity() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        fixedMac = prefs.getString("mac", "");
        fixedSSID = prefs.getString("ssid", "");
        if (fixedMac == null || fixedMac.isEmpty()) {
            fixedMac = generateRandomMac();
            fixedSSID = generateRandomSSID();
            prefs.edit().putString("mac", fixedMac).putString("ssid", fixedSSID).apply();
        }
    }

    private String generateRandomMac() {
        Random random = new Random();
        StringBuilder sb = new StringBuilder("ac");
        for (int i = 0; i < 5; i++) {
            sb.append(':');
            String hex = Integer.toHexString(random.nextInt(256));
            if (hex.length() < 2) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString().toLowerCase(Locale.US);
    }

    private String generateRandomSSID() {
        String[] brands = {"腾达", "小米", "华为", "访客网络"};
        return brands[new Random().nextInt(brands.length)] + "_" + (1000 + new Random().nextInt(9000));
    }

    private void writeToSystemTmp(String content, boolean isStart) {
        writeToSystemTmp(content, isStart, true);
    }

    private void writeToSystemTmp(String content, boolean isStart, boolean showToast) {
        new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec("su");
                DataOutputStream os = new DataOutputStream(process.getOutputStream());
                os.writeBytes("echo \"" + content + "\" > " + FILE_PATH + "\n");
                os.writeBytes("chmod 666 " + FILE_PATH + "\n");
                os.writeBytes("chcon u:object_r:shell_data_file:s0 " + FILE_PATH + "\n");
                os.writeBytes("exit\n");
                os.flush();
                int ret = process.waitFor();
                runOnUiThread(() -> {
                    if (ret == 0) {
                        if (showToast) {
                            Toast.makeText(
                                    this,
                                    isStart ? R.string.simulation_started : R.string.simulation_stopped,
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    } else {
                        Toast.makeText(this, R.string.root_auth_failed, Toast.LENGTH_SHORT).show();
                    }
                    refreshSimulationButton();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.write_config_failed, Toast.LENGTH_SHORT).show();
                    refreshSimulationButton();
                });
            }
        }).start();
    }

    private int dp2px(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapView != null) {
            try {
                mapView.onDestroy();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyEdgeToEdgeSystemBars();
        if (mapView != null) {
            try {
                mapView.onResume();
            } catch (Throwable ignored) {
            }
        }
        if (aMap != null && isSimulationRunning()) {
            syncSimulatedSelection(false);
        }
        loadHistory();
        refreshSimulationButton();
        View insetTarget = rootLayout != null ? rootLayout : bottomPanel;
        if (insetTarget != null) {
            ViewCompat.requestApplyInsets(insetTarget);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) {
            try {
                mapView.onPause();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mapView != null) {
            try {
                mapView.onSaveInstanceState(outState);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION_PERMISSION) {
            return;
        }

        boolean granted = false;
        for (int result : grantResults) {
            if (result == 0) {
                granted = true;
                break;
            }
        }

        if (granted) {
            goToCurrentLocation();
        } else {
            Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class SimulatedState {
        final double lat;
        final double lng;
        final String name;
        final String address;

        SimulatedState(double lat, double lng, String name, String address) {
            this.lat = lat;
            this.lng = lng;
            this.name = name;
            this.address = address;
        }
    }

    private static final class HistoryItem {
        final String name;
        final String address;
        final double lat;
        final double lng;

        HistoryItem(String name, String address, double lat, double lng) {
            this.name = name;
            this.address = address;
            this.lat = lat;
            this.lng = lng;
        }

        boolean sameAs(HistoryItem other) {
            return other != null
                    && name.equals(other.name)
                    && Math.abs(lat - other.lat) < 1e-7
                    && Math.abs(lng - other.lng) < 1e-7;
        }

        String serialize() {
            return encodeStatic(name) + "\t" + encodeStatic(address) + "\t" + lat + "\t" + lng;
        }

        static HistoryItem deserialize(String value) {
            if (TextUtils.isEmpty(value)) {
                return null;
            }

            try {
                String[] parts = value.split("\t");
                if (parts.length >= 4) {
                    return new HistoryItem(
                            decodeStatic(parts[0]),
                            decodeStatic(parts[1]),
                            Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3])
                    );
                }

                String[] legacy = value.split("\\|");
                if (legacy.length >= 3) {
                    String name = legacy[0];
                    double lat = Double.parseDouble(legacy[1]);
                    double lng = Double.parseDouble(legacy[2]);
                    String address = legacy.length >= 4 ? legacy[3] : name;
                    return new HistoryItem(name, address, lat, lng);
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        private static String encodeStatic(String value) {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
        }

        private static String decodeStatic(String value) {
            try {
                return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return value == null ? "" : value;
            }
        }
    }

    private final class TipAdapter extends ArrayAdapter<Tip> {

        TipAdapter(List<Tip> tips) {
            super(MainActivity.this, android.R.layout.simple_dropdown_item_1line, tips);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            TextView text = view.findViewById(android.R.id.text1);
            Tip tip = getItem(position);
            text.setText(tip == null ? "" : safeText(tip.getName(), ""));
            return view;
        }
    }
}
