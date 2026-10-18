package com.ahusan.webapps;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.provider.Settings;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Home screen: grid of saved websites + "Add website" tile. */
public class MainActivity extends Activity {

    static final int BG = 0xFF0E1116;
    static final int CARD = 0xFF1A1F27;
    static final int CARD_FOCUS = 0xFF26303D;
    static final int ACCENT = 0xFF4F9DFF;
    static final int[] PALETTE = {0xFF3B82F6, 0xFFEF4444, 0xFF10B981, 0xFFF59E0B,
            0xFF8B5CF6, 0xFFEC4899, 0xFF14B8A6, 0xFFF97316};

    private List<Store.App> apps = new ArrayList<>();
    private GridView grid;
    private TextView hint, empty;
    private Button addBtn, updateBtn;
    private Updater.Release pendingUpdate;
    private boolean checking;
    private final AppsAdapter adapter = new AppsAdapter();
    private final Map<String, Bitmap> icons = new HashMap<>();
    private final Set<String> fetching = new HashSet<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private View selectedView;

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Blocker.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(48), dp(32), dp(48), dp(24));

        // Header (stays fixed): title + hint on the left, Add website button on the right.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(20));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Web Apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(title);

        hint = new TextView(this);
        hint.setTextColor(0xFF8B95A5);
        hint.setTextSize(14);
        hint.setPadding(0, dp(4), 0, 0);
        titles.addView(hint);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        // Update button: shows the version; turns green when a new version is on GitHub.
        updateBtn = headerButton();
        // Smaller pill than Add website.
        updateBtn.setTextSize(13);
        updateBtn.setPadding(dp(14), 0, dp(14), 0);
        updateBtn.setOnClickListener(v -> onUpdateClicked());
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(-2, dp(36));
        ulp.rightMargin = dp(12);
        header.addView(updateBtn, ulp);
        showUpdateState(Updater.cached(this));

        addBtn = new Button(this);
        addBtn.setId(View.generateViewId());
        addBtn.setText("+  Add website");
        addBtn.setAllCaps(false);
        addBtn.setTextSize(16);
        addBtn.setTypeface(Typeface.DEFAULT_BOLD);
        addBtn.setTextColor(Color.WHITE);
        addBtn.setPadding(dp(22), 0, dp(22), 0);
        addBtn.setStateListAnimator(null);
        GradientDrawable btnNormal = new GradientDrawable();
        btnNormal.setColor(CARD_FOCUS);
        btnNormal.setCornerRadius(dp(14));
        GradientDrawable btnFocus = new GradientDrawable();
        btnFocus.setColor(ACCENT);
        btnFocus.setCornerRadius(dp(14));
        StateListDrawable btnBg = new StateListDrawable();
        btnBg.addState(new int[]{android.R.attr.state_focused}, btnFocus);
        btnBg.addState(new int[]{android.R.attr.state_pressed}, btnFocus);
        btnBg.addState(new int[]{}, btnNormal);
        addBtn.setBackground(btnBg);
        addBtn.setOnClickListener(v -> showEditor(null));
        header.addView(addBtn, new LinearLayout.LayoutParams(-2, dp(52)));
        root.addView(header);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(180));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(18));
        grid.setVerticalSpacing(dp(18));
        grid.setPadding(dp(6), dp(6), dp(6), dp(6));
        grid.setClipToPadding(false);
        GradientDrawable sel = new GradientDrawable();
        sel.setColor(Color.TRANSPARENT);
        sel.setStroke(dp(3), ACCENT);
        sel.setCornerRadius(dp(18));
        grid.setSelector(sel);
        grid.setDrawSelectorOnTop(true);
        // Only show the tile highlight while the grid has focus (not when the Add button does).
        grid.setOnFocusChangeListener((v, has) -> {
            grid.setSelector(has ? sel : new GradientDrawable());
            highlight(has ? grid.getSelectedView() : null);
        });
        grid.setAdapter(adapter);
        grid.setId(View.generateViewId());
        grid.setVerticalFadingEdgeEnabled(true);
        grid.setFadingEdgeLength(dp(24));

        // Only this area scrolls; the header above stays put.
        FrameLayout listArea = new FrameLayout(this);
        listArea.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        empty = new TextView(this);
        empty.setText("No websites yet.\nPress OK on \u201c+ Add website\u201d to add one.");
        empty.setTextColor(0xFF8B95A5);
        empty.setTextSize(18);
        empty.setGravity(Gravity.CENTER);
        listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        grid.setEmptyView(empty);
        root.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1f));

        // D-pad: UP from the top row goes to the button, DOWN from the button goes into the grid.
        grid.setNextFocusUpId(addBtn.getId());
        addBtn.setNextFocusDownId(grid.getId());

        grid.setOnItemClickListener((p, v, pos, id) -> open(apps.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < apps.size()) showOptions(pos);
            return true;
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                highlight(v);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
                highlight(null);
            }
        });

        setContentView(root);
        if (Store.load(this).isEmpty()) addBtn.requestFocus();
        else grid.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        showUpdateState(Updater.cached(this));
        if (Updater.dueForAutoCheck(this)) checkForUpdate(false);
    }

    // ---------- Updates ----------

    private Button headerButton() {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setStateListAnimator(null);
        return b;
    }

    private void styleButton(Button b, int normalColor) {
        GradientDrawable n = new GradientDrawable();
        n.setColor(normalColor);
        n.setCornerRadius(dp(14));
        GradientDrawable f = new GradientDrawable();
        f.setColor(ACCENT);
        f.setCornerRadius(dp(14));
        f.setStroke(dp(2), Color.WHITE);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, f);
        sl.addState(new int[]{android.R.attr.state_pressed}, f);
        sl.addState(new int[]{}, n);
        b.setBackground(sl);
    }

    private void showUpdateState(Updater.Release r) {
        pendingUpdate = r;
        if (r != null) {
            updateBtn.setText("\u2B06  Update available");
            styleButton(updateBtn, 0xFF16A34A);
        } else {
            updateBtn.setText("v" + Updater.currentVersionName(this));
            styleButton(updateBtn, CARD);
        }
    }

    private void checkForUpdate(boolean manual) {
        if (checking) return;
        checking = true;
        if (manual) updateBtn.setText("Checking\u2026");
        new Thread(() -> {
            Updater.Release r = null;
            String error = null;
            try {
                r = Updater.check(this);
            } catch (Exception e) {
                error = e.getMessage();
            }
            Updater.Release found = r;
            String err = error;
            ui.post(() -> {
                checking = false;
                if (isFinishing()) return;
                showUpdateState(found != null ? found : Updater.cached(this));
                if (!manual) return;
                if (err != null) {
                    new AlertDialog.Builder(this).setTitle("Couldn't check for updates")
                            .setMessage("Check the internet connection and try again.\n\n" + err)
                            .setPositiveButton("OK", null).show();
                } else if (found == null) {
                    new AlertDialog.Builder(this).setTitle("Up to date")
                            .setMessage("You have the latest version (v" + Updater.currentVersionName(this) + ").")
                            .setPositiveButton("OK", null).show();
                } else {
                    confirmUpdate(found);
                }
            });
        }).start();
    }

    private void onUpdateClicked() {
        if (pendingUpdate != null) confirmUpdate(pendingUpdate);
        else checkForUpdate(true);
    }

    private void confirmUpdate(Updater.Release r) {
        String notes = r.notes == null || r.notes.isEmpty() ? "" : "\n\nWhat's new:\n" + r.notes;
        String size = r.size > 0 ? String.format(Locale.US, " (%.1f MB)", r.size / 1048576.0) : "";
        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("Version " + r.tag + size + " is ready.\nYou have v"
                        + Updater.currentVersionName(this) + "." + notes)
                .setPositiveButton("Download & install", (d, w) -> startUpdate(r))
                .setNegativeButton("Later", null)
                .show();
    }

    private void startUpdate(Updater.Release r) {
        if (!Updater.canInstall(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow updates")
                    .setMessage("To install updates, allow \"Web Apps\" to install apps.\n\n"
                            + "On the next screen turn it ON, then press BACK and choose Download & install again.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            // Some TVs don't have this screen; the installer will ask instead.
                            doDownload(r);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        doDownload(r);
    }

    private void doDownload(Updater.Release r) {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        TextView label = new TextView(this);
        label.setTextColor(0xFFB8C2D0);
        label.setTextSize(15);
        label.setText("Starting download\u2026");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        box.addView(bar, new LinearLayout.LayoutParams(-1, dp(10)));
        box.addView(label);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Downloading " + r.tag)
                .setView(box)
                .setCancelable(false)
                .show();

        new Thread(() -> {
            String error = null;
            try {
                Updater.downloadAndInstall(this, r, (done, total) -> ui.post(() -> {
                    if (total > 0) {
                        bar.setProgress((int) (done * 1000 / total));
                        label.setText(String.format(Locale.US, "%.1f of %.1f MB",
                                done / 1048576.0, total / 1048576.0));
                    } else {
                        bar.setIndeterminate(true);
                        label.setText(String.format(Locale.US, "%.1f MB", done / 1048576.0));
                    }
                }));
            } catch (Exception e) {
                error = e.getMessage();
            }
            String err = error;
            ui.post(() -> {
                if (dlg.isShowing()) dlg.dismiss();
                if (err != null) {
                    new AlertDialog.Builder(this).setTitle("Update failed")
                            .setMessage(err).setPositiveButton("OK", null).show();
                } else {
                    Toast.makeText(this, "Installing\u2026 confirm on the next screen", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void refresh() {
        apps = Store.load(this);
        hint.setText(apps.isEmpty()
                ? "Add any website and it opens like an app."
                : "OK = open  ·  Hold OK / MENU = edit, move, delete");
        adapter.notifyDataSetChanged();
        if (apps.isEmpty() && !addBtn.hasFocus()) addBtn.requestFocus();
    }

    private void highlight(View v) {
        if (selectedView != null) setCardColor(selectedView, CARD);
        selectedView = v;
        if (v != null) setCardColor(v, CARD_FOCUS);
    }

    private void setCardColor(View v, int color) {
        if (v.getBackground() instanceof GradientDrawable)
            ((GradientDrawable) v.getBackground()).setColor(color);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            int pos = grid.getSelectedItemPosition();
            if (grid.hasFocus() && pos >= 0 && pos < apps.size()) showOptions(pos);
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private void open(Store.App app) {
        Intent i = new Intent(this, WebAppActivity.class);
        i.putExtra("id", app.id);
        startActivity(i);
    }

    // ---------- Add / edit ----------

    private void showEditor(Store.App existing) {
        boolean editing = existing != null;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), 0);

        EditText url = new EditText(this);
        url.setHint("Website address, e.g. youtube.com");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setSingleLine(true);
        box.addView(url);

        EditText name = new EditText(this);
        name.setHint("Name (optional)");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setSingleLine(true);
        box.addView(name);

        CheckBox desktop = new CheckBox(this);
        desktop.setText("Desktop mode (full website instead of mobile version)");
        box.addView(desktop);

        CheckBox cursor = new CheckBox(this);
        cursor.setText("Mouse cursor (recommended for most sites)");
        cursor.setChecked(true);
        box.addView(cursor);

        if (editing) {
            url.setText(existing.url);
            name.setText(existing.name);
            desktop.setChecked(existing.desktop);
            cursor.setChecked(existing.cursor);
        }

        AlertDialog.Builder bld = new AlertDialog.Builder(this)
                .setTitle(editing ? "Edit web app" : "Add website")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (!editing) bld.setNeutralButton("Open once", null);
        AlertDialog dlg = bld.create();

        dlg.setOnShowListener(d -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String u = Store.normalize(url.getText().toString());
                if (u == null) {
                    url.setError("Enter a website address");
                    return;
                }
                Blocker.await();
                if (Blocker.checkUrl(u) == Blocker.ADULT) {
                    url.setError("This website is blocked (not suitable for kids)");
                    return;
                }
                Store.App app = editing ? existing : new Store.App();
                app.url = u;
                String n = name.getText().toString().trim();
                app.name = n.isEmpty() ? Store.defaultName(u) : n;
                app.desktop = desktop.isChecked();
                app.cursor = cursor.isChecked();
                if (editing) {
                    Store.update(this, app);
                    icons.remove(app.id);
                    new File(iconDir(), app.id + ".png").delete();
                } else {
                    apps.add(app);
                    Store.save(this, apps);
                }
                dlg.dismiss();
                refresh();
                if (!editing) {
                    grid.requestFocus();
                    grid.setSelection(apps.size() - 1);
                }
            });
            // The TV keyboard covers the buttons, so its Enter/Done key saves too.
            TextView.OnEditorActionListener saveOnEnter = (tv, actionId, ev) -> {
                if (ev != null && ev.getAction() != KeyEvent.ACTION_UP) return true;
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            };
            url.setImeOptions(EditorInfo.IME_ACTION_DONE);
            name.setImeOptions(EditorInfo.IME_ACTION_DONE);
            url.setOnEditorActionListener(saveOnEnter);
            name.setOnEditorActionListener(saveOnEnter);
            if (!editing) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    String u = Store.normalize(url.getText().toString());
                    if (u == null) {
                        url.setError("Enter a website address");
                        return;
                    }
                    Blocker.await();
                    if (Blocker.checkUrl(u) == Blocker.ADULT) {
                        url.setError("This website is blocked (not suitable for kids)");
                        return;
                    }
                    Intent i = new Intent(this, WebAppActivity.class);
                    i.putExtra("url", u);
                    i.putExtra("desktop", desktop.isChecked());
                    i.putExtra("cursor", cursor.isChecked());
                    dlg.dismiss();
                    startActivity(i);
                });
            }
            url.requestFocus();
        });
        dlg.show();
    }

    private void showOptions(int pos) {
        Store.App app = apps.get(pos);
        String[] items = {"Open", "Edit", "Move left", "Move right", "Delete"};
        new AlertDialog.Builder(this)
                .setTitle(app.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            open(app);
                            break;
                        case 1:
                            showEditor(app);
                            break;
                        case 2:
                            move(pos, pos - 1);
                            break;
                        case 3:
                            move(pos, pos + 1);
                            break;
                        case 4:
                            new AlertDialog.Builder(this)
                                    .setMessage("Delete \"" + app.name + "\"?")
                                    .setPositiveButton("Delete", (d2, w2) -> {
                                        apps.remove(pos);
                                        Store.save(this, apps);
                                        new File(iconDir(), app.id + ".png").delete();
                                        refresh();
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                            break;
                    }
                })
                .show();
    }

    private void move(int from, int to) {
        if (to < 0 || to >= apps.size()) return;
        Store.App a = apps.remove(from);
        apps.add(to, a);
        Store.save(this, apps);
        adapter.notifyDataSetChanged();
        grid.setSelection(to);
    }

    // ---------- Site icons ----------

    private File iconDir() {
        File f = new File(getFilesDir(), "icons");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    private Bitmap icon(Store.App app) {
        if (icons.containsKey(app.id)) return icons.get(app.id);
        File f = new File(iconDir(), app.id + ".png");
        if (f.exists()) {
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            icons.put(app.id, bm);
            return bm;
        }
        if (fetching.add(app.id)) {
            String host = Store.host(app.url);
            String id = app.id;
            new Thread(() -> {
                Bitmap bm = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(
                            "https://www.google.com/s2/favicons?sz=128&domain=" + host).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    try (InputStream in = c.getInputStream()) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        byte[] data = out.toByteArray();
                        bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                    }
                    // Google returns a tiny generic globe when a site has no icon; ignore those.
                    if (bm != null && bm.getWidth() >= 32) {
                        try (FileOutputStream fo = new FileOutputStream(new File(iconDir(), id + ".png"))) {
                            bm.compress(Bitmap.CompressFormat.PNG, 100, fo);
                        }
                    } else {
                        bm = null;
                    }
                } catch (Exception ignored) {
                }
                Bitmap result = bm;
                ui.post(() -> {
                    icons.put(id, result);
                    adapter.notifyDataSetChanged();
                });
            }).start();
        }
        return null;
    }

    // ---------- Grid adapter ----------

    private class AppsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int pos) {
            return apps.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(12), dp(16), dp(12), dp(14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(view_isSelected(pos) ? CARD_FOCUS : CARD);
            bg.setCornerRadius(dp(18));
            card.setBackground(bg);
            card.setLayoutParams(new AbsListView.LayoutParams(-1, dp(180)));

            FrameLayout iconBox = new FrameLayout(MainActivity.this);
            GradientDrawable ib = new GradientDrawable();
            ib.setCornerRadius(dp(20));
            TextView letter = new TextView(MainActivity.this);
            letter.setGravity(Gravity.CENTER);
            letter.setTextColor(Color.WHITE);
            letter.setTypeface(Typeface.DEFAULT_BOLD);

            TextView name = new TextView(MainActivity.this);
            name.setTextColor(Color.WHITE);
            name.setTextSize(16);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, dp(12), 0, 0);

            TextView sub = new TextView(MainActivity.this);
            sub.setTextColor(0xFF8B95A5);
            sub.setTextSize(12);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setGravity(Gravity.CENTER);

            {
                Store.App app = apps.get(pos);
                Bitmap bm = icon(app);
                if (bm != null) {
                    ib.setColor(Color.WHITE);
                    ImageView iv = new ImageView(MainActivity.this);
                    iv.setImageBitmap(bm);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    iv.setPadding(dp(14), dp(14), dp(14), dp(14));
                    iconBox.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                } else {
                    ib.setColor(PALETTE[Math.abs(app.name.hashCode()) % PALETTE.length]);
                    letter.setText(app.name.isEmpty() ? "?" : app.name.substring(0, 1).toUpperCase());
                    letter.setTextSize(36);
                    iconBox.addView(letter, new FrameLayout.LayoutParams(-1, -1));
                }
                name.setText(app.name);
                sub.setText(Store.host(app.url) + (app.desktop ? "  ·  desktop" : ""));
            }
            iconBox.setBackground(ib);

            card.addView(iconBox, new LinearLayout.LayoutParams(dp(84), dp(84)));
            card.addView(name, new LinearLayout.LayoutParams(-1, -2));
            card.addView(sub, new LinearLayout.LayoutParams(-1, -2));
            return card;
        }

        private boolean view_isSelected(int pos) {
            return grid != null && grid.hasFocus() && grid.getSelectedItemPosition() == pos
                    && !grid.isInTouchMode();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && grid.getSelectedView() != null) highlight(grid.getSelectedView());
    }
}
