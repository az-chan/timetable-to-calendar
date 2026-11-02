package com.ttcal.importer.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.ttcal.importer.R;
import com.ttcal.importer.cal.CalendarOption;
import com.ttcal.importer.cal.CalendarRepo;
import com.ttcal.importer.cal.EventWriter;
import com.ttcal.importer.parse.Occurrence;
import com.ttcal.importer.parse.ParsedTimetable;
import com.ttcal.importer.parse.SheetPicker;
import com.ttcal.importer.parse.TimetableParser;
import com.ttcal.importer.xlsx.XlsxReader;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Main screen: choose an xlsx timetable, preview the occurrences, write them into the
 * system calendar. Everything adjustables lives behind the gear in the action bar.
 */
public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1001;
    private static final int REQ_PERM = 1002;
    private static final int MENU_SETTINGS = 2001;

    /** Reminder lead times offered in settings (minutes; 0 = no reminder). */
    private static final int[] REMIND_MINUTES = {0, 5, 10, 15, 20, 30, 60, 120, 1440};
    private static final String[] REMIND_LABELS = {
            "不提醒", "提前 5 分钟", "提前 10 分钟", "提前 15 分钟", "提前 20 分钟",
            "提前 30 分钟", "提前 1 小时", "提前 2 小时", "提前 1 天"};

    private static final String PREF_NAME = "ttcal";
    private static final String PREF_REMIND = "remind_minutes";
    private static final String PREF_CALENDAR = "calendar_id";
    private static final String PREF_MONDAY = "semester_monday";
    private static final String PREF_START = "start_date";
    private static final String PREF_SKIP_PAST = "skip_past";
    private static final String PREF_SKIP_EXISTING = "skip_existing";

    private interface DatePicked {
        void onPicked(LocalDate date);
    }

    private interface ChoiceMade {
        void onChosen(int index);
    }

    // ---- persisted / runtime state
    private LocalDate semesterMonday = LocalDate.of(2026, 9, 7);
    private LocalDate startDate = LocalDate.now();
    private int remindMinutes = 10;
    private boolean skipPast = true;
    private boolean skipExisting = true;
    private CalendarOption selectedCalendar;
    private final List<CalendarOption> calendars = new ArrayList<CalendarOption>();

    // ---- import state
    private Uri pickedUri;
    private String pickedName = "";
    private ParsedTimetable parsed;

    // ---- views
    private TextView fileLabel;
    private TextView settingsLabel;
    private TextView statusLabel;
    private TextView summaryLabel;
    private TextView warnLabel;
    private TextView previewLabel;
    private Button parseBtn;
    private Button writeBtn;
    private Button clearBtn;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSettings();
        if (Build.VERSION.SDK_INT >= 30) {
            // targetSdk 36: draw edge-to-edge and pad with the real system-bar insets
            getWindow().setDecorFitsSystemWindows(false);
        }
        setContentView(buildUi());
        if (hasCalendarPermission()) {
            reloadCalendars();
        } else {
            requestCalendarPermission();
        }
        updateSummary();
        updateButtons();
    }

    // ------------------------------------------------------------- settings io

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE);
    }

    private void loadSettings() {
        SharedPreferences p = prefs();
        String monday = p.getString(PREF_MONDAY, null);
        semesterMonday = monday == null ? LocalDate.of(2026, 9, 7) : LocalDate.parse(monday);

        LocalDate today = LocalDate.now();
        String start = p.getString(PREF_START, null);
        startDate = start == null ? today : LocalDate.parse(start);
        if (startDate.isBefore(today)) {
            startDate = today;      // never start writing in the past by default
        }
        remindMinutes = p.getInt(PREF_REMIND, 10);
        skipPast = p.getBoolean(PREF_SKIP_PAST, true);
        skipExisting = p.getBoolean(PREF_SKIP_EXISTING, true);
    }

    // ---------------------------------------------------------------- menus

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem item = menu.add(Menu.NONE, MENU_SETTINGS, Menu.NONE, "设置");
        item.setIcon(R.drawable.ic_settings);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SETTINGS) {
            showSettings();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ ui

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private View buildUi() {
        final ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        scroll.addView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    view.setPadding(0, bars.top, 0, bars.bottom);
                    return insets;
                }
            });
        }

        TextView hint = new TextView(this);
        hint.setText("把 xlsx 课表（含 星期 / 时间 / 课程 / 周次 / 上课地点）逐节写入手机日历。\n"
                + "全程离线处理，不上传任何数据。");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(getColor(R.color.text_secondary));
        hint.setPadding(0, 0, 0, dp(8));
        root.addView(hint);

        Button pickBtn = new Button(this);
        pickBtn.setText("选择 xlsx 课表");
        pickBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        root.addView(pickBtn, matchWrap());

        fileLabel = new TextView(this);
        fileLabel.setText("尚未选择文件");
        fileLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fileLabel.setTextColor(getColor(R.color.text_secondary));
        fileLabel.setPadding(dp(2), dp(6), dp(2), dp(10));
        root.addView(fileLabel);

        settingsLabel = new TextView(this);
        settingsLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        settingsLabel.setTextColor(getColor(R.color.text_hint));
        settingsLabel.setBackgroundColor(getColor(R.color.card_bg));
        settingsLabel.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(settingsLabel, matchWrap());

        parseBtn = new Button(this);
        parseBtn.setText("解析并预览");
        parseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startParse();
            }
        });
        root.addView(parseBtn, matchWrap());

        writeBtn = new Button(this);
        writeBtn.setText("写入系统日历");
        writeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startWrite();
            }
        });
        root.addView(writeBtn, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, matchWrap());

        statusLabel = new TextView(this);
        statusLabel.setText("");
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusLabel.setTextColor(getColor(R.color.status_ok));
        statusLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(statusLabel);

        summaryLabel = new TextView(this);
        summaryLabel.setText("");
        summaryLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        summaryLabel.setTextColor(getColor(R.color.text_primary));
        summaryLabel.setPadding(0, dp(10), 0, dp(4));
        root.addView(summaryLabel);

        warnLabel = new TextView(this);
        warnLabel.setText("");
        warnLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        warnLabel.setTextColor(getColor(R.color.warn));
        root.addView(warnLabel);

        previewLabel = new TextView(this);
        previewLabel.setText("");
        previewLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        previewLabel.setTextColor(getColor(R.color.text_body));
        previewLabel.setPadding(0, dp(8), 0, dp(8));
        root.addView(previewLabel);

        clearBtn = new Button(this);
        clearBtn.setText("清除本应用写入的日程");
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });
        root.addView(clearBtn, matchWrap());

        Button openCal = new Button(this);
        openCal.setText("打开系统日历查看");
        openCal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openCalendar();
            }
        });
        root.addView(openCal, matchWrap());

        return scroll;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // -------------------------------------------------------------- settings

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);

        final TextView[] mondayValue = new TextView[1];
        box.addView(dateRow("学期第 1 周周一", semesterMonday, "星期几按这一周推算",
                mondayValue, new DatePicked() {
                    @Override
                    public void onPicked(LocalDate date) {
                        semesterMonday = date;
                        prefs().edit().putString(PREF_MONDAY, date.toString()).apply();
                        parsed = null;
                        summaryLabel.setText("");
                        previewLabel.setText("");
                        setStatus("学期日期已改，请重新解析", false);
                        updateSummary();
                        refreshRangeInfo();
                        updateButtons();
                    }
                }), matchWrap());

        final TextView[] startValue = new TextView[1];
        box.addView(dateRow("写入起始", startDate, "早于这一天的课不写入",
                startValue, new DatePicked() {
                    @Override
                    public void onPicked(LocalDate date) {
                        startDate = date;
                        prefs().edit().putString(PREF_START, date.toString()).apply();
                        updateSummary();
                        refreshRangeInfo();
                        updateButtons();
                    }
                }), matchWrap());

        CheckBox skipPastBox = new CheckBox(this);
        skipPastBox.setText("只写入该日期及以后（跳过已过去的课）");
        skipPastBox.setChecked(skipPast);
        skipPastBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                skipPast = isChecked;
                prefs().edit().putBoolean(PREF_SKIP_PAST, isChecked).apply();
                updateSummary();
                refreshRangeInfo();
                updateButtons();
            }
        });
        box.addView(skipPastBox);
        skipPastBox.setPadding(0, dp(4), 0, dp(10));

        box.addView(spinnerRow("提前提醒", REMIND_LABELS, indexOfRemind(remindMinutes),
                new ChoiceMade() {
                    @Override
                    public void onChosen(int index) {
                        remindMinutes = REMIND_MINUTES[index];
                        prefs().edit().putInt(PREF_REMIND, remindMinutes).apply();
                        updateSummary();
                    }
                }), matchWrap());

        final List<String> calLabels = new ArrayList<String>();
        int selectedIndex = 0;
        for (int i = 0; i < calendars.size(); i++) {
            CalendarOption option = calendars.get(i);
            calLabels.add(option.label());
            if (selectedCalendar != null && option.id == selectedCalendar.id) {
                selectedIndex = i;
            }
        }
        if (calLabels.isEmpty()) {
            calLabels.add("（没有可写日历，请检查日历权限）");
        }
        box.addView(spinnerRow("目标日历", calLabels.toArray(new String[0]), selectedIndex,
                new ChoiceMade() {
                    @Override
                    public void onChosen(int index) {
                        if (index >= 0 && index < calendars.size()) {
                            selectedCalendar = calendars.get(index);
                            prefs().edit().putLong(PREF_CALENDAR, selectedCalendar.id).apply();
                            updateSummary();
                        }
                    }
                }), matchWrap());

        TextView calHint = new TextView(this);
        calHint.setText("建议保持「本地 / 手机」日历；云端日历会随账号同步，删除也会同步。");
        calHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        calHint.setTextColor(getColor(R.color.text_hint));
        calHint.setPadding(0, 0, 0, dp(10));
        box.addView(calHint);

        CheckBox skipExistingBox = new CheckBox(this);
        skipExistingBox.setText("跳过已存在的同名同时段事件（避免重复导入）");
        skipExistingBox.setChecked(skipExisting);
        skipExistingBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                skipExisting = isChecked;
                prefs().edit().putBoolean(PREF_SKIP_EXISTING, isChecked).apply();
                updateSummary();
            }
        });
        box.addView(skipExistingBox);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setPositiveButton("完成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        updateSummary();
                        refreshRangeInfo();
                        updateButtons();
                    }
                })
                .show();
    }

    private View dateRow(String title, LocalDate value, String hint, final TextView[] outLabel,
                         final DatePicked callback) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, dp(6));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueLabel = new TextView(this);
        valueLabel.setText(value.toString());
        valueLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        valueLabel.setTypeface(valueLabel.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(valueLabel);
        outLabel[0] = valueLabel;

        Button change = new Button(this);
        change.setText("修改");
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                java.time.LocalDate current = LocalDate.parse(valueLabel.getText().toString());
                DatePickerDialog dialog = new DatePickerDialog(MainActivity.this,
                        new DatePickerDialog.OnDateSetListener() {
                            @Override
                            public void onDateSet(DatePicker view, int year, int month, int day) {
                                LocalDate picked = LocalDate.of(year, month + 1, day);
                                valueLabel.setText(picked.toString());
                                callback.onPicked(picked);
                            }
                        }, current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth());
                dialog.show();
            }
        });
        row.addView(change, new LinearLayout.LayoutParams(dp(72),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(row);

        TextView note = new TextView(this);
        note.setText(hint);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        note.setTextColor(getColor(R.color.text_hint));
        column.addView(note);
        return column;
    }

    private View spinnerRow(String title, String[] items, int selected, final ChoiceMade callback) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(name, new LinearLayout.LayoutParams(dp(96),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        if (selected >= 0 && selected < items.length) {
            spinner.setSelection(selected);
        }
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                callback.onChosen(position);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        row.addView(spinner, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void updateSummary() {
        if (settingsLabel == null) {
            return;
        }
        String calendar = selectedCalendar == null ? "未找到可写日历" : selectedCalendar.label();
        StringBuilder sb = new StringBuilder();
        sb.append("学期首周一 ").append(semesterMonday);
        if (skipPast) {
            boolean today = startDate.equals(LocalDate.now());
            sb.append(" · 起始 ").append(startDate).append(today ? "（今天）" : "");
        } else {
            sb.append(" · 起始 不限（写全部课次）");
        }
        sb.append(" · ").append(REMIND_LABELS[indexOfRemind(remindMinutes)]);
        sb.append("\n目标日历 ").append(calendar);
        sb.append(skipExisting ? " · 跳过重复" : " · 允许重复");
        sb.append("\n点右上角 ⚙ 可修改以上设置");
        settingsLabel.setText(sb.toString());
    }

    private static int indexOfRemind(int minutes) {
        for (int i = 0; i < REMIND_MINUTES.length; i++) {
            if (REMIND_MINUTES[i] == minutes) {
                return i;
            }
        }
        return 2;
    }

    // ---------------------------------------------------------- permissions

    private boolean hasCalendarPermission() {
        return checkSelfPermission("android.permission.READ_CALENDAR") == 0
                && checkSelfPermission("android.permission.WRITE_CALENDAR") == 0;
    }

    private void requestCalendarPermission() {
        requestPermissions(new String[]{"android.permission.READ_CALENDAR",
                "android.permission.WRITE_CALENDAR"}, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERM) {
            if (hasCalendarPermission()) {
                reloadCalendars();
                setStatus("已获得日历权限，可以写入日程了", false);
            } else {
                setStatus("没有日历权限，无法写入日程。请到 设置→应用→课表导入→权限 中开启「日历」。", true);
            }
            updateSummary();
            updateButtons();
        }
    }

    /**
     * The calendar picker lives in settings only: we keep using the calendar chosen
     * last time (default: the first local calendar).
     */
    private void reloadCalendars() {
        calendars.clear();
        calendars.addAll(CalendarRepo.writable(getContentResolver()));
        selectedCalendar = null;
        long savedId = prefs().getLong(PREF_CALENDAR, -1L);
        for (CalendarOption option : calendars) {
            if (option.id == savedId) {
                selectedCalendar = option;
                break;
            }
        }
        if (selectedCalendar == null && !calendars.isEmpty()) {
            selectedCalendar = calendars.get(0);
            prefs().edit().putLong(PREF_CALENDAR, selectedCalendar.id).apply();
        }
        if (selectedCalendar == null) {
            setStatus("没有找到可写入的日历，请到系统设置里给「课表导入」开启日历权限", true);
        }
    }

    // ------------------------------------------------------------ file pick

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel",
                "application/octet-stream"});
        try {
            startActivityForResult(intent, REQ_OPEN);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        pickedUri = uri;
        pickedName = queryDisplayName(uri);
        fileLabel.setText("已选择：" + pickedName
                + (pickedName.toLowerCase().endsWith(".xlsx") ? "" : "\n注意：本应用只支持 .xlsx，请先另存为 xlsx"));
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // not all providers grant persistable access; reading once is enough
        }
        parsed = null;
        summaryLabel.setText("");
        previewLabel.setText("");
        warnLabel.setText("");
        updateButtons();
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // fall through to the path fallback
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return uri.getLastPathSegment() == null ? "未命名.xlsx" : uri.getLastPathSegment();
    }

    // --------------------------------------------------------------- parse

    private void startParse() {
        if (pickedUri == null) {
            setStatus("请先选择 xlsx 文件", true);
            return;
        }
        setBusy(true);
        setStatus("正在解析…", false);
        final Uri uri = pickedUri;
        final LocalDate monday = semesterMonday;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ContentResolver cr = getContentResolver();
                    InputStream in = cr.openInputStream(uri);
                    if (in == null) {
                        throw new IllegalStateException("无法读取文件");
                    }
                    XlsxReader book;
                    try {
                        book = XlsxReader.open(in);
                    } finally {
                        in.close();
                    }
                    XlsxReader.Sheet sheet = SheetPicker.pick(book);
                    final ParsedTimetable result = TimetableParser.fromSheet(sheet.rows, monday, sheet.name);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            parsed = result;
                            showParsed(result);
                            setBusy(false);
                            updateButtons();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            parsed = null;
                            setBusy(false);
                            updateButtons();
                            setStatus("解析失败：" + describe(e), true);
                        }
                    });
                }
            }
        }).start();
    }

    private void showParsed(ParsedTimetable result) {
        StringBuilder warn = new StringBuilder();
        for (int i = 0; i < result.warnings.size() && i < 4; i++) {
            warn.append(result.warnings.get(i)).append('\n');
        }
        if (result.warnings.size() > 4) {
            warn.append("… 共 ").append(result.warnings.size()).append(" 条提示");
        }
        warnLabel.setText(warn.toString());
        refreshRangeInfo();
        setStatus("解析完成：" + writableOccurrences().size() + " 个课次待写入（共 "
                + result.occurrences.size() + " 个）", false);
    }

    /** Occurrences that will actually be written, honouring the date filter. */
    private List<Occurrence> writableOccurrences() {
        if (parsed == null) {
            return new ArrayList<Occurrence>();
        }
        if (!skipPast) {
            return new ArrayList<Occurrence>(parsed.occurrences);
        }
        return Occurrence.filterFromDate(parsed.occurrences, startDate);
    }

    private void refreshRangeInfo() {
        if (parsed == null || summaryLabel == null) {
            return;
        }
        List<Occurrence> list = writableOccurrences();
        int skipped = parsed.occurrences.size() - list.size();
        StringBuilder sb = new StringBuilder(parsed.summary());
        sb.append("\n\n将写入 ").append(list.size()).append(" 条");
        if (skipped > 0) {
            sb.append("，跳过 ").append(skipped).append(" 条早于 ")
                    .append(startDate).append(" 的课");
        }
        summaryLabel.setText(sb.toString());

        StringBuilder preview = new StringBuilder();
        int limit = Math.min(list.size(), 60);
        for (int i = 0; i < limit; i++) {
            preview.append(list.get(i).previewLine()).append('\n');
        }
        if (list.size() > limit) {
            preview.append("… 另有 ").append(list.size() - limit).append(" 条");
        }
        if (list.isEmpty() && parsed.occurrences.size() > 0) {
            preview.append("该日期之后没有课次，可在设置里把「写入起始」往前调。");
        }
        previewLabel.setText(preview.toString());
    }

    // --------------------------------------------------------------- write

    private void startWrite() {
        if (parsed == null || parsed.occurrences.isEmpty()) {
            setStatus("没有可写入的课次，请先解析", true);
            return;
        }
        if (writableOccurrences().isEmpty()) {
            setStatus("写入起始日期之后没有课次，请在设置里调整「写入起始」", true);
            return;
        }
        if (!hasCalendarPermission()) {
            requestCalendarPermission();
            return;
        }
        final CalendarOption target = selectedCalendar;
        if (target == null) {
            setStatus("没有可写的日历，请确认日历权限", true);
            return;
        }
        final List<Occurrence> list = writableOccurrences();
        final int remind = remindMinutes;
        final boolean skip = skipExisting;
        setBusy(true);
        progress.setProgress(0);
        setStatus("正在写入「" + target.label() + "」…", false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final EventWriter.Result result = EventWriter.write(getContentResolver(),
                            target.id, list, remind, skip, new EventWriter.ProgressSink() {
                                @Override
                                public void onProgress(final int written, final int total, String message) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            progress.setProgress(total == 0 ? 0 : written * 100 / total);
                                            statusLabel.setText("写入中 " + written + "/" + total);
                                        }
                                    });
                                }
                            });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            setStatus("完成：" + result.describe() + String.valueOf((char) 10)
                                    + "→ " + target.label(), result.failed > 0);
                            updateButtons();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            setStatus("写入失败：" + describe(e), true);
                            updateButtons();
                        }
                    });
                }
            }
        }).start();
    }

    // --------------------------------------------------------------- clear

    private void confirmClear() {
        if (!hasCalendarPermission()) {
            requestCalendarPermission();
            return;
        }
        final int count = CalendarRepo.countMarked(getContentResolver());
        if (count <= 0) {
            setStatus("没有找到本应用写入的日程", false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认清除")
                .setMessage("将删除 " + count + " 条由「课表导入」写入的日程，其他日程不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int deleted = CalendarRepo.deleteMarked(getContentResolver());
                        setStatus(deleted < 0 ? "删除失败：没有日历写权限" : "已删除 " + deleted + " 条日程",
                                deleted < 0);
                    }
                })
                .show();
    }

    private void openCalendar() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("content://com.android.calendar/time/"));
            startActivity(intent);
            return;
        } catch (Exception ignored) {
            // fall through
        }
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR));
        } catch (Exception e) {
            Toast.makeText(this, "没有找到日历应用", Toast.LENGTH_SHORT).show();
        }
    }

    // --------------------------------------------------------------- state

    private void updateButtons() {
        boolean ready = !writableOccurrences().isEmpty();
        if (parseBtn != null) {
            parseBtn.setEnabled(pickedUri != null);
        }
        if (writeBtn != null) {
            writeBtn.setEnabled(ready && hasCalendarPermission());
        }
        if (clearBtn != null) {
            clearBtn.setEnabled(hasCalendarPermission());
        }
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (parseBtn != null) {
            parseBtn.setEnabled(!busy && pickedUri != null);
        }
        if (writeBtn != null) {
            writeBtn.setEnabled(!busy && !writableOccurrences().isEmpty() && hasCalendarPermission());
        }
    }

    private void setStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.setTextColor(error ? getColor(R.color.status_error) : getColor(R.color.status_ok));
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message;
    }
}
