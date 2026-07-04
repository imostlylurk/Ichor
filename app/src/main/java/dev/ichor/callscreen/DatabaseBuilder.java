/*
Copyright (c) 2024 Divested Computing Group
Copyright (c) 2026 Ichor contributors

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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Downloads FTC Do Not Call daily complaint data files directly and builds
 * the spam-number database on-device — no external/pre-built server file needed.
 *
 * The FTC publishes one CSV per business day at:
 *   https://www.ftc.gov/sites/default/files/DNC_Complaint_Numbers_YYYY-MM-DD.csv
 * The listing page (do-not-call-data) links the ~25 most recent files.
 * Each CSV's first column ("Company_Phone_Number") is a 10-digit number.
 *
 * Filter tiers (each is a subset of the one above — more selective = fewer
 * numbers = fewer false positives):
 *   - Full:       every number seen at least once in the last ~30 files
 *   - HighConf:   numbers reported 2+ times in the last ~90 files
 *   - Archive:    numbers reported 10+ times across all accumulated files
 *
 * Build results are accumulated across runs: the app caches every CSV it has
 * ever fetched, so each build gets progressively richer without needing to
 * re-download. The HighConf and Archive tiers benefit most from this history.
 */
public class DatabaseBuilder {

    private static final String FTC_INDEX_URL = "https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data";
    private static final String FTC_FILE_URL_PREFIX = "https://www.ftc.gov/sites/default/files/";
    private static final Pattern CSV_LINK_PATTERN = Pattern.compile("href=\"(/sites/default/files/(DNC_Complaint_Numbers_[0-9-]+_?\\d*\\.csv))\"");
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0";

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
     * Fetch the FTC listing page, return the discovered CSV URLs keyed by
     * a sortable date string. Natural order = ascending date.
     * Returns an empty map on failure.
     */
    private TreeMap<String, String> discoverCsvLinks() {
        TreeMap<String, String> links = new TreeMap<>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(FTC_INDEX_URL).openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.addRequestProperty("User-Agent", USER_AGENT);
            connection.connect();
            if (connection.getResponseCode() != 200) {
                log("FTC index returned HTTP " + connection.getResponseCode() + "\n");
                return links;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder page = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                page.append(line);
            }
            reader.close();

            Matcher matcher = CSV_LINK_PATTERN.matcher(page.toString());
            while (matcher.find()) {
                String dateKey = sanitizeFilename(matcher.group(2)); // e.g. "DNC_Complaint_Numbers_2026-07-02"
                String relativePath = matcher.group(1).substring("/sites/default/files/".length());
                String fullUrl = FTC_FILE_URL_PREFIX + relativePath;
                links.putIfAbsent(dateKey, fullUrl);
            }
        } catch (Exception e) {
            log("Failed to fetch FTC index: " + e.getMessage() + "\n");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);
            connection.addRequestProperty("User-Agent", USER_AGENT);
            connection.connect();
            if (connection.getResponseCode() != 200) {
                return null;
            }
            File tmp = new File(cached + ".tmp");
            if (tmp.exists()) tmp.delete();
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] data = new byte[4096];
            int count;
            while ((count = connection.getInputStream().read(data)) != -1) {
                out.write(data, 0, count);
            }
            out.close();
            if (tmp.length() > 0) {
                tmp.renameTo(cached);
                return cached;
            }
            tmp.delete();
        } catch (Exception e) {
            // network blip, skip this file
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    /**
     * Build the database from cached FTC CSVs.
     * @param tier "full", "highconf", or "archive"
     */
    public void build(final String tier) {
        new Thread(() -> {
            log("Building '" + tier + "' database from FTC data...\n");

            // 1. Discover available CSVs
            TreeMap<String, String> discovered = discoverCsvLinks();
            if (discovered.isEmpty()) {
                log("No FTC data files found online. Check your connection.\n");
                return;
            }
            log("FTC has " + discovered.size() + " recent daily files listed.\n");

            // 2. Download any we don't have yet
            int downloaded = 0;
            int alreadyCached = 0;
            for (Map.Entry<String, String> entry : discovered.entrySet()) {
                boolean wasCached = new File(cacheDir, entry.getKey() + ".csv").exists();
                File cached = downloadCsvIfMissing(entry.getKey(), entry.getValue());
                if (cached != null) {
                    if (wasCached) {
                        alreadyCached++;
                    } else {
                        downloaded++;
                    }
                }
            }
            log("Downloaded " + downloaded + " new file(s), " + alreadyCached + " already cached.\n");

            // 3. Enumerate ALL cached CSVs (including from previous builds)
            File[] allCached = cacheDir.listFiles((dir, name) ->
                    name.startsWith("DNC_Complaint_Numbers_") && name.endsWith(".csv"));
            if (allCached == null || allCached.length == 0) {
                log("No CSV data available to build from.\n");
                return;
            }
            // Sort descending by filename (newest date first)
            java.util.Arrays.sort(allCached, (a, b) -> b.getName().compareTo(a.getName()));
            log("Building from " + allCached.length + " total cached file(s).\n");

            // 4. Parse CSVs and count occurrences per number
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

            // 5. Assemble the tier
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

            // 6. Write gzipped database (sorted, one number per line)
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

    /** Extract the first comma-separated field, handling quotes. */
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
