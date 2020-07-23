GITHASH := $(shell git rev-parse --short=8 --verify HEAD)

JAR := target/seguard-1.0-SNAPSHOT-jar-with-dependencies.jar
GITHASH_FILE := $(JAR).gitver

jar: $(JAR) update-gitver

all: jar

.phony: init test test-resource check jar

check:
	./check-jdk-version

src/test/resources/%.class: src/test/resources/%.java
	mkdir -p src/test/resources/
	javac $< -d src/test/resources/

TEST_JAVA_FILES := $(wildcard src/test/resources/*.java)
TEST_JAVA_CLASSES := $(patsubst src/test/resources/%.java,src/test/resources/%.class,$(TEST_JAVA_FILES))

init: check $(TEST_JAVA_CLASSES)
	cd lib; bash install.sh
	mvn -q package
	mvn -q clean compile assembly:single

SRC_FILES := $(shell find src/ -type f -name '*.scala') $(shell find src/ -type f -name '*.java') pom.xml

update-gitver:
	echo $(GITHASH) > $(GITHASH_FILE)

# -B for batch mode
$(JAR): $(SRC_FILES)
	mvn -q -B compile assembly:single -o

regtest-not-record:
	grep 'private val record = false' src/test/scala/edu/washington/cs/seguard/JsTest.scala

test: test-js-core test-java-core
	mvn -q test

test-js-core: regtest-not-record jar
	timeout 300 ./seguardjs-cli tests/tmpoc4jukbq.js tests/tmpoc4jukbq.js.gexf src/test/resources/config.yaml

test-java-core: $(TEST_JAVA_CLASSES) jar
	timeout 300 java -Djava.io.tmpdir=/tmp -Xmx4096m -jar $(JAR) -apk tests/example.apk -android $(ANDROID_SDK)/platforms -outputPath /tmp/output.gexf \
            -mode core -sourceSinkFile config/SourcesAndSinks.txt -apkclasses /tmp/classes.txt -config src/test/resources/config.yaml \
            -java /tmo/output.jar.out

clean:
	rm -r target
	rm -r src/test/resources/*.class

javadoc:
	mvn javadoc:javadoc
