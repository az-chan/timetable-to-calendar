# 开发交接（HANDOFF）

面向要修改、扩展或重新发布本程序的开发者。本文只讲**怎么改**，不涉及任何个人数据。

- 仓库只包含程序源码与文档；课表 xlsx、导出的 `.ics`、签名私钥、构建产物都不会入库。
- 程序由 AI 生成，仅在 **vivo OriginOS 7（Android 16）** 实测通过。

---

## 1. 技术栈与设计约束

| 项 | 选择 | 原因 |
| --- | --- | --- |
| 语言 | Java（源码级别 8） | 只用 `javac + d8` 就能出包，不需要 Kotlin 编译器 |
| 依赖 | 仅 Android framework，无 AndroidX、无第三方库 | 离线可构建、包体极小（~50 KB）；代价是只能用平台 View，用不到 Material 组件与 ActivityResult API |
| 构建 | 命令行脚本 `build/build_apk.py`，**不用 Gradle** | 见第 4 节 |
| 解析层 | 纯 `java.*`，零 Android 依赖 | 同一份代码可在桌面 JVM 跑（`CliMain`），才能和 Python 参照实现逐条比对 |
| 最低版本 | minSdk 26（Android 8.0），targetSdk 36 | 见第 6 节 arsc 约束 |

## 2. 目录与职责

```
app/
├─ AndroidManifest.xml            权限（仅 READ/WRITE_CALENDAR）、Activity、主题
├─ res/
│  ├─ values/ + values-night/     颜色、主题（深色模式靠 values-night 自动切换）
│  ├─ drawable/ + drawable-night/ 设置齿轮图标（两套颜色）
│  └─ mipmap-*/                   图标（由 build_apk.py 用 Pillow 生成，可覆盖）
└─ src/com/ttcal/importer/
   ├─ xlsx/XlsxReader.java        最小 xlsx 读取器：zip + 手写 XML 扫描 + 共享字符串
   ├─ parse/                      Weekday / Weeks / TimeRange / LocationCell /
   │                              TimetableParser / SheetPicker / Row / Occurrence / ParsedTimetable
   ├─ cal/                        CalendarRepo（列可写日历、计数、清除）
   │                              EventWriter（批量写入 + 去重 + 提醒）
   ├─ ui/MainActivity.java        单页界面（纯代码布局，无 XML 布局）
   └─ cli/CliMain.java            桌面入口，供 tools/verify_parse.py 调用
tools/
   ├─ fetch_toolchain.py          下载并解压 JDK / build-tools / platform
   ├─ verify_parse.py             独立 Python 解析实现 + 与 Java 逐条 diff + 单元用例
   ├─ test_fixtures.py            生成刁钻 xlsx 并断言（坏行、周次变体、网课、标题行）
   └─ xlsx2ics.py                 导出 .ics（备用导入路径）
build/
   ├─ build_apk.py                aapt2 → javac → d8 → 打包 → zipalign → apksigner → 自检
   └─ keystore.jks                本地签名证书（不入库，自行生成）
```

## 3. 数据流

```
xlsx 文件
  └─ XlsxReader          读工作表 → List<List<String>>
      └─ SheetPicker     选出真正含表头的那张表
          └─ TimetableParser
               ├─ TimetableParser.headerMap()  按关键词定位列（星期/时间/课程/周次/教师/地点/日期）
               ├─ Weeks.parse()                展开周次集合（支持 1-16、1,3,5、3（4学时））
               ├─ TimeRange.parse()            解析 08:00-09:35
               ├─ LocationCell.parse()         地点按 ; 分段，括号里的「…周」决定该段适用周次
               └─ 组合：date = 学期首周一 + (week-1)*7 + (weekday-1)
                    → Occurrence（每节课一条）
                        └─ EventWriter.write()
                             ├─ 去重：查目标日历区间内「标题+DTSTART」已存在则跳过
                             ├─ Events.bulkInsert → 取 id
                             └─ Reminders 批量写入（提前 N 分钟）
```

事件字段：`TITLE=课程`、`EVENT_LOCATION=地点`、`DESCRIPTION=教师/周次/备注`、
`DTSTART/DTEND`（`Asia/Shanghai`）、`CUSTOM_APP_URI`（每条唯一，用于清理）、`CUSTOM_APP_PACKAGE`。

## 4. 构建

环境：JDK 17、Android SDK（build-tools + 一个 platform）、Python 3（含 Pillow）。

```bash
python tools/fetch_toolchain.py    # 首次：把 JDK/build-tools/platform 下到 .toolchain/（默认走国内镜像）
python build/build_apk.py          # 出包：dist/<名字>-v<版本>.apk
```

脚本内置**硬性自检**，任一项不满足直接构建失败：

- `resources.arsc` 必须 STORED（不压缩）
- `zipalign -c -p 4` 通过
- `classes.dex`、`AndroidManifest.xml` 存在
- v2/v3 签名有效
- `aapt2 dump badging` 的 minSdk / targetSdk 与脚本常量一致

改版本号要动**两处**并保持一致：`build/build_apk.py` 的 `VERSION / VERSION_CODE`，
以及 `app/AndroidManifest.xml` 的 `versionCode / versionName`。

签名：脚本首次会在 `build/keystore.jks` 生成自签证书（口令写在脚本里）。
换证书 → 已安装用户必须卸载重装，正式发布请自备证书并妥善保管私钥（**不要提交**）。

## 5. 关键不变量（改代码前务必知道）

1. **`resources.arsc` 不能压缩**：Android 11+ 要求 STORED + 4 字节对齐，否则安装器报「不兼容/解析失败」。
   不要绕过 `build_apk.py` 手工 zip 打包。
2. **解析层不许 import Android**：否则桌面验证链路断掉。`CliMain` 与 App 共用同一份解析代码。
3. **课次展开成单次事件，不用 RRULE**：课表有单双周、跳周、地点随周变化，展开才准确；
   代价是事件多，但删除/去重更简单。
4. **事件身份放元数据**：`CUSTOM_APP_URI`（`content://com.ttcal.importer/occ/<sha1>`）+ `CUSTOM_APP_PACKAGE`。
   不要把内部标记写进 `DESCRIPTION`（用户能看见）。
5. **时区固定 `Asia/Shanghai`**；`LocalDate`/`LocalTime` 直接可用（minSdk 26 免脱糖）。
6. **设置项统一走 `SharedPreferences("ttcal")`**：`semester_monday`、`start_date`、`remind_minutes`、
   `calendar_id`、`skip_past`、`skip_existing`。

## 6. 常见修改指引

| 想改什么 | 改哪里 |
| --- | --- |
| 新增一个设置项 | `MainActivity.showSettings()` 加控件 → 存 `prefs()` → `loadSettings()` 读回 → `updateSummary()` 展示 → 用到的逻辑里读字段 |
| 改提醒选项列表 | `MainActivity.REMIND_MINUTES / REMIND_LABELS`（两数组长度必须一致） |
| 改事件标题/备注格式 | `parse/Occurrence.title() / description()` |
| 支持 `.xls` 旧格式 | 需引入解析库或用外部转换；`XlsxReader` 只认 xlsx（zip） |
| 支持更多表头写法 | `TimetableParser.classify()` 的关键词表 |
| 支持调休/节假日 | 在 `TimetableParser` 生成 `Occurrence` 后加一层日期修正表 |
| 换包名 | `app/AndroidManifest.xml` 的 `package` + 两处硬编码 `com.ttcal.importer`（`Occurrence.APP_PACKAGE`、`CalendarRepo` 选择条件） + 目录名 |
| 加语言 | 目前文案是 Java 字符串常量，国际化需先抽到 `res/values/strings.xml` 并改用 `R.string` |
| 加自动构建 | 用 GitHub Actions：runner 上 `sdkmanager "platforms;android-36" "build-tools;36.0.0"`，再调 `build_apk.py` |

## 7. 测试与回归

```bash
python tools/verify_parse.py     # 解析一致性：Java ⟷ 独立 Python 实现逐条比对 + 周次/时间/星期单测
python tools/test_fixtures.py    # 鲁棒性：坏行、周次变体、地点随周变化、网课、标题行
python build/build_apk.py        # 打包自检（见第 4 节）
```

`verify_parse.py` 需要一个或多个课表 xlsx 作为输入（**自行准备，不要提交到仓库**），
不传参时使用脚本里的默认路径；也可以 `python tools/verify_parse.py 你的课表.xlsx`。

改动解析规则后，以上两项必须全部 PASS，否则视为未完成。

## 8. 已知限制

- 只做「按日期」过滤，不做「按当天时段」过滤：当天已结束的课也会写入。
- 不支持调休/节假日偏移、跨零点课程、`RRULE` 周期事件（每节课都是独立事件）。
- 只支持 `.xlsx`；`.xls` 需先另存为 xlsx。
- 日历选择器已从主界面移除（设置在设置面板里）；`currentCalendar()` 只返回持久化的那个日历。
- 仅 vivo OriginOS 7 实测；其他 ROM 的日历 Provider 行为（尤其 `CUSTOM_APP_*` 字段是否保留）未验证。

## 9. 后续可做（按价值排序）

1. 在更多 ROM / Android 版本上回归，特别是「清除」是否按预期删除（`CUSTOM_APP_PACKAGE` 是否被保留）。
2. 「跳过今天已结束的时段」开关。
3. 导入前冲突列表可展开查看，支持单条跳过。
4. 把文案抽到 `strings.xml` 做国际化。
5. GitHub Actions 自动出包 + 自动挂 Release 附件。

## 10. 发版流程

1. 改两处版本号（见第 4 节）→ 构建 → 本地安装验证。
2. 提交：`git add -A && git commit -m "feat: ..."` → `git push`。
3. GitHub → Releases → Draft a new release：tag 用 `v<版本>`，标题写要点，
   把 `dist/*.apk` 拖入附件。
   - 注意：**附件名里的非 ASCII 字符会被 GitHub 过滤**（中文名会变成 `-v1.7.apk`），
     建议直接用 ASCII 名，例如 `timetable-importer-v1.7.apk`。
4. 更新 README 的版本说明（如涉及）与本文第 8 节。
