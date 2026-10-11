/*
This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
package dev.ichor.callscreen;

import android.content.Context;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Downloads FTC Do Not Call daily complaint data files directly and builds the spam-number database on-device.
 * Carrion depended on the files being hosted at the Divested website, Ichor is freestanding
 *
 * The FTC publishes one CSV per business day at:
 *   https://www.ftc.gov/sites/default/files/DNC_Complaint_Numbers_YYYY-MM-DD.csv
 * The listing page (do-not-call-data) links the ~25 most recent files.
 * Each CSV's first column ("Company_Phone_Number") is a 10-digit number.
 *
 * Filter tiers (each is a subset of the one above - more selective = fewer
 * numbers = fewer false positives):
 *   - Full:       every number seen at least once in the last ~30 files
 *   - HighConf:   numbers reported 2+ times in the last ~90 files
 *   - Archive:    numbers reported 10+ times across all accumulated files
 *
 * Build results are accumulated across runs: the app caches every csv it has
 * ever fetched, so each build gets progressively richer without needing to
 * re-download. The HighConf and Archive tiers benefit most from this history.
 */
public class DatabaseBuilder {

    private static final String FTC_INDEX_URL = "https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data";
    private static final String FTC_FILE_URL_PREFIX = "https://www.ftc.gov/sites/default/files/";
    private static final Pattern CSV_LINK_PATTERN = Pattern.compile("href=\"(/sites/default/files/(DNC_Complaint_Numbers_[0-9-]+_?\\d*\\.csv))\"");
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

    /**
     * The FTC WAF fingerprints request headers AND HttpURLConnection silently
     * drops Sec-Fetch-* headers (they are restricted), so a browser-like UA
     * sent through HttpURLConnection can never look like a real browser and
     * gets a fake HTTP 404 on every ftc.gov URL. Speaking raw HTTP/1.1 over an
     * SSLSocket lets us send the complete browser header set verbatim.
     *
     * Handles hostname verification, redirects (<=3), chunked transfer
     * encoding, and gzip content encoding. Returns the decoded body bytes.
     */
    private static byte[] httpsGet(String urlStr) throws Exception {
        String current = urlStr;
        for (int hop = 0; hop < 4; hop++) {
            URL u = new URL(current);
            String host = u.getHost();
            int port = u.getPort() != -1 ? u.getPort() : 443;
            String path = u.getPath() + (u.getQuery() != null ? "?" + u.getQuery() : "");

            javax.net.ssl.SSLSocketFactory factory = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            javax.net.ssl.SSLSocket socket = (javax.net.ssl.SSLSocket) factory.createSocket();
            javax.net.ssl.SSLParameters params = socket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS"); // hostname verification
            socket.setSSLParameters(params);
            socket.connect(new InetSocketAddress(host, port), 30000);
            socket.setSoTimeout(30000);

            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "User-Agent: " + USER_AGENT + "\r\n"
                    + "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8\r\n"
                    + "Accept-Language: en-US,en;q=0.5\r\n"
                    + "Accept-Encoding: gzip\r\n"
                    + "Upgrade-Insecure-Requests: 1\r\n"
                    + "Sec-Fetch-Dest: document\r\n"
                    + "Sec-Fetch-Mode: navigate\r\n"
                    + "Sec-Fetch-Site: none\r\n"
                    + "Sec-Fetch-User: ?1\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            OutputStream os = socket.getOutputStream();
            os.write(request.getBytes(StandardCharsets.UTF_8));
            os.flush();

            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = socket.getInputStream().read(buf)) != -1) {
                    raw.write(buf, 0, n);
                }
            } catch (SocketTimeoutException ste) {
                // use whatever arrived before the read timeout
            }
            socket.close();
            byte[] resp = raw.toByteArray();

            int headerEnd = indexOf(resp, "\r\n\r\n");
            if (headerEnd < 0) {
                throw new java.io.IOException("malformed HTTP response");
            }
            String headerBlock = new String(resp, 0, headerEnd, StandardCharsets.ISO_8859_1);
            String statusLine = headerBlock.split("\r\n")[0];
            int code = Integer.parseInt(statusLine.split(" ")[1]);
            String headersLower = headerBlock.toLowerCase();

            if (code >= 300 && code < 400) {
                String location = null;
                for (String h : headerBlock.split("\r\n")) {
                    if (h.toLowerCase().startsWith("location:")) {
                        location = h.substring("location:".length()).trim();
                        break;
                    }
                }
                if (location != null) {
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
            }
            if (code != 200) {
                throw new java.io.IOException("HTTP " + code);
            }

            InputStream body = new ByteArrayInputStream(resp, headerEnd + 4, resp.length - headerEnd - 4);
            if (headersLower.contains("transfer-encoding:") && headersLower.contains("chunked")) {
                body = new ByteArrayInputStream(dechunk(body));
            }
            if (headersLower.contains("content-encoding:")) {
                int idx = headersLower.indexOf("content-encoding:");
                String rest = headersLower.substring(idx + "content-encoding:".length()).trim();
                if (rest.startsWith("gzip")) {
                    body = new GZIPInputStream(body);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int k;
            while ((k = body.read(b)) != -1) {
                out.write(b, 0, k);
            }
            return out.toByteArray();
        }
        throw new java.io.IOException("too many redirects");
    }

    /** Find a byte-sequence delimiter in a byte array, -1 if absent. */
    private static int indexOf(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i <= haystack.length - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (haystack[i + j] != n[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Decode HTTP/1.1 chunked transfer encoding. */
    private static byte[] dechunk(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            sizeLine = sizeLine.trim();
            if (sizeLine.isEmpty()) {
                continue;
            }
            int size;
            try {
                size = Integer.parseInt(sizeLine.split(";")[0].trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (size == 0) {
                break;
            }
            byte[] chunk = new byte[size];
            int read = 0;
            while (read < size) {
                int c = in.read(chunk, read, size - read);
                if (c < 0) {
                    break;
                }
                read += c;
            }
            out.write(chunk, 0, read);
            readLine(in); // consume trailing CRLF after each chunk
        }
        return out.toByteArray();
    }

    /** Read a CRLF-terminated line from a stream as ISO-8859-1 bytes. */
    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int prev = -1;
        int c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                byte[] b = line.toByteArray();
                return new String(b, 0, b.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(c);
            prev = c;
        }
        return line.size() > 0 ? new String(line.toByteArray(), StandardCharsets.ISO_8859_1) : null;
    }

    private final Context context;
    private final TextView logView;
    private final File cacheDir;
    private final File databaseFile;

    public DatabaseBuilder(Context context, TextView logView, File databaseFile) {
        this.context = context;
        this.logView = logView;
        this.databaseFile = databaseFile;
        this.cacheDir = new File(context.getCacheDir(), "ftc_csv_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
    }

    /** Delete cached FTC CSV files so the next build starts fresh. */
    public static void clearCache(Context context) {
        File dir = new File(context.getCacheDir(), "ftc_csv_cache");
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    /**
     * Fetch the FTC listing page, return the discovered CSV URLs keyed by a date string. Natural order = ascending date.
     * Returns an empty map on failure.
     */
    private TreeMap<String, String> discoverCsvLinks() {
        TreeMap<String, String> links = new TreeMap<>();
        try {
            String page = new String(httpsGet(FTC_INDEX_URL), StandardCharsets.UTF_8);

            Matcher matcher = CSV_LINK_PATTERN.matcher(page);
            while (matcher.find()) {
                String dateKey = sanitizeFilename(matcher.group(2)); // e.g. "DNC_Complaint_Numbers_2026-07-02"
                String relativePath = matcher.group(1).substring("/sites/default/files/".length());
                String fullUrl = FTC_FILE_URL_PREFIX + relativePath;
                links.putIfAbsent(dateKey, fullUrl);
            }
        } catch (Exception e) {
            log("Failed to fetch FTC index: " + e.getMessage() + "\n");
        }
        return links;
    }

    /**
     * Download a CSV into the cache if not already present.
     * Returns the cached File, or null on failure.
     */
    private File downloadCsvIfMissing(String dateKey, String url) {
        File cached = new File(cacheDir, dateKey + ".csv");
        if (cached.exists() && cached.length() > 0) {
            return cached; // already have it
        }
        try {
            byte[] data = httpsGet(url);
            if (data.length == 0) {
                return null;
            }
            File tmp = new File(cached + ".tmp");
            if (tmp.exists()) tmp.delete();
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(data);
            out.close();
            tmp.renameTo(cached);
            return cached;
        } catch (Exception e) {
            // network blip, skip this file
        }
        return null;
    }

    /**
     * Build the database from cached FTC csvs.
     * @param tier "full", "highconf", or "archive"
     */
    public void build(final String tier) {
        new Thread(() -> {
            log("Building '" + tier + "' database from FTC data...\n");

            TreeMap<String, String> discovered = discoverCsvLinks();
            if (discovered.isEmpty()) {
                log("No FTC data files found online.\n");
                log("If your connection works, the FTC may be rate-limiting this");
                log(" network (its block masquerades as HTTP 404 on all ftc.gov");
                log(" pages). Wait a few hours or try another network.\n");
                File[] cached = cacheDir.listFiles((dir, name) ->
                        name.startsWith("DNC_Complaint_Numbers_") && name.endsWith(".csv"));
                if (cached == null || cached.length == 0) {
                    return;
                }
                log("Building from " + cached.length + " cached file(s) only.\n");
            } else {
                log("FTC has " + discovered.size() + " recent daily files listed.\n");
            }

            int downloaded = 0;
            int alreadyCached = 0;
            int consecutiveFailures = 0;
            for (Map.Entry<String, String> entry : discovered.entrySet()) {
                boolean wasCached = new File(cacheDir, entry.getKey() + ".csv").exists();
                if (!wasCached && downloaded > 0) {
                    try {
                        Thread.sleep(1500); // pace requests; burst-fetching trips the FTC WAF
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                File cached = downloadCsvIfMissing(entry.getKey(), entry.getValue());
                if (cached != null) {
                    consecutiveFailures = 0;
                    if (wasCached) {
                        alreadyCached++;
                    } else {
                        downloaded++;
                    }
                } else if (!wasCached) {
                    consecutiveFailures++;
                    if (consecutiveFailures >= 3) {
                        log("Repeated download failures, aborting batch. The FTC");
                        log(" is likely blocking this network. Try again later.\n");
                        break;
                    }
                }
            }
            log("Downloaded " + downloaded + " new file(s), " + alreadyCached + " already cached.\n");

            File[] allCached = cacheDir.listFiles((dir, name) ->
                    name.startsWith("DNC_Complaint_Numbers_") && name.endsWith(".csv"));
            if (allCached == null || allCached.length == 0) {
                log("No CSV data available to build from.\n");
                return;
            }

            java.util.Arrays.sort(allCached, (a, b) -> b.getName().compareTo(a.getName()));
            log("Building from " + allCached.length + " total cached file(s).\n");

            HashSet<String> allNumbersRecent30 = new HashSet<>();      // for "full" tier
            TreeMap<String, Integer> countsRecent90 = new TreeMap<>(); // for "highconf" tier
            TreeMap<String, Integer> countsAll = new TreeMap<>();      // for "archive" tier

            int fileIndex = 0;
            for (File csv : allCached) {
                boolean within30 = fileIndex < 30;
                boolean within90 = fileIndex < 90;
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(java.nio.file.Files.newInputStream(csv.toPath())));
                    String line;
                    boolean firstLine = true;
                    while ((line = reader.readLine()) != null) {
                        if (firstLine) {
                            firstLine = false;
                            continue; // skip header row
                        }
                        String number = extractFirstField(line);
                        if (number != null && number.length() == 10 && number.chars().allMatch(Character::isDigit)) {
                            if (within30) {
                                allNumbersRecent30.add(number);
                            }
                            if (within90) {
                                countsRecent90.merge(number, 1, Integer::sum);
                            }
                            countsAll.merge(number, 1, Integer::sum);
                        }
                    }
                    reader.close();
                } catch (Exception e) {
                    // skip corrupt file
                }
                fileIndex++;
            }

            HashSet<String> result = new HashSet<>();
            switch (tier) {
                case "archive":
                    // Most selective: 10+ reports across ALL cached files
                    for (Map.Entry<String, Integer> e : countsAll.entrySet()) {
                        if (e.getValue() >= 10) result.add(e.getKey());
                    }
                    log("Archive: " + result.size() + " numbers (10+ reports, all files)\n");
                    break;
                case "highconf":
                    // Balanced: 2+ reports across recent 90 files
                    for (Map.Entry<String, Integer> e : countsRecent90.entrySet()) {
                        if (e.getValue() >= 2) result.add(e.getKey());
                    }
                    log("HighConf: " + result.size() + " numbers (2+ reports, recent 90)\n");
                    break;
                case "full":
                default:
                    // Maximum coverage: all numbers reported in recent 30 files
                    result.addAll(allNumbersRecent30);
                    log("Full: " + result.size() + " numbers (all reported, recent 30)\n");
                    break;
            }

            if (result.isEmpty()) {
                log("Database build produced 0 numbers, aborting.\n");
                return;
            }

            File outFile = new File(databaseFile + ".new");
            if (outFile.exists()) outFile.delete();
            try {
                FileOutputStream fos = new FileOutputStream(outFile);
                GZIPOutputStream gzip = new GZIPOutputStream(fos);
                java.util.TreeSet<String> sorted = new java.util.TreeSet<>(result);
                StringBuilder sb = new StringBuilder();
                for (String num : sorted) {
                    sb.append(num).append("\n");
                }
                gzip.write(sb.toString().getBytes());
                gzip.close();
                fos.close();

                if (outFile.length() > 0 && outFile.length() <= (1000 * 1000 * 4)) {
                    if (databaseFile.exists()) databaseFile.delete();
                    outFile.renameTo(databaseFile);
                    log("Database built successfully: " + result.size() + " numbers, " + (databaseFile.length() / 1024) + " KB compressed\n");
                    log("Done! The screening service will use this immediately.\n");
                } else {
                    log("Build completed but output size looks wrong (" + outFile.length() + " bytes), aborting.\n");
                    outFile.delete();
                }
            } catch (Exception e) {
                log("Failed to write database: " + e.getMessage() + "\n");
            }
        }).start();
    }

    private static String extractFirstField(String line) {
        if (line == null || line.isEmpty()) return null;
        line = line.replace("\r", "");
        if (line.startsWith("\"")) {
            int end = line.indexOf("\"", 1);
            if (end > 0) return line.substring(1, end);
            return null;
        }
        int comma = line.indexOf(",");
        if (comma > 0) return line.substring(0, comma);
        return line.trim();
    }

    /** Convert a URL filename to a safe cache filename. */
    private static String sanitizeFilename(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void log(final String message) {
        if (logView != null) {
            logView.post(() -> logView.append(message));
        }
    }
}
