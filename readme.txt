Projeto 1 - Segurança e Confiabilidade

1) Compilação
- Requisitos: JDK 21.0.7 (OpenJDK), bash, `javac`, `jar`.
- Comando: `./build.sh`
- Saída: `build/SpertaServer.jar` e `build/SpertaClient.jar`.

2) Execução
- Servidor:
  `./SpertaServer.sh [<port>] <password-cifra> <keystore> <password-keystore>`
  - Se `<port>` não for indicado, usa 22345.
- Cliente:
  `./SpertaClient.sh <serverAddress> <truststore> <password-truststore> <keystore> <password-keystore> <user-id> <password>`
  - `serverAddress` no formato `<IP/hostname>[:port]`.
  - Se `:port` não for indicado, usa 22345.

3) Estrutura de ficheiros
- `src/` — código-fonte Java.
- `build/` — jars executáveis.
- `SpertaServer.sh` e `SpertaClient.sh` — scripts de arranque.
- `server/` (criado em runtime):
  - `server/apps.txt` — app de referência para atestação (`SpertaClient:<path_do_jar>`).
  - `server/users.txt` — utilizadores (`user:hash:base64salt`).
  - `server/certs/<user>.cert` — certificados recebidos no primeiro login.
  - `server/homes.txt` — casas, owner e permissões.
  - `server/devices.txt` — último estado conhecido de cada dispositivo.
  - `server/<home>_<device>.csv` — histórico cronológico do dispositivo.

4) Limitações conhecidas
- Atualmente é possível haver duas sessões simultâneas do mesmo utilizador.

5) Ambiente usado no desenvolvimento/testes
- Java 21.0.7 (OpenJDK).
- Plataforma: Linux (Ubuntu 22.04) nas máquinas pessoais.

6) Grupo 20
- Alice Correia fc61844
- Francisco Lima fc61864
- Marta Rufino fc61778
