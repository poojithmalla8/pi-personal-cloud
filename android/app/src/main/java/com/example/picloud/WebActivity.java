package com.example.picloud;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.*;
import android.widget.Toast;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class WebActivity extends Activity {
  static final int FILE_REQ = 1;
  WebView web; SwipeRefreshLayout swipe;
  ValueCallback<Uri[]> fileCb;
  String home, lastUrl;

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    setContentView(R.layout.main);
    home = getIntent().getStringExtra("server");
    if (home == null || home.isEmpty()) {
      try { home = new Api(this).server; } catch (Exception ignored) {}
    }
    if (home == null) home = "";
    lastUrl = home;
    web = findViewById(R.id.web); swipe = findViewById(R.id.swipe);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true);
    s.setLoadWithOverviewMode(true); s.setUseWideViewPort(true); s.setAllowFileAccess(false);
    CookieManager cm = CookieManager.getInstance();
    cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(web, true);
    web.addJavascriptInterface(new Object() {
      @JavascriptInterface public void retry() { runOnUiThread(() -> web.loadUrl(lastUrl)); }
    }, "Android");
    web.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String url) { swipe.setRefreshing(false); CookieManager.getInstance().flush(); }
      @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
        if (r.isForMainFrame()) { lastUrl = r.getUrl().toString(); v.loadUrl("file:///android_asset/offline.html"); }
      }
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        Uri u = r.getUrl();
        if (!home.isEmpty() && u.toString().startsWith(home)) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
        return true;
      }
    });
    web.setWebChromeClient(new WebChromeClient() {
      @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
        if (fileCb != null) fileCb.onReceiveValue(null);
        fileCb = cb;
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(Intent.createChooser(i, "Choose file"), FILE_REQ); }
        catch (Exception e) { fileCb = null; return false; }
        return true;
      }
    });
    web.setDownloadListener((url, ua, cd, mime, len) -> {
      try {
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setMimeType(mime);
        req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
        req.addRequestHeader("User-Agent", ua);
        String name = URLUtil.guessFileName(url, cd, mime);
        req.setTitle(name);
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(req);
        Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
      } catch (Exception e) { Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show(); }
    });
    swipe.setOnRefreshListener(() -> {
      if (web.getUrl() != null && web.getUrl().startsWith("file:")) web.loadUrl(lastUrl); else web.reload();
    });
    swipe.setOnChildScrollUpCallback((parent, child) -> web.getScrollY() > 0);
    if (b != null) web.restoreState(b); else {
      String u = getIntent().getStringExtra("url");
      web.loadUrl(u != null ? u : home);
    }
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    if (req == FILE_REQ && fileCb != null) {
      Uri[] r = null;
      if (res == RESULT_OK && data != null) {
        if (data.getClipData() != null) {
          int n = data.getClipData().getItemCount(); r = new Uri[n];
          for (int i = 0; i < n; i++) r[i] = data.getClipData().getItemAt(i).getUri();
        } else if (data.getData() != null) r = new Uri[]{data.getData()};
      }
      fileCb.onReceiveValue(r); fileCb = null;
    } else super.onActivityResult(req, res, data);
  }

  @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }
  @Override protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
  @Override public void onBackPressed() {
    if (web.getUrl() != null && web.getUrl().startsWith("file:") && web.canGoBack()) { web.goBack(); return; }
    if (web.canGoBack()) web.goBack(); else super.onBackPressed();
  }
}
