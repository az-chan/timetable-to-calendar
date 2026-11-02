package com.ttcal.importer.xlsx;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal, dependency-free .xlsx reader.
 *
 * Pure java.* so that the same class can run both on Android and on a desktop JVM
 * (used by the verification harness). Handles sharedStrings, inlineStr, str and
 * numeric cells, and resolves sheet names through workbook.xml + its rels.
 */
public final class XlsxReader {

    public static final class Sheet {
        public final String name;
        /** rows[excelRowIndex][columnIndex] - both 0-based, null padded. */
        public final List<List<String>> rows;

        Sheet(String name, List<List<String>> rows) {
            this.name = name;
            this.rows = rows;
        }
    }

    private final Map<String, String> parts;
    private final List<String> sheetNames = new ArrayList<String>();
    private final Map<String, String> sheetPathByName = new LinkedHashMap<String, String>();
    private final List<String> sharedStrings = new ArrayList<String>();

    private static final Pattern ROW_TAG = Pattern.compile("<row\\b([^>]*?)(?:/>|>(.*?)</row>)", Pattern.DOTALL);
    private static final Pattern CELL_TAG = Pattern.compile("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL);
    private static final Pattern V_TAG = Pattern.compile("<v[^>]*>(.*?)</v>", Pattern.DOTALL);
    private static final Pattern T_TAG = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([A-Za-z_:][\\w.:-]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern SHEET_TAG = Pattern.compile("<sheet\\b([^>]*?)/?>", Pattern.DOTALL);
    private static final Pattern REL_TAG = Pattern.compile("<Relationship\\b([^>]*?)/?>", Pattern.DOTALL);

    private XlsxReader(Map<String, String> parts) throws IOException {
        this.parts = parts;
        loadSharedStrings();
        resolveSheets();
    }

    public static XlsxReader open(InputStream in) throws IOException {
        Map<String, String> map = new HashMap<String, String>();
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in));
        byte[] buf = new byte[16384];
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (!name.endsWith(".xml") && !name.endsWith(".rels")) {
                    continue;
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                long total = 0;
                int n;
                while ((n = zis.read(buf)) > 0) {
                    if (total < 16L * 1024 * 1024) {
                        bo.write(buf, 0, n);
                        total += n;
                    }
                }
                map.put(name, new String(bo.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            try {
                zis.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
        if (!map.containsKey("xl/workbook.xml")) {
            throw new IOException("不是有效的 xlsx 文件（缺少 xl/workbook.xml）");
        }
        return new XlsxReader(map);
    }

    public List<String> sheetNames() {
        return new ArrayList<String>(sheetNames);
    }

    private void loadSharedStrings() {
        String xml = parts.get("xl/sharedStrings.xml");
        if (xml == null) {
            return;
        }
        Matcher si = Pattern.compile("<si\\b[^>]*>(.*?)</si>", Pattern.DOTALL).matcher(xml);
        while (si.find()) {
            sharedStrings.add(collectText(si.group(1)));
        }
    }

    private void resolveSheets() {
        String workbook = parts.get("xl/workbook.xml");
        Map<String, String> rels = new HashMap<String, String>();
        String relXml = parts.get("xl/_rels/workbook.xml.rels");
        if (relXml != null) {
            Matcher m = REL_TAG.matcher(relXml);
            while (m.find()) {
                Map<String, String> a = attrs(m.group(1));
                if (a.get("Id") != null && a.get("Target") != null) {
                    rels.put(a.get("Id").replace("&amp;", "&"), a.get("Target"));
                }
            }
        }
        Matcher m = SHEET_TAG.matcher(workbook == null ? "" : workbook);
        int index = 0;
        while (m.find()) {
            Map<String, String> a = attrs(m.group(1));
            String name = unescape(a.containsKey("name") ? a.get("name") : ("Sheet" + (index + 1)));
            String rid = a.get("r:id");
            String target = rid == null ? null : rels.get(rid);
            String path = null;
            if (target != null) {
                path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            } else {
                path = "xl/worksheets/sheet" + (index + 1) + ".xml";
            }
            sheetNames.add(name);
            sheetPathByName.put(name, path);
            index++;
        }
        if (sheetNames.isEmpty()) {
            for (String key : parts.keySet()) {
                if (key.startsWith("xl/worksheets/") && key.endsWith(".xml")) {
                    String name = key.substring(key.lastIndexOf('/') + 1);
                    sheetNames.add(name);
                    sheetPathByName.put(name, key);
                }
            }
        }
    }

    public Sheet sheet(String name) throws IOException {
        String path = sheetPathByName.get(name);
        if (path == null) {
            throw new IOException("找不到工作表: " + name);
        }
        String xml = parts.get(path);
        if (xml == null) {
            throw new IOException("工作表内容缺失: " + path);
        }
        return new Sheet(name, parseSheet(xml));
    }

    /** Parses the first worksheet that exists (fallback when names are odd). */
    public Sheet firstSheet() throws IOException {
        if (sheetNames.isEmpty()) {
            throw new IOException("xlsx 中没有工作表");
        }
        return sheet(sheetNames.get(0));
    }

    private List<List<String>> parseSheet(String xml) {
        List<List<String>> rows = new ArrayList<List<String>>();
        Matcher rm = ROW_TAG.matcher(xml);
        int nextRow = 0;
        while (rm.find()) {
            Map<String, String> ra = attrs(rm.group(1));
            int rowNumber = nextRow;
            if (ra.containsKey("r")) {
                try {
                    rowNumber = Integer.parseInt(ra.get("r")) - 1;
                } catch (NumberFormatException ignored) {
                    rowNumber = nextRow;
                }
            }
            nextRow = rowNumber + 1;
            List<String> cells = new ArrayList<String>();
            String body = rm.group(2) == null ? "" : rm.group(2);
            Matcher cm = CELL_TAG.matcher(body);
            int nextCol = 0;
            while (cm.find()) {
                Map<String, String> ca = attrs(cm.group(1));
                int col = nextCol;
                if (ca.containsKey("r")) {
                    col = columnIndex(ca.get("r"));
                }
                nextCol = col + 1;
                String type = ca.get("t");
                String cbody = cm.group(2) == null ? "" : cm.group(2);
                String value;
                if ("s".equals(type)) {
                    Matcher vm = V_TAG.matcher(cbody);
                    int idx = vm.find() ? parseIntSafe(unescape(vm.group(1)), -1) : -1;
                    value = (idx >= 0 && idx < sharedStrings.size()) ? sharedStrings.get(idx) : "";
                } else if ("inlineStr".equals(type)) {
                    value = collectText(cbody);
                } else {
                    Matcher vm = V_TAG.matcher(cbody);
                    value = vm.find() ? unescape(vm.group(1)) : collectText(cbody);
                }
                while (cells.size() <= col) {
                    cells.add(null);
                }
                cells.set(col, value == null ? null : value.trim());
            }
            while (rows.size() <= rowNumber) {
                rows.add(new ArrayList<String>());
            }
            rows.set(rowNumber, cells);
        }
        return rows;
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String collectText(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = T_TAG.matcher(xml);
        while (m.find()) {
            sb.append(unescape(m.group(1)));
        }
        return sb.toString();
    }

    private static int columnIndex(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                col = col * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                col = col * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }
        return Math.max(col - 1, 0);
    }

    private static Map<String, String> attrs(String raw) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        Matcher m = ATTR.matcher(raw);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    static String unescape(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                continue;
            }
            int semi = s.indexOf(';', i);
            if (semi < 0) {
                sb.append(c);
                continue;
            }
            String entity = s.substring(i + 1, semi);
            i = semi;
            if (entity.equals("amp")) {
                sb.append('&');
            } else if (entity.equals("lt")) {
                sb.append('<');
            } else if (entity.equals("gt")) {
                sb.append('>');
            } else if (entity.equals("quot")) {
                sb.append('"');
            } else if (entity.equals("apos")) {
                sb.append('\'');
            } else if (entity.startsWith("#x") || entity.startsWith("#X")) {
                try {
                    sb.append((char) Integer.parseInt(entity.substring(2), 16));
                } catch (NumberFormatException e) {
                    sb.append('&').append(entity).append(';');
                }
            } else if (entity.startsWith("#")) {
                try {
                    sb.append((char) Integer.parseInt(entity.substring(1)));
                } catch (NumberFormatException e) {
                    sb.append('&').append(entity).append(';');
                }
            } else {
                sb.append('&').append(entity).append(';');
            }
        }
        return sb.toString();
    }
}
