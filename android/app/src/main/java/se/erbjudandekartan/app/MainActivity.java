package se.erbjudandekartan.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.PopupMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.util.LruCache;
import android.text.TextPaint;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.JavascriptInterface;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.text.Collator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_LOCATION = 42;
    private static final String LOCAL_API = "http://127.0.0.1:8700";
    private int GREEN;
    private int INK;
    private int MUTED;
    private int BACKGROUND;
    private int BORDER;
    private int SURFACE;
    private int SECONDARY_SURFACE;
    private int PRIMARY_BUTTON_TEXT;
    private String activeTheme = "janinis";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService imageExecutor = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(8 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return Math.max(1, bitmap.getByteCount() / 1024); }
    };
    private final Map<String, Chain> chains = new LinkedHashMap<>();
    private final List<JSONObject> allStores = new ArrayList<>();
    private final Map<String, JSONObject> offerCategoryByObject = new HashMap<>();
    private SharedPreferences preferences;
    private EditText citySearch;
    private LinearLayout chainGrid;
    private TextView connectionStatus;
    private ProgressBar loadingProgress;
    private ImageButton locationButton;
    private Double userLat;
    private Double userLng;
    private boolean openMapAfterLocation;

    private static class Chain {
        final String id;
        final String name;
        final String emoji;
        final int color;
        final boolean offers;

        Chain(String id, String name, String emoji, int color, boolean offers) {
            this.id = id;
            this.name = name;
            this.emoji = emoji;
            this.color = color;
            this.offers = offers;
        }
    }

    private final class StoreMapJavascriptBridge {
        private final Dialog dialog;
        private final List<JSONObject> mapStores;

        StoreMapJavascriptBridge(Dialog dialog, List<JSONObject> mapStores) {
            this.dialog = dialog;
            this.mapStores = mapStores;
        }

        @JavascriptInterface
        public void openStore(int index) {
            runOnUiThread(() -> {
                if (index < 0 || index >= mapStores.size()) return;
                JSONObject store = mapStores.get(index);
                String chainId = store.optString("chain", "");
                if (chainId.equals("city_gross")) chainId = "citygross";
                Chain chain = chains.get(chainId);
                if (chain == null) chain = new Chain(chainId, chainId, "🏬", Color.rgb(104, 117, 109), false);
                dialog.dismiss();
                MainActivity.this.openStore(chain, store);
            });
        }
    }

    private static class OutlinedTextView extends TextView {
        private int outlineColor = Color.BLACK;
        private final float outlineWidth;

        OutlinedTextView(Context context) {
            super(context);
            outlineWidth = 1.6f * getResources().getDisplayMetrics().density;
        }

        void setOutlineColor(int color) {
            outlineColor = color;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            TextPaint paint = getPaint();
            int textColor = getCurrentTextColor();
            Paint.Style oldStyle = paint.getStyle();
            float oldWidth = paint.getStrokeWidth();
            int oldColor = paint.getColor();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(outlineWidth);
            paint.setColor(outlineColor);
            super.onDraw(canvas);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(textColor);
            super.onDraw(canvas);
            paint.setStyle(oldStyle);
            paint.setStrokeWidth(oldWidth);
            paint.setColor(oldColor);
        }
    }

    private static class PaperTextureDrawable extends Drawable {
        private final int backgroundColor;
        private final int grainColor;
        private final float density;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] xs = new float[0];
        private float[] ys = new float[0];
        private float[] radii = new float[0];
        private int[] colors = new int[0];

        PaperTextureDrawable(int backgroundColor, int grainColor, float density) {
            this.backgroundColor = backgroundColor;
            this.grainColor = grainColor;
            this.density = density;
        }

        @Override protected void onBoundsChange(Rect bounds) {
            int spacing = Math.max(1, Math.round(12 * density));
            int count = Math.max(600, Math.min(8000, bounds.width() * bounds.height() / (spacing * spacing)));
            xs = new float[count];
            ys = new float[count];
            radii = new float[count];
            colors = new int[count];
            Random random = new Random(417);
            for (int i = 0; i < count; i++) {
                xs[i] = bounds.left + random.nextFloat() * bounds.width();
                ys[i] = bounds.top + random.nextFloat() * bounds.height();
                radii[i] = (0.35f + random.nextFloat() * 1.0f) * density;
                colors[i] = Color.argb(10 + random.nextInt(16), Color.red(grainColor), Color.green(grainColor), Color.blue(grainColor));
            }
        }

        @Override public void draw(Canvas canvas) {
            canvas.drawColor(backgroundColor);
            for (int i = 0; i < xs.length; i++) {
                paint.setColor(colors[i]);
                canvas.drawCircle(xs[i], ys[i], radii[i], paint);
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("offer_map", MODE_PRIVATE);
        activeTheme = preferences.getString("app_theme", "janinis");
        updateThemeColors();
        restoreCachedStores();
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        java.net.CookieHandler.setDefault(cookieManager);
        initializeChains();
        buildScreen();
        locationButton.post(this::requestLocation);
        startEmbeddedApi();
    }

    private void initializeChains() {
        chains.put("ica", new Chain("ica", "ICA", "🔴", Color.rgb(217, 75, 69), true));
        chains.put("coop", new Chain("coop", "Coop", "🟢", Color.rgb(40, 140, 90), true));
        chains.put("willys", new Chain("willys", "Willys", "🟡", Color.rgb(211, 155, 24), true));
        chains.put("hemkop", new Chain("hemkop", "Hemköp", "🟠", Color.rgb(213, 116, 50), true));
        chains.put("lidl", new Chain("lidl", "Lidl", "🔵", Color.rgb(65, 104, 168), false));
        chains.put("citygross", new Chain("citygross", "City Gross", "🟣", Color.rgb(119, 86, 166), false));
    }

    private void updateThemeColors() {
        if ("cybercat".equals(activeTheme)) {
            GREEN = Color.rgb(222, 79, 255);
            INK = Color.rgb(250, 241, 255);
            MUTED = Color.rgb(196, 170, 215);
            BACKGROUND = Color.rgb(17, 10, 29);
            BORDER = Color.rgb(103, 61, 130);
            SURFACE = Color.rgb(34, 21, 49);
            SECONDARY_SURFACE = Color.rgb(52, 31, 72);
            PRIMARY_BUTTON_TEXT = Color.rgb(29, 10, 39);
        } else {
            GREEN = Color.rgb(166, 105, 0);
            INK = Color.rgb(69, 47, 8);
            MUTED = Color.rgb(119, 91, 42);
            BACKGROUND = Color.rgb(255, 245, 211);
            BORDER = Color.rgb(213, 176, 69);
            SURFACE = Color.rgb(255, 252, 240);
            SECONDARY_SURFACE = Color.rgb(255, 230, 157);
            PRIMARY_BUTTON_TEXT = Color.rgb(59, 39, 0);
        }
    }

    private void buildScreen() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        int grainColor = "cybercat".equals(activeTheme) ? Color.rgb(255, 198, 255) : Color.rgb(113, 78, 25);
        page.setBackground(new PaperTextureDrawable(BACKGROUND, grainColor, getResources().getDisplayMetrics().density));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(28));
        page.addView(content);

        LinearLayout headingRow = new LinearLayout(this);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView eyebrow = label("VECKANS MATFYND", 12, GREEN, true);
        headingRow.addView(eyebrow, new LinearLayout.LayoutParams(0, -2, 1));
        TextView themeButton = label("🎨", 21, INK, false);
        themeButton.setGravity(Gravity.CENTER);
        themeButton.setContentDescription("Byt tema");
        themeButton.setBackground(background(SECONDARY_SURFACE, BORDER, 14));
        headingRow.addView(themeButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
        themeButton.setOnClickListener(v -> showThemePicker());
        content.addView(headingRow, margin(0, 2, 0, 5));
        content.addView(label("Erbjudandekartan", 29, INK, true), margin(0, 0, 0, 4));
        content.addView(label("Erbjudanden nära dig, samlade på ett ställe.", 15, MUTED, false), margin(0, 0, 0, 18));

        LinearLayout connection = card();
        content.addView(connection, margin(0, 0, 0, 14));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        ImageButton connect = iconButton(android.R.drawable.ic_popup_sync, true, "Uppdatera erbjudanden");
        controls.addView(connect, new LinearLayout.LayoutParams(dp(48), dp(48)));
        locationButton = iconButton(android.R.drawable.ic_menu_mylocation, false, "Visa min plats");
        locationButton.setColorFilter(userLat != null && userLng != null ? GREEN : MUTED);
        LinearLayout.LayoutParams locationParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        locationParams.leftMargin = dp(8);
        controls.addView(locationButton, locationParams);
        OutlinedTextView mapButton = new OutlinedTextView(this);
        mapButton.setText("⌖  Visa alla butiker  ⌖");
        mapButton.setTextSize(13);
        mapButton.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        mapButton.setLetterSpacing(0.01f);
        mapButton.setTextColor(GREEN);
        mapButton.setOutlineColor(INK);
        mapButton.setGravity(Gravity.CENTER);
        mapButton.setPadding(dp(8), dp(10), dp(8), dp(10));
        mapButton.setBackground(background(SECONDARY_SURFACE, GREEN, 14));
        mapButton.setClickable(true);
        mapButton.setFocusable(true);
        mapButton.setContentDescription("Visa alla butiker på kartan");
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        mapParams.leftMargin = dp(8);
        controls.addView(mapButton, mapParams);
        connection.addView(controls, margin(0, 0, 0, 0));

        citySearch = new EditText(this);
        citySearch.setSingleLine(true);
        citySearch.setTextSize(16);
        citySearch.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        citySearch.setHint("Sök efter stad");
        citySearch.setPadding(dp(15), 0, dp(15), 0);
        citySearch.setMinHeight(dp(52));
        citySearch.setTextColor(INK);
        citySearch.setHintTextColor(MUTED);
        citySearch.setBackground(background(SURFACE, GREEN, 16));
        connection.addView(citySearch, margin(0, 12, 0, 0));
        citySearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderChains(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER);
        loadingProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        loadingProgress.setIndeterminate(true);
        loadingProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GREEN));
        statusRow.addView(loadingProgress, new LinearLayout.LayoutParams(dp(22), dp(22)));
        OutlinedTextView countStatus = new OutlinedTextView(this);
        countStatus.setTextSize(14);
        countStatus.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        countStatus.setLetterSpacing(0.04f);
        countStatus.setTextColor(GREEN);
        countStatus.setOutlineColor(INK);
        countStatus.setGravity(Gravity.CENTER);
        countStatus.setPadding(dp(12), dp(6), dp(12), dp(6));
        countStatus.setBackground(background(SECONDARY_SURFACE, GREEN, 14));
        countStatus.setVisibility(View.GONE);
        connectionStatus = countStatus;
        if (!allStores.isEmpty()) {
            connectionStatus.setText("✦  " + allStores.size() + " BUTIKER HITTADES  ✦");
            connectionStatus.setVisibility(View.VISIBLE);
        }
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.leftMargin = dp(8);
        statusRow.addView(connectionStatus, statusParams);
        connection.addView(statusRow, margin(0, 10, 0, 0));
        connect.setOnClickListener(v -> connectAndLoad(true));
        locationButton.setOnClickListener(v -> requestLocation());

        mapButton.setOnClickListener(v -> openStoreMap());

        TextView listHeader = label("Välj matbutik", 20, INK, true);
        content.addView(listHeader, margin(0, 0, 0, 8));
        chainGrid = new LinearLayout(this);
        chainGrid.setOrientation(LinearLayout.VERTICAL);
        content.addView(chainGrid);
        content.addView(label("Avstånd visas ungefärligt fågelvägen. Erbjudanden och giltighetstid visas när källan innehåller uppgifterna.", 12, MUTED, false), margin(0, 12, 0, 0));
        setContentView(page);
        renderChains();
    }

    private void showThemePicker() {
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(16));
        root.setBackground(background(SURFACE, BORDER, 20));
        root.addView(label("Välj tema", 22, INK, true), margin(0, 0, 0, 5));
        root.addView(label("Välj färgvärlden du vill handla i.", 13, MUTED, false), margin(0, 0, 0, 14));
        root.addView(themeOption(dialog, "janinis", "🌼  Janinis", "Solgul, varm hippiestil med mjuka naturtoner.",
                new int[]{Color.rgb(255, 218, 105), Color.rgb(166, 105, 0), Color.rgb(151, 127, 55)}), margin(0, 0, 0, 10));
        root.addView(themeOption(dialog, "cybercat", "🐈  Cybercat", "Cyberpunk med neonlila, rosa och nattmörker.",
                new int[]{Color.rgb(34, 16, 54), Color.rgb(222, 79, 255), Color.rgb(45, 220, 255)}), margin(0, 0, 0, 12));
        Button close = secondaryButton("Stäng");
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(close);
        dialog.setContentView(root);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(40), -2);
        }
    }

    private View themeOption(Dialog dialog, String themeId, String title, String description, int[] swatchColors) {
        LinearLayout option = new LinearLayout(this);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding(dp(13), dp(12), dp(13), dp(12));
        option.setBackground(background(SECONDARY_SURFACE, BORDER, 15));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.addView(label(title, 17, INK, true));
        details.addView(label(description, 12, MUTED, false), margin(0, 3, 0, 0));
        option.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout swatches = new LinearLayout(this);
        swatches.setGravity(Gravity.CENTER_VERTICAL);
        for (int color : swatchColors) {
            View swatch = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(15), dp(25));
            params.leftMargin = dp(3);
            swatch.setBackground(background(color, color, 8));
            swatches.addView(swatch, params);
        }
        option.addView(swatches);
        option.setOnClickListener(v -> {
            dialog.dismiss();
            selectTheme(themeId);
        });
        return option;
    }

    private void selectTheme(String themeId) {
        if (themeId.equals(activeTheme)) return;
        String previousStatus = connectionStatus == null ? "" : connectionStatus.getText().toString();
        String previousCity = citySearch == null ? "" : citySearch.getText().toString();
        int previousLoading = loadingProgress == null ? View.VISIBLE : loadingProgress.getVisibility();
        activeTheme = themeId;
        preferences.edit().putString("app_theme", themeId).apply();
        updateThemeColors();
        buildScreen();
        citySearch.setText(previousCity);
        connectionStatus.setText(previousStatus);
        setLoading(previousLoading == View.VISIBLE);
    }

    private ArrayAdapter<String> themedSpinnerAdapter(List<String> items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                if (view instanceof TextView) ((TextView) view).setTextColor(INK);
                return view;
            }

            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                if (view instanceof TextView) {
                    ((TextView) view).setTextColor(INK);
                    view.setBackgroundColor(SURFACE);
                }
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void startEmbeddedApi() {
        setLoading(true);
        executor.execute(() -> {
            try {
                File runtime = new File(getFilesDir(), "fyndkartan-runtime");
                copyRuntimeAssets(runtime);
                File database = new File(runtime, "stores-public.db");
                if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
                Python.getInstance().getModule("mobile_server").callAttr("start", runtime.getAbsolutePath(), database.getAbsolutePath());
                boolean ready = false;
                for (int attempt = 0; attempt < 120; attempt++) {
                    if (isLocalApiReady()) { ready = true; break; }
                    Thread.sleep(500);
                }
                if (!ready) throw new IOException("Fyndkartan svarade inte efter uppstart.");
                runOnUiThread(() -> {
                    connectAndLoad(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    connectionStatus.setText("Kunde inte starta Fyndkartan: " + e.getMessage());
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        if (loadingProgress != null) loadingProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (connectionStatus != null) {
            boolean hasCachedStores = !allStores.isEmpty();
            if (loading && !hasCachedStores) connectionStatus.setText("");
            connectionStatus.setVisibility(loading && !hasCachedStores ? View.GONE : View.VISIBLE);
        }
    }

    private void restoreCachedStores() {
        String cachedResponse = preferences.getString("cached_stores_response", null);
        if (cachedResponse == null) return;
        try {
            JSONArray stores = new JSONObject(cachedResponse).optJSONArray("stores");
            if (stores == null) return;
            for (int i = 0; i < stores.length(); i++) {
                JSONObject store = stores.optJSONObject(i);
                if (store != null) allStores.add(store);
            }
        } catch (JSONException ignored) {
            preferences.edit().remove("cached_stores_response").apply();
        }
    }

    private void copyRuntimeAssets(File runtime) throws IOException {
        long installedAt;
        try {
            installedAt = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Kunde inte läsa appversionen.", e);
        }
        long copiedAt = preferences.getLong("runtime_assets_installed_at", -1);
        if (runtime.isDirectory() && copiedAt == installedAt) return;
        copyAssetTree("fyndkartan-runtime", runtime);
        preferences.edit().putLong("runtime_assets_installed_at", installedAt).apply();
    }

    private void copyAssetTree(String assetPath, File destination) throws IOException {
        String[] children = getAssets().list(assetPath);
        if (children == null) return;
        if (children.length > 0) {
            if (!destination.exists() && !destination.mkdirs()) throw new IOException("Kunde inte skapa appens datamapp.");
            for (String child : children) copyAssetTree(assetPath + "/" + child, new File(destination, child));
            return;
        }
        if ((destination.getName().equals("stores.db") || destination.getName().equals("stores-public.db")) && destination.exists()) return;
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Kunde inte skapa appens datamapp.");
        try (InputStream input = getAssets().open(assetPath); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private boolean isLocalApiReady() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(LOCAL_API + "/healthz").openConnection();
            connection.setConnectTimeout(700);
            connection.setReadTimeout(700);
            return connection.getResponseCode() == 200;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void connectAndLoad(boolean showMessage) {
        String base = LOCAL_API;
        setLoading(true);
        executor.execute(() -> {
            try {
                ensureLogin(base);
                JSONObject response = apiGet(base, "/v1/stores");
                JSONArray stores = response.optJSONArray("stores");
                List<JSONObject> loaded = new ArrayList<>();
                if (stores != null) for (int i = 0; i < stores.length(); i++) loaded.add(stores.optJSONObject(i));
                runOnUiThread(() -> {
                    allStores.clear();
                    allStores.addAll(loaded);
                    preferences.edit().putString("cached_stores_response", response.toString()).apply();
                    setLoading(false);
                    connectionStatus.setText("✦  " + allStores.size() + " BUTIKER HITTADES  ✦");
                    renderChains();
                    if (showMessage) Toast.makeText(this, "Butikerna är uppdaterade", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    connectionStatus.setText("Kunde inte hämta data: " + e.getMessage());
                });
            }
        });
    }

    private void ensureLogin(String base) throws Exception {
        String email = preferences.getString("account_email", null);
        String password = preferences.getString("account_password", null);
        boolean newAccount = email == null || password == null;
        if (newAccount) {
            email = "erbjudandekartan-" + UUID.randomUUID().toString().replace("-", "") + "@example.com";
            password = UUID.randomUUID().toString() + UUID.randomUUID();
            preferences.edit().putString("account_email", email).putString("account_password", password).apply();
        }
        JSONObject credentials = new JSONObject();
        credentials.put("email", email);
        credentials.put("password", password);
        if (!newAccount) {
            try {
                request(base, "/v1/auth/login", "POST", credentials);
                return;
            } catch (IOException ignored) {
                // Register again below if the local API database was reset.
            }
        }
        try {
            request(base, "/v1/auth/register", "POST", credentials);
        } catch (IOException e) {
            if (e.getMessage() == null || !e.getMessage().contains("409")) throw e;
            request(base, "/v1/auth/login", "POST", credentials);
        }
    }

    private JSONObject apiGet(String base, String path) throws Exception {
        try {
            return request(base, path, "GET", null);
        } catch (IOException e) {
            if (e.getMessage() == null || !e.getMessage().contains("401")) throw e;
            ensureLogin(base);
            return request(base, path, "GET", null);
        }
    }

    private JSONObject request(String base, String path, String method, JSONObject body) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(base + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(25000);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readAll(stream);
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status + (text.isEmpty() ? "" : ": " + text));
            try {
                return text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (JSONException e) {
                throw new IOException("API:t skickade ett oväntat svar.", e);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readAll(InputStream stream) throws IOException {
        StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) value.append(line);
        }
        return value.toString();
    }

    private void renderChains() {
        if (chainGrid == null) return;
        chainGrid.removeAllViews();
        String city = citySearch == null ? "" : citySearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<Chain> items = new ArrayList<>(chains.values());
        for (int index = 0; index < items.size(); index += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = index; column < Math.min(index + 2, items.size()); column++) {
                Chain chain = items.get(column);
                List<JSONObject> stores = storesFor(chain.id, city);
                LinearLayout tile = card();
                tile.setMinimumHeight(dp(128));
                tile.setGravity(Gravity.CENTER_VERTICAL);
                tile.addView(label(chain.emoji + "  " + chain.name, 19, INK, true), margin(0, 0, 0, 5));
                tile.addView(label(stores.size() + " butiker", 13, MUTED, false), margin(0, 0, 0, 8));
                if (!stores.isEmpty() && stores.get(0).has("distance_km")) {
                    tile.addView(label(String.format(Locale.getDefault(), "Närmast %.1f km", stores.get(0).optDouble("distance_km")), 12, GREEN, true), margin(0, 0, 0, 6));
                }
                TextView status = label(chain.offers ? "Erbjudanden" : "Se butikens reklamblad", 11, chain.offers ? GREEN : MUTED, true);
                tile.addView(status);
                tile.setOnClickListener(v -> openChain(chain, stores));
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, -2, 1);
                tileParams.rightMargin = column == index ? dp(7) : 0;
                tileParams.leftMargin = column == index ? 0 : dp(7);
                tileParams.bottomMargin = dp(12);
                row.addView(tile, tileParams);
            }
            if (items.size() % 2 == 1 && index + 2 >= items.size()) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            }
            chainGrid.addView(row);
        }
    }

    private void openStoreMap() {
        if (allStores.isEmpty()) {
            Toast.makeText(this, "Vänta tills butikerna har lästs in.", Toast.LENGTH_SHORT).show();
            return;
        }
        openMapAfterLocation = true;
        requestLocation();
    }

    private void showStoreMap() {
        JSONArray points = new JSONArray();
        JSONArray chainItems = new JSONArray();
        List<JSONObject> mapStores = new ArrayList<>();
        Map<String, Chain> shownChains = new LinkedHashMap<>();
        for (JSONObject store : allStores) {
            JSONObject location = store.optJSONObject("location");
            if (location == null) continue;
            double lat = location.optDouble("lat", Double.NaN);
            double lng = location.optDouble("lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) continue;

            String chainId = store.optString("chain", "");
            if (chainId.equals("city_gross")) chainId = "citygross";
            Chain chain = chains.get(chainId);
            if (chain == null) chain = new Chain(chainId, chainId, "🏬", Color.rgb(104, 117, 109), false);
            String color = String.format(Locale.ROOT, "#%06X", 0xFFFFFF & chain.color);
            JSONObject point = new JSONObject();
            try {
                point.put("lat", lat);
                point.put("lng", lng);
                point.put("name", store.optString("name", "Butik"));
                point.put("city", storeCity(store));
                point.put("chain", chain.name);
                point.put("chain_id", chainId);
                point.put("color", color);
                point.put("index", mapStores.size());
                points.put(point);
                mapStores.add(store);
                shownChains.put(chainId, chain);
            } catch (JSONException ignored) {}
        }
        if (points.length() == 0) {
            Toast.makeText(this, "Det finns inga butiker med kartpositioner ännu.", Toast.LENGTH_LONG).show();
            return;
        }

        for (Chain chain : shownChains.values()) {
            String color = String.format(Locale.ROOT, "#%06X", 0xFFFFFF & chain.color);
            JSONObject item = new JSONObject();
            try {
                item.put("id", chain.id);
                item.put("name", chain.name);
                item.put("color", color);
                chainItems.put(item);
            } catch (JSONException ignored) {}
        }
        String initialMapView;
        if (userLat != null && userLng != null) {
            initialMapView = "map.setView([" + userLat + "," + userLng + "],12);const userIcon=L.divIcon({className:'',"
                    + "html:'<div class=\"user-pin\"><svg viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"8\" r=\"4\" fill=\"white\"/>"
                    + "<path d=\"M4 21v-1a8 8 0 0 1 16 0v1z\" fill=\"white\"/></svg></div>',iconSize:[36,36],iconAnchor:[18,34]});"
                    + "L.marker([" + userLat + "," + userLng + "],{icon:userIcon,zIndexOffset:1000}).addTo(map).bindPopup('Du är här');";
        } else {
            initialMapView = "if(bounds.length===1)map.setView(bounds[0],12);else map.fitBounds(bounds,{padding:[24,24],maxZoom:12});";
        }
        String html = "<!doctype html><html lang=\"sv\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1\">"
                + "<link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\">"
                + "<style>html,body{height:100%;margin:0;font-family:Arial,sans-serif}#map{height:calc(100% - 88px);width:100%}"
                + "#legend{height:88px;box-sizing:border-box;padding:6px 9px;display:flex;flex-wrap:wrap;align-content:center;gap:5px 12px;"
                + "font-size:12px;background:#fff;color:#26352e;overflow:auto}#legend label{display:inline-flex;align-items:center;gap:4px;white-space:nowrap}"
                + "#legend i{display:inline-block;width:11px;height:11px;border-radius:50%}.user-pin{width:34px;height:34px;box-sizing:border-box;"
                + "background:#1268d8;border:3px solid #fff;border-radius:50% 50% 50% 0;transform:rotate(-45deg);box-shadow:0 2px 7px #3339;"
                + "display:flex;align-items:center;justify-content:center}.user-pin svg{width:19px;height:19px;transform:rotate(45deg)}"
                + ".brand-icon{box-sizing:border-box;min-width:42px;height:30px;padding:0 5px;border:2px solid #fff;border-radius:9px;"
                + "box-shadow:0 2px 6px #26352e88;display:flex;align-items:center;justify-content:center;font-family:Arial,sans-serif;"
                + "font-size:11px;font-weight:900;white-space:nowrap}.brand-ica{background:#df1021;color:#fff;border-radius:50%;font-size:15px;font-style:italic}"
                + ".brand-willys{background:#ffdf00;color:#185b2b;border-color:#fff;font-size:11px;border-radius:50%}"
                + ".brand-coop{background:#087b49;color:#fff;border-radius:50%;font-size:13px}"
                + ".brand-lidl{background:#ffdf00;color:#e52232;border:3px solid #0754a5;border-radius:6px;font-style:italic}"
                + ".brand-hemkop{background:#fff;color:#d51d2a;border-color:#d51d2a;font-size:10px}"
                + ".brand-citygross{background:#25496f;color:#fff;font-size:9px}</style></head><body>"
                + "<div id=\"map\"></div><div id=\"legend\"></div>"
                + "<script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script><script>"
                + "const stores=" + points + ";const chains=" + chainItems + ";const map=L.map('map');"
                + "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,"
                + "attribution:'&copy; OpenStreetMap'}).addTo(map);"
                + "const bounds=[];const markers=[];const active=new Set(chains.map(c=>c.id));"
                + "chains.forEach(c=>{const label=document.createElement('label');const box=document.createElement('input');box.type='checkbox';box.checked=true;"
                + "const dot=document.createElement('i');dot.style.background=c.color;const name=document.createElement('span');name.textContent=c.name;"
                + "label.append(box,dot,name);document.getElementById('legend').appendChild(label);box.addEventListener('change',()=>{"
                + "if(box.checked)active.add(c.id);else active.delete(c.id);markers.filter(m=>m.chain===c.id).forEach(m=>{"
                + "if(active.has(c.id))m.marker.addTo(map);else map.removeLayer(m.marker);});});});"
                + "function brandIcon(s){const styles={ica:['brand-ica','ICA'],willys:['brand-willys','willys'],"
                + "coop:['brand-coop','coop'],lidl:['brand-lidl','Lidl'],hemkop:['brand-hemkop','Hemköp'],"
                + "citygross:['brand-citygross','City Gross']};const item=styles[s.chain_id]||['','?'];"
                + "const html='<div class=\"brand-icon '+item[0]+'\" style=\"'+(item[0]?'':'background:'+s.color+';color:#fff')+'\">'+item[1]+'</div>';"
                + "return L.divIcon({className:'',html:html,iconSize:[48,34],iconAnchor:[24,17]});}"
                + "stores.forEach(s=>{const p=[s.lat,s.lng];bounds.push(p);const marker=L.marker(p,{icon:brandIcon(s),title:s.name,keyboard:true});"
                + "marker.on('click',()=>AndroidMap.openStore(s.index));"
                + "if(active.has(s.chain_id))marker.addTo(map);markers.push({marker:marker,chain:s.chain_id});});"
                + initialMapView + "</script></body></html>";

        WebView mapView = new WebView(this);
        WebSettings settings = mapView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        Dialog dialog = new Dialog(this);
        mapView.addJavascriptInterface(new StoreMapJavascriptBridge(dialog, mapStores), "AndroidMap");
        mapView.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(12));
        root.setBackground(background(SURFACE, BORDER, 20));
        root.addView(label("Alla butiker på kartan", 20, INK, true), margin(0, 0, 0, 3));
        int withoutLocation = Math.max(0, allStores.size() - points.length());
        String mapSummary = points.length() + " butiker på kartan · färg visar kedja"
                + (withoutLocation == 0 ? "" : " · " + withoutLocation + " saknar position");
        root.addView(label(mapSummary, 12, MUTED, false), margin(0, 0, 0, 8));
        root.addView(mapView, new LinearLayout.LayoutParams(-1, 0, 1));
        Button close = secondaryButton("Stäng");
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(close, margin(0, 8, 0, 0));
        dialog.setContentView(root);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(24),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.86));
        }
    }

    private List<JSONObject> storesFor(String chain, String cityFilter) {
        List<JSONObject> matches = new ArrayList<>();
        for (JSONObject store : allStores) {
            String code = store.optString("chain", "");
            if (!(code.equals(chain) || (chain.equals("citygross") && code.equals("city_gross")))) continue;
            String city = store.optJSONObject("address") == null ? "" : store.optJSONObject("address").optString("city", "");
            if (!cityFilter.isEmpty() && !city.toLowerCase(Locale.ROOT).contains(cityFilter)) continue;
            if (userLat != null && userLng != null) {
                JSONObject loc = store.optJSONObject("location");
                if (loc != null && loc.has("lat") && loc.has("lng")) {
                    store.remove("distance_km");
                    try { store.put("distance_km", distanceKm(userLat, userLng, loc.getDouble("lat"), loc.getDouble("lng"))); }
                    catch (JSONException ignored) {}
                }
            }
            matches.add(store);
        }
        boolean nearestFirst = userLat != null && userLng != null;
        Collator swedish = Collator.getInstance(new Locale("sv", "SE"));
        if (nearestFirst) {
            matches.sort(Comparator.comparingDouble(s -> s.optDouble("distance_km", Double.MAX_VALUE)));
        } else {
            matches.sort((left, right) -> {
                int byCity = swedish.compare(storeCity(left), storeCity(right));
                if (byCity != 0) return byCity;
                return swedish.compare(left.optString("name", ""), right.optString("name", ""));
            });
        }
        return matches;
    }

    private String storeCity(JSONObject store) {
        JSONObject address = store.optJSONObject("address");
        return address == null ? "" : address.optString("city", "").trim();
    }

    private void openChain(Chain chain, List<JSONObject> stores) {
        if (stores.isEmpty()) {
            Toast.makeText(this, "Inga " + chain.name + "-butiker hittades med det filtret", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout picker = new LinearLayout(this);
        picker.setOrientation(LinearLayout.VERTICAL);
        picker.setPadding(dp(20), dp(8), dp(20), dp(8));
        picker.addView(label("Visa butiker i stad", 13, MUTED, true), margin(0, 0, 0, 4));

        List<String> cities = new ArrayList<>();
        cities.add("Alla städer");
        for (JSONObject store : stores) {
            String city = storeCity(store);
            if (city.isEmpty()) city = "Okänd stad";
            if (!cities.contains(city)) cities.add(city);
        }
        Collator swedish = Collator.getInstance(new Locale("sv", "SE"));
        Collections.sort(cities.subList(1, cities.size()), swedish);
        Spinner cityPicker = new Spinner(this);
        ArrayAdapter<String> cityAdapter = themedSpinnerAdapter(cities);
        cityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cityPicker.setAdapter(cityAdapter);
        cityPicker.setBackground(background(SURFACE, BORDER, 12));
        picker.addView(cityPicker, margin(0, 0, 0, 8));

        ListView storeList = new ListView(this);
        List<JSONObject> visibleStores = new ArrayList<>();
        ArrayAdapter<String> storeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        storeList.setAdapter(storeAdapter);
        picker.addView(storeList, new LinearLayout.LayoutParams(-1, dp(360)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(chain.emoji + "  Välj " + chain.name + "-butik")
                .setView(picker)
                .setNegativeButton("Stäng", null)
                .create();

        Runnable updateStores = () -> {
            String selectedCity = String.valueOf(cityPicker.getSelectedItem());
            visibleStores.clear();
            storeAdapter.clear();
            for (JSONObject store : stores) {
                String city = storeCity(store);
                String cityLabel = city.isEmpty() ? "Okänd stad" : city;
                if (!selectedCity.equals("Alla städer") && !selectedCity.equals(cityLabel)) continue;
                visibleStores.add(store);
                String distance = store.has("distance_km") ? String.format(Locale.getDefault(), " · %.1f km", store.optDouble("distance_km")) : "";
                storeAdapter.add(store.optString("name", "Butik") + (city.isEmpty() ? "" : " · " + city) + distance);
            }
            if (visibleStores.isEmpty()) storeAdapter.add("Inga butiker i den här staden");
        };
        cityPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { updateStores.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        updateStores.run();
        storeList.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= visibleStores.size()) return;
            JSONObject selectedStore = visibleStores.get(position);
            dialog.dismiss();
            openStore(chain, selectedStore);
        });
        dialog.show();
        dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(40), -2);
    }

    private void openStore(Chain chain, JSONObject store) {
        if (!chain.offers) {
            JSONObject links = store.optJSONObject("links");
            String offersUrl = links == null ? "" : links.optString("offers", "");
            if (offersUrl.trim().isEmpty() || offersUrl.trim().equalsIgnoreCase("null")) {
                offersUrl = chain.id.equals("lidl") ? "https://www.lidl.se/c/reklamblad/s10018018" : "";
            }
            final String flyerUrl = offersUrl;
            String message = chain.id.equals("lidl")
                    ? "Fyndkartan hämtar inte Lidls erbjudanden direkt. Reklambladet gäller alla butiker; välj din butik på Lidls sida för lokala erbjudanden."
                    : "Fyndkartan har ännu inga hämtade erbjudanden för den här kedjan.";
            AlertDialog.Builder info = new AlertDialog.Builder(this)
                    .setTitle(chain.name)
                    .setMessage(message);
            if (!flyerUrl.isEmpty()) info.setPositiveButton("Öppna reklamblad", (d, w) -> openFlyer(flyerUrl));
            info.setNegativeButton("Stäng", null).show();
            return;
        }
        String base = LOCAL_API;
        String path = "/v1/stores/" + Uri.encode(store.optString("chain", chain.id)) + "/" + Uri.encode(store.optString("store_id", "")) + "/offers";
        Dialog progress = new AlertDialog.Builder(this).setMessage("Hämtar aktuella erbjudanden…").create();
        progress.show();
        executor.execute(() -> {
            try {
                JSONObject result = apiGet(base, path);
                JSONArray offers = result.optJSONArray("offers");
                List<JSONObject> items = new ArrayList<>();
                if (offers != null) for (int i = 0; i < offers.length(); i++) items.add(offers.optJSONObject(i));
                runOnUiThread(() -> {
                    progress.dismiss();
                    showOffers(chain, store, items, result.optString("note", ""));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    new AlertDialog.Builder(this).setTitle("Kunde inte hämta erbjudanden").setMessage(e.getMessage()).setPositiveButton("Stäng", null).show();
                });
            }
        });
    }

    private void openFlyer(String rawUrl) {
        String url = rawUrl == null ? "" : rawUrl.trim();
        if (url.isEmpty() || url.equalsIgnoreCase("null")) {
            Toast.makeText(this, "Butiken har ingen giltig länk till reklambladet.", Toast.LENGTH_LONG).show();
            return;
        }
        if (url.startsWith("//")) url = "https:" + url;
        else if (!url.matches("(?i)^https?://.*")) url = "https://" + url;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Kunde inte öppna reklambladet. Kontrollera att en webbläsare är installerad.", Toast.LENGTH_LONG).show();
        }
    }

    private void showOffers(Chain chain, JSONObject store, List<JSONObject> offers, String note) {
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(12));
        root.setBackground(background(SURFACE, BORDER, 20));
        JSONObject address = store.optJSONObject("address");
        String city = address == null ? "" : address.optString("city", "");
        root.addView(label(chain.emoji + "  " + chain.name, 23, INK, true));
        root.addView(label(store.optString("name", "Butik") + (city.isEmpty() ? "" : " · " + city), 13, MUTED, false), margin(0, 2, 0, 12));
        if (!note.isEmpty()) root.addView(label(note, 13, MUTED, false), margin(0, 0, 0, 8));
        if (offers.isEmpty()) {
            root.addView(label("Inga erbjudanden hittades för den här butiken just nu.", 15, MUTED, false), margin(0, 8, 0, 8));
        } else {
            List<String> categories = new ArrayList<>();
            categories.add("Alla kategorier");
            for (JSONObject offer : offers) {
                String category = category(offer);
                if (!categories.contains(category)) categories.add(category);
            }
            Collections.sort(categories.subList(1, categories.size()), String.CASE_INSENSITIVE_ORDER);
            Spinner spinner = new Spinner(this);
            spinner.setAdapter(themedSpinnerAdapter(categories));
            spinner.setBackground(background(SURFACE, BORDER, 12));
            List<String> sortOptions = new ArrayList<>();
            sortOptions.add("Sortera: rekommenderat");
            sortOptions.add("Pris: lägsta först");
            sortOptions.add("Pris: högsta först");
            sortOptions.add("Namn: A–Ö");
            sortOptions.add("Namn: Ö–A");
            sortOptions.add("Rabatt: störst först");
            sortOptions.add("Rabatt: minst först");
            String[] selectedSort = {sortOptions.get(0)};
            ImageButton sortButton = iconButton(android.R.drawable.ic_menu_sort_by_size, false, "Sortera erbjudanden");
            LinearLayout filterRow = new LinearLayout(this);
            filterRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(0, dp(52), 1);
            filterParams.rightMargin = dp(8);
            filterRow.addView(spinner, filterParams);
            LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(dp(52), dp(52));
            filterRow.addView(sortButton, sortParams);
            root.addView(filterRow, margin(0, 0, 0, 8));
            TextView countLabel = label("Visar " + offers.size() + " erbjudanden", 12, MUTED, false);
            root.addView(countLabel, margin(0, 0, 0, 2));
            root.addView(label("Rabattprocent visas när ordinarie pris finns tillgängligt.", 11, MUTED, false), margin(0, 0, 0, 7));
            ScrollView listScroll = new ScrollView(this);
            LinearLayout offerList = new LinearLayout(this);
            offerList.setOrientation(LinearLayout.VERTICAL);
            listScroll.addView(offerList);
            root.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            Runnable render = () -> {
                String selected = String.valueOf(spinner.getSelectedItem());
                offerList.removeAllViews();
                List<JSONObject> filteredOffers = new ArrayList<>();
                for (JSONObject offer : offers) {
                    String category = category(offer);
                    if (selected.equals("Alla kategorier") || selected.equals(category)) filteredOffers.add(offer);
                }
                sortOffers(filteredOffers, selectedSort[0]);
                int count = 0;
                for (JSONObject offer : filteredOffers) {
                    String category = category(offer);
                    offerList.addView(offerCard(offer, category), margin(0, 0, 0, 9));
                    count++;
                }
                countLabel.setText("Visar " + count + " av " + offers.size() + " erbjudanden");
            };
            spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { render.run(); }
                @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
            sortButton.setOnClickListener(v -> {
                PopupMenu sortMenu = new PopupMenu(this, sortButton);
                for (int i = 0; i < sortOptions.size(); i++) {
                    final String option = sortOptions.get(i);
                    sortMenu.getMenu().add(0, i, i, option).setOnMenuItemClickListener(item -> {
                        selectedSort[0] = option;
                        sortButton.setContentDescription("Sortering: " + option);
                        render.run();
                        return true;
                    });
                }
                sortMenu.show();
            });
            render.run();
        }
        Button close = secondaryButton("Stäng");
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(close, margin(0, 9, 0, 0));
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(getResources().getDisplayMetrics().widthPixels - dp(12), ViewGroup.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(12), ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void sortOffers(List<JSONObject> offers, String sortOption) {
        Collator swedish = Collator.getInstance(new Locale("sv", "SE"));
        if (sortOption.equals("Namn: A–Ö") || sortOption.equals("Namn: Ö–A")) {
            Comparator<JSONObject> byName = (left, right) -> swedish.compare(
                    first(left, "name", "title", "product_name", "Erbjudande"),
                    first(right, "name", "title", "product_name", "Erbjudande"));
            if (sortOption.equals("Namn: Ö–A")) byName = byName.reversed();
            offers.sort(byName);
        } else if (sortOption.equals("Pris: lägsta först") || sortOption.equals("Pris: högsta först")) {
            boolean descending = sortOption.equals("Pris: högsta först");
            offers.sort((left, right) -> {
                double leftPrice = offerNumericPrice(left);
                double rightPrice = offerNumericPrice(right);
                if (Double.isNaN(leftPrice)) return Double.isNaN(rightPrice) ? 0 : 1;
                if (Double.isNaN(rightPrice)) return -1;
                return descending ? Double.compare(rightPrice, leftPrice) : Double.compare(leftPrice, rightPrice);
            });
        } else if (sortOption.equals("Rabatt: störst först") || sortOption.equals("Rabatt: minst först")) {
            boolean descending = sortOption.equals("Rabatt: störst först");
            offers.sort((left, right) -> {
                double leftDiscount = discountPercent(left);
                double rightDiscount = discountPercent(right);
                if (Double.isNaN(leftDiscount)) return Double.isNaN(rightDiscount) ? 0 : 1;
                if (Double.isNaN(rightDiscount)) return -1;
                return descending ? Double.compare(rightDiscount, leftDiscount) : Double.compare(leftDiscount, rightDiscount);
            });
        }
    }

    private double offerNumericPrice(JSONObject offer) {
        if (!offer.has("price") || offer.isNull("price")) return Double.NaN;
        return offer.optDouble("price", Double.NaN);
    }

    private double discountPercent(JSONObject offer) {
        double price = offerNumericPrice(offer);
        if (Double.isNaN(price) || price <= 0) return Double.NaN;
        double savings = offer.optDouble("savings", Double.NaN);
        if (!Double.isNaN(savings) && savings > 0) {
            return Math.min(100, Math.max(0, savings / (price + savings) * 100));
        }
        double regularPrice = Double.NaN;
        String[] regularPriceKeys = {"regular_price", "original_price", "price_before", "previous_price", "regularPrice"};
        for (String key : regularPriceKeys) {
            if (offer.has(key) && !offer.isNull(key)) {
                regularPrice = offer.optDouble(key, Double.NaN);
                if (!Double.isNaN(regularPrice)) break;
            }
        }
        if (!Double.isNaN(regularPrice) && regularPrice > price) {
            return Math.min(100, Math.max(0, (regularPrice - price) / regularPrice * 100));
        }
        return Double.NaN;
    }

    private View offerCard(JSONObject offer, String category) {
        LinearLayout card = card();
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        String name = first(offer, "name", "title", "product_name", "Erbjudande");
        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.TOP);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageResource(android.R.drawable.ic_menu_gallery);
        image.setBackground(background(SECONDARY_SURFACE, BORDER, 12));
        image.setClipToOutline(true);
        String imageUrl = offerImageUrl(offer);
        if (!imageUrl.isEmpty()) {
            image.setContentDescription("Visa större bild av " + name);
            String fullImageUrl = offerFullImageUrl(offer, imageUrl);
            image.setOnClickListener(v -> showLargeOfferImage(fullImageUrl, name));
        }
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(88), dp(88));
        imageParams.rightMargin = dp(12);
        summary.addView(image, imageParams);
        LinearLayout productInfo = new LinearLayout(this);
        productInfo.setOrientation(LinearLayout.VERTICAL);
        summary.addView(productInfo, new LinearLayout.LayoutParams(0, -2, 1));
        productInfo.addView(label(name, 16, INK, true));
        productInfo.addView(label(category + (offer.optString("brand", "").isEmpty() ? "" : " · " + offer.optString("brand")), 12, MUTED, false), margin(0, 3, 0, 6));
        String packageText = offer.optString("package_size", offer.optString("package", ""));
        if (packageText.isEmpty() && offer.has("package_value")) packageText = offer.optString("package_value") + " " + offer.optString("package_unit", "");
        if (!packageText.isEmpty()) productInfo.addView(label("Förpackning: " + packageText, 13, INK, false));
        if (offer.has("price") && !offer.isNull("price")) {
            productInfo.addView(label(offer.optString("price_text", String.format(Locale.getDefault(), "Pris: %.2f %s", offer.optDouble("price"), offer.optString("unit", "kr"))), 14, GREEN, true), margin(0, 4, 0, 0));
        }
        double discount = discountPercent(offer);
        if (!Double.isNaN(discount)) {
            TextView discountBadge = label(String.format(new Locale("sv", "SE"), "%.0f %% rabatt", discount), 12, GREEN, true);
            discountBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
            discountBadge.setBackground(background(SECONDARY_SURFACE, BORDER, 10));
            productInfo.addView(discountBadge, margin(0, 5, 0, 0));
        }
        String priceText = offer.optString("price_text", "");
        if (!priceText.isEmpty() && (!offer.has("price") || offer.isNull("price"))) productInfo.addView(label(priceText, 13, INK, false), margin(0, 3, 0, 0));
        card.addView(summary, margin(0, 0, 0, 8));
        if (!imageUrl.isEmpty()) loadOfferImage(image, imageUrl);
        if (offer.optString("deal_type", "").equals("multibuy") && offer.has("multibuy_qty") && !offer.has("price_text")) {
            card.addView(label("Flerpack: " + offer.optInt("multibuy_qty") + " st", 13, INK, false), margin(0, 3, 0, 0));
        } else if (offer.optString("deal_type", "").equals("by_weight")) {
            card.addView(label("Priset gäller efter vikt.", 13, INK, false), margin(0, 3, 0, 0));
        }
        if (offer.has("comparison_value") && !offer.optString("comparison_unit", "").isEmpty()) {
            card.addView(label(String.format(Locale.getDefault(), "Jämförpris: %.2f kr/%s", offer.optDouble("comparison_value"), offer.optString("comparison_unit")), 12, MUTED, false), margin(0, 3, 0, 0));
        }
        String dates = validity(offer);
        card.addView(label(dates, 12, MUTED, false), margin(0, 6, 0, 0));
        if (offer.optBoolean("member_price", false)) card.addView(label("Medlemspris", 12, GREEN, true), margin(0, 3, 0, 0));
        return card;
    }

    private void showLargeOfferImage(String imageUrl, String title) {
        Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(12));
        root.setBackground(background(SURFACE, BORDER, 18));
        root.addView(label(title, 17, INK, true), margin(0, 0, 0, 10));
        ImageView largeImage = new ImageView(this);
        largeImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        largeImage.setAdjustViewBounds(true);
        largeImage.setBackgroundColor(Color.BLACK);
        largeImage.setTag(imageUrl);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(-1, 0, 1);
        root.addView(largeImage, imageParams);
        Button close = secondaryButton("Stäng");
        close.setOnClickListener(v -> dialog.dismiss());
        root.addView(close, margin(0, 10, 0, 0));
        dialog.setContentView(root);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(28),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.82));
        }
        loadOfferImage(largeImage, imageUrl);
    }

    private String offerImageUrl(JSONObject offer) {
        String imageUrl = offer.optString("image", "").trim();
        if (imageUrl.isEmpty()) imageUrl = offer.optString("image_url", "").trim();
        if (imageUrl.startsWith("//")) return "https:" + imageUrl;
        if (imageUrl.startsWith("/")) return LOCAL_API + imageUrl;
        if (!imageUrl.isEmpty()) return imageUrl;
        JSONArray eans = offer.optJSONArray("eans");
        if (eans != null && eans.length() > 0) {
            String ean = eans.optString(0, "");
            if (!ean.isEmpty()) return LOCAL_API + "/v1/products/" + Uri.encode(ean) + "/image?size=thumb";
        }
        return "";
    }

    private String offerFullImageUrl(JSONObject offer, String fallbackUrl) {
        JSONArray eans = offer.optJSONArray("eans");
        if (eans != null && eans.length() > 0) {
            String ean = eans.optString(0, "");
            if (!ean.isEmpty()) return LOCAL_API + "/v1/products/" + Uri.encode(ean) + "/image?size=full";
        }
        return fallbackUrl;
    }

    private void loadOfferImage(ImageView imageView, String imageUrl) {
        imageView.setTag(imageUrl);
        Bitmap cached = imageCache.get(imageUrl);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }
        imageExecutor.execute(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "image/*");
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream input = connection.getInputStream()) { bitmap = BitmapFactory.decodeStream(input); }
                }
            } catch (Exception ignored) {
                // Offer images are optional; keep the placeholder if the source is unavailable.
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (bitmap == null) return;
            imageCache.put(imageUrl, bitmap);
            Bitmap loaded = bitmap;
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && imageUrl.equals(imageView.getTag())) imageView.setImageBitmap(loaded);
            });
        });
    }

    private String category(JSONObject offer) {
        String category = offer.optString("category", "").trim();
        if (!category.isEmpty()) return friendlyCategory(category);
        String title = (offer.optString("name", "") + " " + offer.optString("title", "") + " " + offer.optString("product_name", "")).toLowerCase(Locale.ROOT);
        if (containsAny(title, "bröd", "limpa", "fralla", "knäckebröd", "toast", "baguette")) return "Bröd";
        if (containsAny(title, "läsk", "juice", "vatten", "saft", "öl", "mjölk", "dryck", "kaffe", "te")) return "Dryck";
        return "Övrigt";
    }

    private String friendlyCategory(String rawCategory) {
        String key = rawCategory.toLowerCase(Locale.ROOT).trim().replace('-', '_').replace(' ', '_');
        switch (key) {
            case "brod_bageri": case "bread_bakery": return "Bröd & bageri";
            case "frukt_gront": case "frukt_och_gront": case "fruit_vegetables": return "Frukt & grönt";
            case "mejeri_ost_agg": case "mejeri_ost_ägg": case "dairy_eggs": return "Mejeri, ost & ägg";
            case "kott_fagel_chark": case "kött_fågel_chark": case "meat_poultry_deli": return "Kött, fågel & chark";
            case "fisk_skaldjur": case "fish_seafood": return "Fisk & skaldjur";
            case "dryck": case "drinks": case "beverages": return "Dryck";
            case "skafferi": case "pantry": return "Skafferi";
            case "godis_snacks": case "candy_snacks": return "Godis & snacks";
            case "fryst": case "frozen": return "Fryst";
            case "hygien_halsa": case "personal_care": return "Hygien & hälsa";
            case "hem_fritid": case "home_leisure": return "Hem & fritid";
            case "barn_baby": case "baby_children": return "Barn & baby";
            case "djur": case "pets": return "Djur";
            case "ovrigt": case "övrigt": case "other": return "Övrigt";
            default:
                String readable = key.replace('_', ' ')
                        .replace("brod", "bröd").replace("bageri", "bageri")
                        .replace("gront", "grönt").replace("agg", "ägg")
                        .replace("kott", "kött").replace("fagel", "fågel")
                        .replace("ovrigt", "övrigt");
                return readable.isEmpty() ? "Övrigt" : readable.substring(0, 1).toUpperCase(new Locale("sv", "SE")) + readable.substring(1);
        }
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private String validity(JSONObject offer) {
        String from = formatDate(offer.optString("valid_from", offer.optString("start_date", "")));
        String to = formatDate(offer.optString("valid_to", offer.optString("end_date", "")));
        if (!from.isEmpty() && !to.isEmpty()) return "Gäller " + from + " – " + to;
        if (!to.isEmpty()) return "Gäller till " + to;
        if (!from.isEmpty()) return "Gäller från " + from;
        String validity = offer.optString("validity", "");
        return validity.isEmpty() ? "Giltighetstid saknas" : validity;
    }

    private String formatDate(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            String date = value.length() >= 10 ? value.substring(0, 10) : value;
            Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(date);
            return new SimpleDateFormat("d MMM", new Locale("sv", "SE")).format(parsed);
        } catch (Exception ignored) { return value; }
    }

    private String first(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!value.isEmpty()) return value;
        }
        return "Erbjudande";
    }

    private void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        getLocation();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            boolean granted = false;
            for (int result : grantResults) if (result == PackageManager.PERMISSION_GRANTED) granted = true;
            if (granted) getLocation();
            else {
                Toast.makeText(this, "Tillåt platsåtkomst för att centrera kartan vid dig.", Toast.LENGTH_LONG).show();
                if (openMapAfterLocation) {
                    openMapAfterLocation = false;
                    showStoreMap();
                }
            }
        }
    }

    private void getLocation() {
        locationButton.setContentDescription("Hämtar din plats");
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String provider : manager.getProviders(true)) {
                try {
                    Location candidate = manager.getLastKnownLocation(provider);
                    if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
                } catch (SecurityException ignored) {}
            }
            if (best != null && System.currentTimeMillis() - best.getTime() < 60 * 60 * 1000) {
                applyLocation(best);
                return;
            }
            String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
            manager.requestSingleUpdate(provider, new LocationListener() {
                @Override public void onLocationChanged(Location location) { runOnUiThread(() -> applyLocation(location)); }
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) { runOnUiThread(() -> locationFailed()); }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            }, getMainLooper());
            locationButton.postDelayed(() -> { if (userLat == null) locationFailed(); }, 18000);
        } catch (SecurityException e) {
            locationFailed();
        }
    }

    private void applyLocation(Location location) {
        userLat = location.getLatitude();
        userLng = location.getLongitude();
        locationButton.setColorFilter(GREEN);
        locationButton.setContentDescription("Visa min plats");
        renderChains();
        Toast.makeText(this, "Butiker sorterade efter avstånd", Toast.LENGTH_SHORT).show();
        if (openMapAfterLocation) {
            openMapAfterLocation = false;
            showStoreMap();
        }
    }

    private void locationFailed() {
        locationButton.setColorFilter(userLat != null && userLng != null ? GREEN : MUTED);
        locationButton.setContentDescription("Platsåtkomst misslyckades");
        Toast.makeText(this, "Kunde inte läsa platsen. Kontrollera platsinställningarna.", Toast.LENGTH_LONG).show();
        if (openMapAfterLocation) {
            openMapAfterLocation = false;
            showStoreMap();
        }
    }

    private double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        layout.setBackground(background(SURFACE, BORDER, 16));
        layout.setElevation(dp(4));
        return layout;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private ImageButton iconButton(int iconResource, boolean primary, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setColorFilter(primary ? PRIMARY_BUTTON_TEXT : GREEN);
        button.setContentDescription(description);
        button.setTooltipText(description);
        button.setPadding(dp(13), dp(13), dp(13), dp(13));
        int fill = primary ? GREEN : SECONDARY_SURFACE;
        button.setBackground(background(fill, primary ? GREEN : BORDER, 14));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setTextColor(GREEN);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(background(SECONDARY_SURFACE, BORDER, 14));
        return button;
    }

    private GradientDrawable background(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (fill != stroke) drawable.setStroke(dp(2), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        imageExecutor.shutdownNow();
        super.onDestroy();
    }
}
