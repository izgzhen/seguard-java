all: quick


.phony: init quick test test-resource check

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

quick:
	mvn -q clean compile assembly:single -o

test: $(TEST_JAVA_CLASSES) quick
	mvn -q test

clean:
	rm -r target
	rm -r src/test/resources/*.class

javadoc:
	mvn javadoc:javadoc
