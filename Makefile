JAR := target/seguard-1.0-SNAPSHOT-jar-with-dependencies.jar

jar: $(JAR)

.phony: init test test-resource check

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

# -B for batch mode
$(JAR): $(SRC_FILES)
	mvn -B compile assembly:single -o

regtest-not-record:
	grep 'private val record = false' src/test/scala/edu/washington/cs/seguard/JsTest.scala

test: regtest-not-record jar $(TEST_JAVA_CLASSES)
	timeout 300 ./seguardjs-cli tests/tmpoc4jukbq.js tests/tmpoc4jukbq.js.gexf src/test/resources/config.yaml

test-js: jar
	./seguardjs-cli src/test/resources/example.js src/test/resources/example.js.gexf src/test/resources/config.yaml

clean:
	rm -r target
	rm -r src/test/resources/*.class

javadoc:
	mvn javadoc:javadoc
