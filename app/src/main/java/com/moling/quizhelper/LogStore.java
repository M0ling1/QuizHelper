package com.moling.quizhelper;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 问答日志，一行一条 JSON（jsonl），存在 filesDir/logs.jsonl。 */
public final class LogStore {

    private static final String FILE = "logs.jsonl";
    private static final int MAX = 300;

    private LogStore() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    public static synchronized void add(Context c, LogRecord r) {
        try {
            List<LogRecord> all = load(c);
            all.add(0, r);
            while (all.size() > MAX) all.remove(all.size() - 1);
            FileOutputStream fos = new FileOutputStream(file(c), false);
            StringBuilder sb = new StringBuilder();
            for (LogRecord x : all) sb.append(x.toJson().toString()).append('\n');
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Throwable ignored) {
        }
    }

    public static synchronized List<LogRecord> load(Context c) {
        List<LogRecord> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                LogRecord r = LogRecord.fromJson(line);
                if (r != null) out.add(r);
            }
            br.close();
        } catch (Throwable ignored) {
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    public static synchronized void clear(Context c) {
        try {
            file(c).delete();
        } catch (Throwable ignored) {
        }
    }
}
