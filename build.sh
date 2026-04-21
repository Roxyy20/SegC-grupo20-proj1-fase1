if [ ! -d build ]
then
  mkdir build
fi
rm -f build/*.class build/*.jar
javac -d build src/*.java
cd build
jar -cfe SpertaClient.jar SpertaClient *.class
jar -cfe SpertaServer.jar SpertaServer *.class
