if [ ! -d build ]
then
  mkdir build
fi
javac -d build src/*.java
cd build
jar -cfe SpertaClient.jar SpertaClient SpertaClient.class 'SpertaClient$1.class' Command.class LoginMessage.class ServerResponse.class SecurityUtils.class CryptoUtils.class Section.class
jar -cfe SpertaServer.jar SpertaServer SpertaServer.class ServerThread.class 'ServerThread$1.class' ServerResponse.class Section.class LoginMessage.class Home.class CommandHandler.class Command.class SecurityUtils.class FileIntegraty.class
