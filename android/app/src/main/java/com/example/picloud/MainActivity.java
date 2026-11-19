package com.example.picloud;

import android.app.DownloadManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.*;
import android.webkit.MimeTypeMap;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.*;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

public class MainActivity extends AppCompatActivity {
  static final int PICK = 10;
  final ExecutorService bg = Executors.newSingleThreadExecutor();
  final ExecutorService poller = Executors.newSingleThreadExecutor();
  final Handler ui = new Handler(Looper.getMainLooper());
  Api api;
  final List<String> path = new ArrayList<>();
  final List<Api.Item> items = new ArrayList<>();
  ArrayAdapter<Api.Item> adapter;
  View loginBox, mainBox, progressBox, barUsed, barFree;
  TextView quotaText, pathText, loginStatus, progressText, emptyText;
  LinearProgressIndicator progress;
  SwipeRefreshLayout swipe;
  Button loginBtn;
  View setupBox;
  android.widget.EditText serverInput;
  Button saveServerBtn;
  volatile boolean polling;
  TextView heatBanner, tempText, sysSummary, sysDetails;
  View tempFill, tempRest;
  final Runnable statsTick = new Runnable() { public void run() { loadStats(); ui.postDelayed(this, 60_000); } };

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    setContentView(R.layout.dashboard);
    try { api = new Api(this); } catch (RuntimeException e) {
      new MaterialAlertDialogBuilder(this).setTitle("Error").setMessage(e.getMessage()).setPositiveButton("OK", null).show();
      return;
    }
    MaterialToolbar tb = findViewById(R.id.toolbar);
    tb.getMenu().add("Refresh").setOnMenuItemClickListener(m -> { refreshAll(); return true; });
    tb.getMenu().add("Change server").setOnMenuItemClickListener(m -> { confirmChangeServer(); return true; });
    tb.getMenu().add("Sign out").setOnMenuItemClickListener(m -> { confirmLogout(); return true; });
    setupBox = findViewById(R.id.setupBox);
    serverInput = findViewById(R.id.serverInput);
    saveServerBtn = findViewById(R.id.saveServerBtn);
    saveServerBtn.setOnClickListener(v -> saveServer());
    loginBox = findViewById(R.id.loginBox); mainBox = findViewById(R.id.mainBox);
    progressBox = findViewById(R.id.progressBox); progress = findViewById(R.id.progress); progressText = findViewById(R.id.progressText);
    barUsed = findViewById(R.id.barUsed); barFree = findViewById(R.id.barFree);
    quotaText = findViewById(R.id.quotaText); pathText = findViewById(R.id.pathText);
    loginStatus = findViewById(R.id.loginStatus); emptyText = findViewById(R.id.emptyText);
    loginBtn = findViewById(R.id.loginBtn); swipe = findViewById(R.id.swipe);
    heatBanner = findViewById(R.id.heatBanner); tempText = findViewById(R.id.tempText);
    sysSummary = findViewById(R.id.sysSummary); sysDetails = findViewById(R.id.sysDetails);
    tempFill = findViewById(R.id.tempFill); tempRest = findViewById(R.id.tempRest);
    findViewById(R.id.sysCard).setOnClickListener(v -> sysDetails.setVisibility(sysDetails.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    loginBtn.setOnClickListener(v -> startLogin());
    findViewById(R.id.newFolderBtn).setOnClickListener(v -> newFolder());
    findViewById(R.id.uploadBtn).setOnClickListener(v -> {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
          .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
      startActivityForResult(i, PICK);
    });
    findViewById(R.id.openWebBtn).setOnClickListener(v -> openWeb(null));
    swipe.setOnRefreshListener(this::refreshAll);

    adapter = new ArrayAdapter<Api.Item>(this, R.layout.item, items) {
      @Override public View getView(int pos, View cv, ViewGroup parent) {
        if (cv == null) cv = getLayoutInflater().inflate(R.layout.item, parent, false);
        Api.Item it = items.get(pos);
        ((TextView) cv.findViewById(R.id.icon)).setText(icon(it));
        ((TextView) cv.findViewById(R.id.name)).setText(it.name);
        String when = it.modified > 0 ? DateFormat.getMediumDateFormat(MainActivity.this).format(new Date(it.modified)) : "";
        ((TextView) cv.findViewById(R.id.meta)).setText(it.dir ? (when.isEmpty() ? "Folder" : "Folder · " + when)
            : Formatter.formatFileSize(MainActivity.this, it.size) + (when.isEmpty() ? "" : " · " + when));
        return cv;
      }
    };
    ListView lv = findViewById(R.id.list);
    lv.setAdapter(adapter);
    lv.setOnItemClickListener((p, v, pos, id) -> {
      Api.Item it = items.get(pos);
      if (it.dir) { path.add(it.name); loadFolder(); } else openFile(it);
    });
    lv.setOnItemLongClickListener((p, v, pos, id) -> { itemMenu(items.get(pos)); return true; });
    showState();
  }

  void showState() {
    boolean has = api.hasServer();
    boolean in = api.loggedIn();
    setupBox.setVisibility(has ? View.GONE : View.VISIBLE);
    loginBox.setVisibility(has && !in ? View.VISIBLE : View.GONE);
    mainBox.setVisibility(has && in ? View.VISIBLE : View.GONE);
    if (!has) {
      heatBanner.setVisibility(View.GONE);
      if (api.server != null) serverInput.setText(api.server);
    } else if (!in) {
      heatBanner.setVisibility(View.GONE);
      loginStatus.setText("Sign in to " + api.server + ". If you're away from home, make sure Tailscale is connected.");
    } else {
      refreshAll();
    }
  }

  Intent webIntent(String url) {
    Intent i = new Intent(this, WebActivity.class).putExtra("server", api.server);
    if (url != null) i.putExtra("url", url);
    return i;
  }
  void openWeb(String url) { startActivity(webIntent(url)); }

  void saveServer() {
    try {
      api.setServer(serverInput.getText().toString());
      path.clear(); items.clear(); if (adapter != null) adapter.notifyDataSetChanged();
      showState();
    } catch (IllegalArgumentException e) {
      serverInput.setError(e.getMessage());
    }
  }

  void confirmChangeServer() {
    if (!api.hasServer()) { showState(); return; }
    new MaterialAlertDialogBuilder(this).setTitle("Change server?")
        .setMessage("You'll be signed out. Enter a new Nextcloud address next.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Change", (d, w) -> {
          String current = api.server;
          api.clearServer();
          path.clear(); items.clear(); if (adapter != null) adapter.notifyDataSetChanged();
          serverInput.setText(current == null ? "" : current);
          showState();
        }).show();
  }

  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); }

  @Override public void onBackPressed() {
    if (api != null && api.loggedIn() && !path.isEmpty()) { path.remove(path.size() - 1); loadFolder(); }
    else super.onBackPressed();
  }

  // ---------- errors ----------
  String friendly(Throwable e) {
    if (e instanceof ConnectException || e instanceof SocketTimeoutException || e instanceof NoRouteToHostException || e instanceof UnknownHostException)
      return "Can't reach your cloud. Make sure Tailscale is connected.";
    String m = e.getMessage();
    return m != null ? m : e.getClass().getSimpleName();
  }

  void handleError(Throwable e) {
    if (e instanceof Api.ApiException && ((Api.ApiException) e).code == 401) {
      api.logout(); path.clear(); toast(friendly(e)); showState(); return;
    }
    toast(friendly(e));
  }

  void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

  interface Job<T> { T run() throws Exception; }
  interface Done<T> { void ok(T t); }

  <T> void async(Job<T> job, Done<T> done) {
    bg.execute(() -> {
      try { T r = job.run(); ui.post(() -> { if (!isFinishing()) done.ok(r); }); }
      catch (Exception e) { ui.post(() -> { swipe.setRefreshing(false); progressBox.setVisibility(View.GONE); if (!isFinishing()) handleError(e); }); }
    });
  }

  // ---------- login flow v2 ----------
  void startLogin() {
    loginBtn.setEnabled(false); loginStatus.setText("Connecting…");
    poller.execute(() -> {
      try {
        String[] s = api.startLogin();
        polling = true;
        ui.post(() -> {
          loginStatus.setText("Approve access in the page that opened, then you'll come right back here.");
          openWeb(s[0]);
        });
        long deadline = System.currentTimeMillis() + 20 * 60_000;
        while (polling && System.currentTimeMillis() < deadline) {
          Thread.sleep(2000);
          String[] r;
          try { r = Api.pollLogin(s[1], s[2]); } catch (IOException transient_) { continue; }
          if (r != null) {
            api.save(r[0], r[1]); polling = false;
            ui.post(() -> {
              startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
              loginBtn.setEnabled(true); path.clear(); showState(); toast("Signed in as " + r[0]);
            });
            return;
          }
        }
        ui.post(() -> { loginBtn.setEnabled(true); loginStatus.setText("Sign-in timed out. Tap Sign in to try again."); });
      } catch (Exception e) {
        ui.post(() -> { loginBtn.setEnabled(true); loginStatus.setText(friendly(e)); });
      }
    });
  }

  void confirmLogout() {
    if (!api.loggedIn()) return;
    new MaterialAlertDialogBuilder(this).setTitle("Sign out?").setMessage("You'll need to sign in again to see your files.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Sign out", (d, w) -> { api.logout(); path.clear(); items.clear(); adapter.notifyDataSetChanged(); showState(); }).show();
  }

  @Override protected void onDestroy() { polling = false; super.onDestroy(); }

  // ---------- quota + listing ----------
  void refreshAll() { if (!api.loggedIn()) { swipe.setRefreshing(false); return; } loadQuota(); loadFolder(); loadStats(); }

  @Override protected void onResume() { super.onResume(); ui.removeCallbacks(statsTick); ui.postDelayed(statsTick, 60_000); }
  @Override protected void onPause() { super.onPause(); ui.removeCallbacks(statsTick); }

  static String fmtB(long b) {
    if (b < 0) return "?";
    String[] u = {"B", "KB", "MB", "GB", "TB"}; double v = b; int i = 0;
    while (v >= 1000 && i < 4) { v /= 1000; i++; }
    return i == 0 ? b + " B" : String.format(Locale.US, v >= 100 ? "%.0f %s" : "%.1f %s", v, u[i]);
  }
  static String ago(long s) {
    if (s < 0) return "?";
    if (s < 60) return s + " s ago"; if (s < 3600) return (s / 60) + " min ago";
    if (s < 86400) return (s / 3600) + " h ago"; return (s / 86400) + " days ago";
  }
  static String netLine(org.json.JSONObject o) { return o == null ? "?" : "↓ " + fmtB(o.optLong("rx", -1)) + "  ↑ " + fmtB(o.optLong("tx", -1)); }

  void loadStats() {
    if (api == null || !api.loggedIn()) return;
    bg.execute(() -> {
      try {
        org.json.JSONObject d = api.stats();
        ui.post(() -> { if (!isFinishing()) showStats(d); });
      } catch (Exception e) {
        ui.post(() -> {
          if (isFinishing()) return;
          if (e instanceof Api.ApiException && ((Api.ApiException) e).code == 401) { handleError(e); return; }
          heatBanner.setVisibility(View.GONE); tempText.setText("—");
          sysSummary.setText(friendly(e)); sysDetails.setText("");
        });
      }
    });
  }

  void showStats(org.json.JSONObject d) {
    org.json.JSONObject c = d.optJSONObject("cpu"), th = d.optJSONObject("throttle"), n = d.optJSONObject("network"),
        p = d.optJSONObject("power"), nd = d.optJSONObject("nextcloud_disk");
    double t = (c != null && !c.isNull("temp_c")) ? c.optDouble("temp_c", Double.NaN) : Double.NaN;
    boolean thr = th != null && th.optBoolean("active", false);
    int color = Double.isNaN(t) ? 0xFF9E9E9E : t >= 75 ? 0xFFE53935 : t >= 60 ? 0xFFFB8C00 : 0xFF43A047;
    tempText.setText(Double.isNaN(t) ? "🌡️ n/a" : String.format(Locale.US, "🌡️ %.1f °C", t));
    tempText.setTextColor(color);
    tempFill.setBackgroundColor(color);
    float f = Double.isNaN(t) ? 0f : (float) Math.max(0.03, Math.min(1.0, t / 90.0));
    ((LinearLayout.LayoutParams) tempFill.getLayoutParams()).weight = f;
    ((LinearLayout.LayoutParams) tempRest.getLayoutParams()).weight = 1f - f;
    tempFill.requestLayout(); tempRest.requestLayout();
    List<String> warn = new ArrayList<>();
    if (!Double.isNaN(t) && t >= 75) warn.add(String.format(Locale.US, "🔥 Overheating! CPU at %.1f °C", t));
    if (thr) warn.add("⚠️ Pi is throttling to cool down" + (th.optBoolean("under_voltage") ? " (under-voltage: check power supply)" : ""));
    heatBanner.setText(String.join("\n", warn));
    heatBanner.setVisibility(warn.isEmpty() ? View.GONE : View.VISIBLE);
    long age = d.isNull("age_s") ? -1 : d.optLong("age_s", -1);
    String thrTxt = th == null || th.isNull("raw") ? "throttle status n/a" : thr ? "throttling!" : "not throttling";
    sysSummary.setText((c != null ? "CPU " + c.optInt("util_pct") + "% · " : "") + thrTxt
        + (p != null ? String.format(Locale.US, " · ~%.1f W (est.)", p.optDouble("watts")) : "")
        + "\nUpdated " + ago(age) + (age > 300 ? " ⚠️ collector may have stopped" : "") + " · tap for details");
    StringBuilder sb = new StringBuilder();
    if (c != null) sb.append(String.format(Locale.US, "Load      %s / %s / %s%n", c.opt("load1"), c.opt("load5"), c.opt("load15")));
    long up = d.optLong("uptime_s", -1);
    if (up >= 0) sb.append(String.format(Locale.US, "Uptime    %dd %dh %dm%n", up / 86400, up % 86400 / 3600, up % 3600 / 60));
    if (n != null) {
      sb.append("\nNETWORK\n");
      sb.append("Today     ").append(netLine(n.optJSONObject("today"))).append('\n');
      sb.append("Month     ").append(netLine(n.optJSONObject("month"))).append('\n');
      sb.append("Boot      ").append(netLine(n.optJSONObject("since_boot"))).append('\n');
    }
    if (p != null) {
      sb.append("\nPOWER (ESTIMATED, not measured)\n");
      sb.append(String.format(Locale.US, "Now       ~%.2f W%nToday     ~%.3f kWh%nMonth     ~%.2f kWh%n", p.optDouble("watts"), p.optDouble("today_kwh"), p.optDouble("month_kwh")));
    }
    if (nd != null) {
      sb.append("\nNEXTCLOUD DISK USAGE\n");
      String[][] k = {{"App code", "app_code"}, {"Database", "database"}, {"App data", "appdata"}, {"Previews", "previews"}, {"Trash", "trash"}, {"Versions", "versions"}, {"Images", "docker_images"}};
      for (String[] x : k) sb.append(String.format(Locale.US, "%-10s%s%n", x[0], fmtB(nd.optLong(x[1], -1))));
    }
    sysDetails.setText(sb.toString().trim());
  }

  void loadQuota() {
    async(api::quota, q -> {
      long used = q[0], free = q[1], total = q[2];
      float uf = total > 0 ? (float) used / total : 0f;
      ((LinearLayout.LayoutParams) barUsed.getLayoutParams()).weight = uf;
      ((LinearLayout.LayoutParams) barFree.getLayoutParams()).weight = 1f - uf;
      barUsed.requestLayout(); barFree.requestLayout();
      quotaText.setText(gb(used) + " used of " + gb(total) + " (" + gb(free) + " free)");
    });
  }

  static String gb(long b) {
    double g = b / 1e9;
    return g >= 100 ? String.format(Locale.US, "%.0f GB", g) : g >= 1 ? String.format(Locale.US, "%.1f GB", g) : String.format(Locale.US, "%.2f GB", g);
  }

  void loadFolder() {
    pathText.setText(path.isEmpty() ? "🏠 Home" : "🏠 Home / " + String.join(" / ", path));
    swipe.setRefreshing(true);
    List<String> p = new ArrayList<>(path);
    async(() -> api.list(p), list -> {
      swipe.setRefreshing(false);
      if (!p.equals(path)) return;
      items.clear(); items.addAll(list); adapter.notifyDataSetChanged();
      emptyText.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
      emptyText.setText("This folder is empty. Tap Upload to add files.");
    });
  }

  static String icon(Api.Item it) {
    if (it.dir) return "📁";
    String m = it.mime == null ? "" : it.mime;
    if (m.startsWith("image/")) return "🖼️";
    if (m.startsWith("video/")) return "🎬";
    if (m.startsWith("audio/")) return "🎵";
    if (m.contains("pdf")) return "📕";
    if (m.contains("zip") || m.contains("compressed") || m.contains("tar")) return "🗜️";
    if (m.startsWith("text/") || m.contains("document") || m.contains("word")) return "📝";
    return "📄";
  }

  List<String> child(String name) { List<String> l = new ArrayList<>(path); l.add(name); return l; }

  static String validName(String n) {
    n = n.trim();
    if (n.isEmpty()) return "Please enter a name";
    if (n.contains("/") || n.contains("\\") || n.equals(".") || n.equals("..")) return "Names can't contain / or \\";
    return null;
  }

  void askName(String title, String initial, String okLabel, Done<String> done) {
    EditText et = new EditText(this);
    et.setSingleLine(); et.setText(initial); et.setSelectAllOnFocus(true);
    FrameLayout fl = new FrameLayout(this); int pad = (int) (20 * getResources().getDisplayMetrics().density);
    fl.setPadding(pad, pad / 2, pad, 0); fl.addView(et);
    androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this).setTitle(title).setView(fl)
        .setNegativeButton("Cancel", null).setPositiveButton(okLabel, null).create();
    d.setOnShowListener(x -> d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
      String err = validName(et.getText().toString());
      if (err != null) { et.setError(err); return; }
      d.dismiss(); done.ok(et.getText().toString().trim());
    }));
    d.show(); et.requestFocus();
  }

  void newFolder() {
    askName("New folder", "", "Create", name -> async(() -> { api.mkcol(child(name)); return true; }, r -> { toast("Folder created"); loadFolder(); }));
  }

  void itemMenu(Api.Item it) {
    List<String> opts = new ArrayList<>(Arrays.asList(it.dir ? "Open" : "Open", "Rename", "Delete"));
    if (!it.dir) opts.add(1, "Save to Downloads");
    new MaterialAlertDialogBuilder(this).setTitle(it.name).setItems(opts.toArray(new String[0]), (d, w) -> {
      switch (opts.get(w)) {
        case "Open": if (it.dir) { path.add(it.name); loadFolder(); } else openFile(it); break;
        case "Save to Downloads": saveToDownloads(it); break;
        case "Rename": askName("Rename", it.name, "Rename", n -> {
          if (n.equals(it.name)) return;
          new MaterialAlertDialogBuilder(this).setTitle("Rename?").setMessage("Rename \"" + it.name + "\" to \"" + n + "\"?")
              .setNegativeButton("Cancel", null).setPositiveButton("Rename", (a, b) ->
                  async(() -> { api.move(child(it.name), child(n)); return true; }, r -> { toast("Renamed"); loadFolder(); })).show();
        }); break;
        case "Delete": new MaterialAlertDialogBuilder(this).setTitle("Delete " + (it.dir ? "folder" : "file") + "?")
            .setMessage("\"" + it.name + "\"" + (it.dir ? " and everything inside it" : "") + " will be moved to Nextcloud's trash.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (a, b) ->
                async(() -> { api.delete(child(it.name)); return true; }, r -> { toast("Deleted"); loadFolder(); loadQuota(); })).show(); break;
      }
    }).show();
  }

  // ---------- open / download ----------
  void openFile(Api.Item it) {
    showProgress("Opening " + it.name + "…", 0);
    List<String> p = child(it.name);
    async(() -> {
      File dir = new File(getCacheDir(), "open"); dir.mkdirs();
      File f = new File(dir, it.name);
      try (Response r = api.get(p); InputStream in = r.body().byteStream(); OutputStream out = new FileOutputStream(f)) {
        long total = r.body().contentLength() > 0 ? r.body().contentLength() : it.size, done = 0, last = 0;
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n); done += n;
          if (total > 0 && System.currentTimeMillis() - last > 150) { last = System.currentTimeMillis(); long d = done; ui.post(() -> progress.setProgress((int) (d * 1000 / total))); }
        }
      }
      return f;
    }, f -> {
      progressBox.setVisibility(View.GONE);
      String mime = it.mime;
      if (mime == null || mime.isEmpty()) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.encode(it.name));
        mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext == null ? "" : ext.toLowerCase(Locale.US));
      }
      Uri u = FileProvider.getUriForFile(this, "com.example.picloud.files", f);
      Intent v = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime != null ? mime : "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      try { startActivity(Intent.createChooser(v, "Open with")); }
      catch (Exception e) { toast("No app can open this file. Use long-press → Save to Downloads."); }
    });
  }

  void saveToDownloads(Api.Item it) {
    try {
      DownloadManager.Request req = new DownloadManager.Request(Uri.parse(api.davUrl(child(it.name)).toString()));
      req.addRequestHeader("Authorization", api.auth());
      req.setTitle(it.name);
      req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
      req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, it.name);
      ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
      toast("Downloading " + it.name + " to Downloads");
    } catch (Exception e) { toast("Download failed: " + friendly(e)); }
  }

  // ---------- upload ----------
  void showProgress(String text, int p) {
    progressBox.setVisibility(View.VISIBLE); progressText.setText(text); progress.setProgress(p);
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req != PICK || res != RESULT_OK || data == null) return;
    List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
    else if (data.getData() != null) uris.add(data.getData());
    if (uris.isEmpty()) return;
    List<String> folder = new ArrayList<>(path);
    showProgress("Preparing upload…", 0);
    async(() -> {
      int i = 0;
      for (Uri u : uris) {
        i++;
        String[] meta = nameAndSize(u);
        String name = meta[0]; long size = Long.parseLong(meta[1]);
        String label = "Uploading " + i + "/" + uris.size() + ": " + name;
        ui.post(() -> showProgress(label, 0));
        List<String> dest = new ArrayList<>(folder); dest.add(name);
        String type = getContentResolver().getType(u);
        api.put(dest, new RequestBody() {
          @Override public MediaType contentType() { return MediaType.parse(type != null ? type : "application/octet-stream"); }
          @Override public long contentLength() { return size; }
          @Override public void writeTo(BufferedSink sink) throws IOException {
            try (InputStream in = getContentResolver().openInputStream(u)) {
              if (in == null) throw new IOException("Can't read " + name);
              byte[] buf = new byte[65536]; int n; long done = 0, last = 0;
              while ((n = in.read(buf)) > 0) {
                sink.write(buf, 0, n); done += n;
                if (size > 0 && System.currentTimeMillis() - last > 150) {
                  last = System.currentTimeMillis(); long d = done;
                  ui.post(() -> progress.setProgress((int) Math.min(1000, d * 1000 / size)));
                }
              }
            }
          }
        });
      }
      return uris.size();
    }, n -> {
      progressBox.setVisibility(View.GONE);
      toast(n == 1 ? "Uploaded 1 file" : "Uploaded " + n + " files");
      if (folder.equals(path)) loadFolder();
      loadQuota();
    });
  }

  String[] nameAndSize(Uri u) {
    String name = null; long size = -1;
    try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
        if (ni >= 0) name = c.getString(ni);
        if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
      }
    } catch (Exception ignored) {}
    if (name == null || name.isEmpty()) name = "upload_" + System.currentTimeMillis();
    name = name.replace("/", "_");
    return new String[]{name, String.valueOf(size)};
  }
}
