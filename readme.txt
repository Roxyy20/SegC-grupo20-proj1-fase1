Projeto 1 - Segurança e Confiabilidade

1) Compilação
- Requisitos: JDK 21.0.7 (OpenJDK), bash, `javac`, `jar`.
- Comando: `./build.sh`
- Saída: `build/SpertaServer.jar` e `build/SpertaClient.jar` ficam prontos para execução.

2) Execução
- Servidor: `./SpertaServer.sh [<port>]` (porta por defeito: 22345).
- Cliente: `./SpertaClient.sh <server_address>[:<port>] <user-id> <password>`.

3) Estrutura/organização dos ficheiros
- `src/` — código-fonte Java.
- `build/` — diretório gerado pela compilação com os jars executáveis.
- `SpertaServer.sh` e `SpertaClient.sh` — scripts de arranque dos jars.
- `server/` (criado em runtime pelo servidor) — dados persistidos:
  - `server/apps.txt` — tamanho esperado do binário do cliente para atestação.
  - `server/users.txt` — utilizadores e passwords (`user:pass`).
  - `server/homes.txt` — casas, owners e permissões por secção.
  - `server/devices.txt` — último estado conhecido de cada dispositivo.
  - `server/<home>_<device>.csv` — log cronológico de estados por dispositivo.
- `README.md` — instruções curtas já presentes no repositório.

4) Limitações conhecidas
- Permitimos que dois usuários iguais estejam logados ao mesmo tempo.
- Não é possível correr nas máquinas da FCUL, uma vez que diz que não tem permissões sobre os ficheiros.

5) Ambiente usado no desenvolvimento/testes
- Java 21.0.7 (OpenJDK).
- Plataforma: Linux (Ubuntu 22.04) rm máquinas pessoais.
- Sem dependências externas além do JDK padrão.

6) Grupo 20:
- Alice Correia fc61844
- Francisco Lima fc61864
- Marta Rufino fc61778