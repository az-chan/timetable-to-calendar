package com.ttcal.importer.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a location cell into segments, honouring week-scoped variants such as
 * "主楼B405（7,9,11,13,15周）；2A-109（1,3周）" and plain notes such as "1H-403（智慧黑板）".
 */
public final class LocationCell {

    public static final class Seg {
        public final String place;
        public final String note;
        /** Empty list means "inherits the whole row's weeks". */
        public final List<Integer> weeks;

        Seg(String place, String note, List<Integer> weeks) {
            this.place = place;
            this.note = note;
            this.weeks = weeks;
        }

        public boolean appliesTo(int week) {
            return weeks.isEmpty() || weeks.contains(week);
        }
    }

    private static final Pattern BRACKET = Pattern.compile("[（(]([^（()）]*)[）)]");
    private static final Pattern NO_VENUE = Pattern.compile("不用场地|无场地|网络授课|线上|网课|无");

    private LocationCell() {
    }

    public static List<Seg> parse(String raw) {
        List<Seg> result = new ArrayList<Seg>();
        if (raw == null || raw.trim().isEmpty()) {
            result.add(new Seg("", "", new ArrayList<Integer>()));
            return result;
        }
        for (String part : raw.split("[；;]")) {
            String piece = part.trim();
            if (piece.isEmpty()) {
                continue;
            }
            StringBuilder note = new StringBuilder();
            List<Integer> weeks = new ArrayList<Integer>();
            Matcher m = BRACKET.matcher(piece);
            while (m.find()) {
                String inner = m.group(1).trim();
                if (inner.indexOf('周') >= 0) {
                    for (Integer w : Weeks.parse(inner)) {
                        if (!weeks.contains(w)) {
                            weeks.add(w);
                        }
                    }
                } else if (!inner.isEmpty()) {
                    if (note.length() > 0) {
                        note.append(' ');
                    }
                    note.append(inner);
                }
            }
            String place = BRACKET.matcher(piece).replaceAll("").trim();
            if (NO_VENUE.matcher(place).find() && place.length() <= 6) {
                if (note.length() > 0) {
                    note.append(' ');
                }
                note.append(place);
                place = "";
            }
            result.add(new Seg(place, note.toString(), weeks));
        }
        if (result.isEmpty()) {
            result.add(new Seg("", "", new ArrayList<Integer>()));
        }
        return result;
    }
}
