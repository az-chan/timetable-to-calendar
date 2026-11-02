package com.ttcal.importer.cal;

/** A writable system calendar shown in the picker. */
public final class CalendarOption {

    public final long id;
    public final String displayName;
    public final String accountName;
    public final String accountType;
    public final boolean local;

    public CalendarOption(long id, String displayName, String accountName, String accountType) {
        this.id = id;
        this.displayName = displayName;
        this.accountName = accountName == null ? "" : accountName;
        this.accountType = accountType == null ? "" : accountType;
        this.local = this.accountType.equalsIgnoreCase("LOCAL")
                || this.accountType.equalsIgnoreCase("com.android.calendar")
                || this.accountType.equalsIgnoreCase("LOCAL_CALENDAR")
                || this.displayName.contains("本地")
                || this.displayName.contains("手机");
    }

    public String label() {
        String account = accountName.isEmpty() ? accountType : accountName;
        String suffix = local ? "本地" : account;
        return displayName + "（" + suffix + "）";
    }

    @Override
    public String toString() {
        return label();
    }
}
