package edu.washington.cs.seguard;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.jimple.infoflow.android.source.parsers.xml.XMLSourceSinkParser;
import soot.jimple.infoflow.sourcesSinks.definitions.SourceSinkDefinition;

class SourceSinkManager {
    Set<String> sources;
    Set<String> sinks;
    private Logger logger = LoggerFactory.getLogger(SourceSinkManager.class);

    SourceSinkManager(String filepath) throws IOException {
        sources = new HashSet<>();
        sinks = new HashSet<>();
        if (filepath.endsWith(".txt")) {
            BufferedReader br = new BufferedReader(new FileReader(filepath));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().startsWith("%")) {
                    // comment
                    continue;
                }
                if (line.trim().length() == 0) {
                    continue;
                }
                if (line.contains(" -> ")) {
                    String[] segs = line.trim().split("\\)>");
                    String sig = segs[0].trim() + ")>";
                    if (segs[1].contains("_SOURCE_")) {
                        sources.add(sig);
                    } else if (segs[1].contains("_SINK_")) {
                        sinks.add(sig);
                    } else if (segs[1].contains("_BOTH_")) {
                        sources.add(sig);
                        sinks.add(sig);
                    } else {
                        br.close();
                        throw new RuntimeException("Unknown segs[1]: " + segs[1]);
                    }
                }
            }
            br.close();
        } else if (filepath.endsWith(".xml")) {
            XMLSourceSinkParser parser = XMLSourceSinkParser.fromFile(filepath);
            for (SourceSinkDefinition source : parser.getSources()) {
                sources.add(source.toString());
            }
            for (SourceSinkDefinition sink: parser.getSinks()) {
                sinks.add(sink.toString());
            }
            logger.info("sources: {}", sources);
            logger.info("sinks:  {}", sinks);
        } else {
            throw new RuntimeException("Unsupported file extension: " + filepath);
        }
    }
}
