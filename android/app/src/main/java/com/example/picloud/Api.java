package com.example.picloud;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/** Nextcloud HTTP client: Login Flow v2, OCS quota, WebDAV. All methods are blocking (call off the UI thread). */
public class Api {
  public static final OkHttpClient http = new OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(0, TimeUnit.SECONDS).build();

  public static class ApiException extends IOException {
    public final int code;
    public ApiException(int code, String msg) { super(msg); this.code = code; }
  }

  public static class Item {
    public String name, href, mime; public long size; public boolean dir; public long modified;
  }

  /** Configured Nextcloud origin, e.g. http://100.x.x.x:8080 — never a trailing slash. */
  public String server, user, pass;
  private final SharedPreferences prefs;

  public Api(Context c) {
    SharedPreferences p;
    try {
      MasterKey mk = new MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
      p = EncryptedSharedPreferences.create(c, "secure_creds", mk,
          EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    } catch (Exception e) {
      throw new RuntimeException("Secure storage unavailable on this device: " + e.getMessage(), e);
    }
    prefs = p;
    server = prefs.getString("server", null);
    user = prefs.getString("loginName", null);
    pass = prefs.getString("appPassword", null);
  }

  public boolean hasServer() { return server != null && !server.isEmpty(); }
  public boolean loggedIn() { return hasServer() && user != null && pass != null; }

  /**
   * Accepts a user-typed address (scheme optional; trailing slash ignored) and stores it.
   * Changing the server clears any saved login so the next sign-in targets the new host.
   */
  public void setServer(String raw) {
    String normalized = normalizeServer(raw);
    boolean changed = server == null || !server.equals(normalized);
    server = normalized;
    SharedPreferences.Editor ed = prefs.edit().putString("server", server);
    if (changed) { user = null; pass = null; ed.remove("loginName").remove("appPassword"); }
    ed.apply();
  }

  public void clearServer() {
    server = null; user = null; pass = null;
    prefs.edit().clear().apply();
  }

  public void save(String loginName, String appPassword) {
    user = loginName; pass = appPassword;
    prefs.edit().putString("server", server).putString("loginName", loginName).putString("appPassword", appPassword).apply();
  }

  /** Sign out of Nextcloud but keep the saved server URL for the next sign-in. */
  public void logout() {
    user = null; pass = null;
    prefs.edit().remove("loginName").remove("appPassword").apply();
  }

  public String auth() { return Credentials.basic(user, pass); }

  /** http[s] URL with no trailing slash. Adds http:// if the user omitted a scheme. */
  public static String normalizeServer(String raw) {
    if (raw == null) throw new IllegalArgumentException("Enter your server address.");
    raw = raw.trim();
    if (raw.isEmpty()) throw new IllegalArgumentException("Enter your server address.");
    if (!raw.contains("://")) raw = "http://" + raw;
    HttpUrl u = HttpUrl.parse(raw);
    if (u == null || (!"http".equals(u.scheme()) && !"https".equals(u.scheme()))) {
      throw new IllegalArgumentException("Use an address like http://100.x.x.x:8080");
    }
    if (u.host() == null || u.host().isEmpty()) {
      throw new IllegalArgumentException("Use an address like http://100.x.x.x:8080");
    }
    StringBuilder s = new StringBuilder(u.scheme()).append("://").append(u.host());
    if (u.port() != HttpUrl.defaultPort(u.scheme())) s.append(':').append(u.port());
    String path = u.encodedPath();
    if (path != null && !path.isEmpty() && !path.equals("/")) s.append(path.replaceAll("/+$", ""));
    return s.toString();
  }

  /** Always talk to the configured server (Nextcloud may return URLs with an internal/overwrite host). */
  String rebase(String url) {
    HttpUrl u = HttpUrl.parse(url);
    if (u == null) return url;
    String q = u.encodedQuery();
    return server + u.encodedPath() + (q != null ? "?" + q : "");
  }

  // ---------- Login Flow v2 ----------
  /** returns {loginUrl, pollEndpoint, token} */
  public String[] startLogin() throws IOException {
    if (!hasServer()) throw new IOException("No server configured.");
    Request r = new Request.Builder().url(server + "/index.php/login/v2")
        .header("User-Agent", "My Pi Cloud (Android)").post(RequestBody.create(new byte[0])).build();
    try (Response resp = http.newCall(r).execute()) {
      String body = resp.body() != null ? resp.body().string() : "";
      if (!resp.isSuccessful()) throw new ApiException(resp.code(), "Login start failed (HTTP " + resp.code() + ")");
      try {
        JSONObject j = new JSONObject(body);
        JSONObject poll = j.getJSONObject("poll");
        return new String[]{rebase(j.getString("login")), rebase(poll.getString("endpoint")), poll.getString("token")};
      } catch (Exception e) { throw new IOException("Unexpected login response from server"); }
    }
  }

  /** returns {loginName, appPassword} or null if not yet granted (404). */
  public static String[] pollLogin(String endpoint, String token) throws IOException {
    Request r = new Request.Builder().url(endpoint).post(new FormBody.Builder().add("token", token).build()).build();
    try (Response resp = http.newCall(r).execute()) {
      if (resp.code() == 404) return null;
      if (!resp.isSuccessful()) throw new ApiException(resp.code(), "Login poll failed (HTTP " + resp.code() + ")");
      try {
        JSONObject j = new JSONObject(resp.body().string());
        return new String[]{j.getString("loginName"), j.getString("appPassword")};
      } catch (Exception e) { throw new IOException("Unexpected login poll response"); }
    }
  }

  // ---------- helpers ----------
  private Response exec(Request.Builder b) throws IOException {
    Response resp = http.newCall(b.header("Authorization", auth()).header("OCS-APIRequest", "true").build()).execute();
    if (resp.code() == 401) { resp.close(); throw new ApiException(401, "Your sign-in expired or was revoked. Please sign in again."); }
    return resp;
  }

  private static String errMsg(Response r, String what) {
    switch (r.code()) {
      case 403: return what + ": permission denied";
      case 404: return what + ": not found";
      case 405: return what + ": already exists";
      case 409: return what + ": parent folder missing";
      case 412: return what + ": an item with that name already exists";
      case 507: return what + ": not enough storage space";
      default: return what + " failed (HTTP " + r.code() + ")";
    }
  }

  public HttpUrl davUrl(List<String> path) {
    HttpUrl.Builder b = HttpUrl.parse(server).newBuilder().addPathSegment("remote.php").addPathSegment("dav").addPathSegment("files").addPathSegment(user);
    for (String s : path) b.addPathSegment(s);
    return b.build();
  }

  // ---------- quota ----------
  /** returns {used, free, total} in bytes */
  public long[] quota() throws IOException {
    try (Response r = exec(new Request.Builder().url(server + "/ocs/v1.php/cloud/user?format=json").header("Accept", "application/json"))) {
      if (!r.isSuccessful()) throw new ApiException(r.code(), errMsg(r, "Loading storage"));
      try {
        JSONObject q = new JSONObject(r.body().string()).getJSONObject("ocs").getJSONObject("data").getJSONObject("quota");
        long used = (long) Math.max(0, num(q, "used")), free = (long) num(q, "free"), total = (long) num(q, "total");
        long quotaSetting = (long) num(q, "quota");
        if (free < 0) free = 0;
        if (quotaSetting < 0 || total <= 0 || total < used) total = used + free; // unlimited quota (-3) or unknown
        return new long[]{used, free, total};
      } catch (org.json.JSONException e) { throw new IOException("Storage info not available from server"); }
    }
  }

  /** Nextcloud may send numbers as JSON numbers, floats or strings. */
  static double num(JSONObject o, String k) {
    Object v = o.opt(k);
    if (v instanceof Number) return ((Number) v).doubleValue();
    if (v != null) try { return Double.parseDouble(v.toString().trim()); } catch (Exception ignored) {}
    return -1;
  }

  // ---------- host stats (poojithhome Nextcloud app) ----------
  public JSONObject stats() throws IOException {
    try (Response r = exec(new Request.Builder().url(server + "/index.php/apps/poojithhome/stats").header("Accept", "application/json"))) {
      String body = r.body() != null ? r.body().string() : "";
      if (r.code() == 404 && body.contains("error")) {
        try { throw new ApiException(404, new JSONObject(body).getString("error")); } catch (org.json.JSONException ignored) {}
      }
      if (r.code() == 404) throw new ApiException(404, "System stats need the 'poojithhome' Nextcloud app (v1.1+) to be enabled.");
      if (!r.isSuccessful()) throw new ApiException(r.code(), "Loading system stats failed (HTTP " + r.code() + ")");
      try { return new JSONObject(body); } catch (org.json.JSONException e) { throw new IOException("System stats: unexpected response"); }
    }
  }

  // ---------- WebDAV ----------
  private static final String PROPFIND_BODY = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>"
      + "<d:resourcetype/><d:getcontentlength/><d:getcontenttype/><d:getlastmodified/></d:prop></d:propfind>";

  public List<Item> list(List<String> path) throws IOException {
    HttpUrl url = davUrl(path);
    Request.Builder b = new Request.Builder().url(url).header("Depth", "1")
        .method("PROPFIND", RequestBody.create(PROPFIND_BODY, MediaType.parse("application/xml")));
    String xml;
    try (Response r = exec(b)) {
      if (r.code() != 207 && !r.isSuccessful()) throw new ApiException(r.code(), errMsg(r, "Listing folder"));
      xml = r.body().string();
    }
    List<Item> out = new ArrayList<>();
    try {
      XmlPullParserFactory f = XmlPullParserFactory.newInstance(); f.setNamespaceAware(true);
      XmlPullParser p = f.newPullParser(); p.setInput(new StringReader(xml));
      Item cur = null; String tag = null;
      for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
        if (ev == XmlPullParser.START_TAG) {
          tag = p.getName();
          if ("response".equals(tag)) cur = new Item();
          else if ("collection".equals(tag) && cur != null) cur.dir = true;
        } else if (ev == XmlPullParser.TEXT && cur != null && tag != null) {
          String t = p.getText().trim();
          if (t.isEmpty()) continue;
          switch (tag) {
            case "href": cur.href = t; break;
            case "getcontentlength": try { cur.size = Long.parseLong(t); } catch (Exception ignored) {} break;
            case "getcontenttype": cur.mime = t; break;
            case "getlastmodified": try { cur.modified = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(t).getTime(); } catch (Exception ignored) {} break;
          }
        } else if (ev == XmlPullParser.END_TAG) {
          if ("response".equals(p.getName()) && cur != null && cur.href != null) {
            String h = cur.href.endsWith("/") ? cur.href.substring(0, cur.href.length() - 1) : cur.href;
            cur.name = URLDecoder.decode(h.substring(h.lastIndexOf('/') + 1).replace("+", "%2B"), "UTF-8");
            if (!url.encodedPath().replaceAll("/$", "").equals(HttpUrl.parse(server + h).encodedPath().replaceAll("/$", "")))
              out.add(cur);
            cur = null;
          }
          tag = null;
        }
      }
    } catch (Exception e) { throw new IOException("Could not read folder listing: " + e.getMessage()); }
    Collections.sort(out, (a, b2) -> a.dir != b2.dir ? (a.dir ? -1 : 1) : a.name.compareToIgnoreCase(b2.name));
    return out;
  }

  public void mkcol(List<String> path) throws IOException {
    try (Response r = exec(new Request.Builder().url(davUrl(path)).method("MKCOL", null))) {
      if (!r.isSuccessful()) throw new ApiException(r.code(), errMsg(r, "Creating folder"));
    }
  }

  public void delete(List<String> path) throws IOException {
    try (Response r = exec(new Request.Builder().url(davUrl(path)).delete())) {
      if (!r.isSuccessful()) throw new ApiException(r.code(), errMsg(r, "Deleting"));
    }
  }

  public void move(List<String> from, List<String> to) throws IOException {
    try (Response r = exec(new Request.Builder().url(davUrl(from)).method("MOVE", null)
        .header("Destination", davUrl(to).toString()).header("Overwrite", "F"))) {
      if (!r.isSuccessful()) throw new ApiException(r.code(), errMsg(r, "Renaming"));
    }
  }

  public void put(List<String> path, RequestBody body) throws IOException {
    try (Response r = exec(new Request.Builder().url(davUrl(path)).put(body))) {
      if (!r.isSuccessful()) throw new ApiException(r.code(), errMsg(r, "Uploading"));
    }
  }

  public Response get(List<String> path) throws IOException {
    Response r = exec(new Request.Builder().url(davUrl(path)).get());
    if (!r.isSuccessful()) { String m = errMsg(r, "Downloading"); r.close(); throw new ApiException(r.code(), m); }
    return r;
  }
}
