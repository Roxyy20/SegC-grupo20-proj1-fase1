Projeto 1 - Segurança e Confiabilidade (Fase 2)

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
  - `<truststore>` é por utilizador: `userCerts/<user-id>.truststore`.
  - Para criar truststore de um utilizador: `keytool -importcert -alias server -file server.cer -keystore userCerts/<user-id>.truststore -storetype PKCS12 -storepass <password-truststore> -noprompt`.

3) Estrutura de ficheiros
- `src/` — código-fonte Java.
- `build/` — jars executáveis.
- `SpertaServer.sh` e `SpertaClient.sh` — scripts de arranque.
- `server/` (criado em runtime):
  - `server/apps.txt` — app de referência para atestação (`SpertaClient:<path_do_jar>`).
  - `server/users.txt` — utilizadores (`user:hash:base64salt`).
  - `server/homes.txt` — casas, owner e permissões.
  - `server/devices.txt` — último estado conhecido de cada dispositivo.
  - `server/<home>_<device>.csv` — histórico cronológico do dispositivo.
  - `server/certs/<user>.cert` — certificados recebidos no primeiro login.
  - `server/sectionKeys/key.<hm>.<s>.<user>` — chave de secção cifrada por utilizador.
  - `server/server.salt` — salt persistente para derivação da chave PBE.

4) Integridade no servidor
- Existe um `<file>.hash` para os ficheiros de dados do servidor.
- Em falha de integridade, o servidor termina com `NOK-INTEGRITY`.

5) Entrega (zip final)
- Nome do ficheiro: `SegC-grupoXX-proj1-fase2.zip` (XX = número do grupo).
- O zip deve incluir:
  - Código-fonte completo.
  - Keystores, truststores, certificados (`.cert`) e chaves necessárias para execução.
  - `build/SpertaServer.jar` e `build/SpertaClient.jar`.
  - Este `readme.txt`.
  - Um ficheiro de passwords (ex.: `passwords.txt`) com:
    - password da keystore do servidor;
    - passwords das truststores dos clientes;
    - passwords das keystores dos utilizadores;
    - password-cifra usada no arranque do servidor;
    - utilizadores/passwords de login necessários para demonstração.

6) Limitações conhecidas
- Atualmente é possível haver duas sessões simultâneas do mesmo utilizador.

7) Ambiente usado no desenvolvimento/testes
- Java 21.0.7 (OpenJDK).
- Plataforma: Linux (Ubuntu 22.04) nas máquinas pessoais.

8) Grupo 20
- Alice Correia fc61844
- Francisco Lima fc61864
- Marta Rufino fc61778
