package edu.washington.cs.seguard;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

public class Config {
    @Getter @Setter List<String> sensitiveEntrypointClassKeywords;
    @Getter @Setter List<String> sensitiveMethodNames;
    @Getter @Setter List<String> sensitiveMethodSigs;
    @Getter @Setter List<String> sensitivePackageNameKeywords;
    @Getter @Setter List<String> dataflowClassNames;
    @Getter @Setter List<String> blacklistedPackagePrefixes;
    @Setter List<String> sensitiveConstStringKeywords;

    public List<String> getSensitiveConstStringKeywords() {
        return sensitiveConstStringKeywords;
    }

    @Getter @Setter boolean debug;
    @Getter @Setter String abstractionDumpPath;
    @Getter @Setter String callGraphDumpPath;

    public static Config load(String path) {
        Representer rep = new Representer();
        rep.getPropertyUtils().setSkipMissingProperties(true);
        Yaml yaml = new Yaml(new Constructor(Config.class), rep);
        try {
            InputStream inputStream = new FileInputStream(new File(path));
            return yaml.load(inputStream);
        } catch (Exception e) {
            throw new RuntimeException(e.toString());
        }
    }
}
