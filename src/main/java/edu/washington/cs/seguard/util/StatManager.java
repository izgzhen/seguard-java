package edu.washington.cs.seguard.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.val;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class StatManager {
    private Map<StatKey, Double> statMap = new TreeMap<>(Comparator.comparing(Object::toString));

    public void COUNT(StatKey key) {
        val v = statMap.get(key);
        if (v == null) {
            statMap.put(key, 1.0);
        } else {
            statMap.put(key, v + 1);
        }
    }

    public void put(StatKey key, int x) {
        statMap.put(key, (double) x);
    }

    private String toJSON() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(statMap);
    }

    private String statPath;

    public StatManager(String apkPath, String mode) {
        if (apkPath == null) {
            statPath = null;
            return;
        }
        statPath = apkPath + "-" + mode + ".stat.json";
    }

    public void writeToDisk() {
        if (statPath == null) {
            return;
        }
        BufferedWriter bw;
        try {
            bw = new BufferedWriter(new FileWriter(statPath));
            bw.write(toJSON());
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
