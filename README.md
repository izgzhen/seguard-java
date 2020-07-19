# SeGuard Java Library

[![Build Status](https://travis-ci.com/semantic-graph/seguard-java.svg?branch=master)](https://travis-ci.com/semantic-graph/seguard-java)

Extract approximate dependency graph from Java/Android/JavaScript.

## Usage

Analyze Java:

    make init
    make
    java -Djava.io.tmpdir=/tmp -Xmx4096m -jar target/seguard-1.0-SNAPSHOT-jar-with-dependencies.jar \
        -apk path/to/input.apk -android $ANDROID_SDK/platforms -outputPath path/to/output.gexf \
        -mode core -sourceSinkFile config/SourcesAndSinks.txt -apkclasses /tmp/classes.txt \
        -config path/to/config.yaml \
        -java /tmo/output.jar.out

Analyze JS (see `src/test/resources/config.yaml` for example config file):

    ./seguardjs-cli path/to/filename.js path/to/output.js.gexf path/to/config.yaml

## Test

Test Java analysis:

```bash
make test-js-core
```

Test JS analysis:

```
make test-java-core
```
