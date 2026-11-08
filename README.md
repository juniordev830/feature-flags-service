# Feature Flags Service

API Spring Boot para habilitar e desabilitar feature flags em memória. O padrão para flags desconhecidas é **false**.

## Estado

**MVP funcional / projeto inicial de portfólio.** Não é um produto pronto para produção.

## Como executar

```bash
export FEATURE_FLAGS_ADMIN_TOKEN="$(python -c 'import secrets;print(secrets.token_urlsafe(40))')"
mvn spring-boot:run
```

Por padrão, o serviço escuta somente em `127.0.0.1:8080`.

## Endpoints

| Método | Rota | Autenticação | Comportamento |
| --- | --- | --- | --- |
| GET | `/api/flags/{nome}` | Não | Lê uma flag; flags inexistentes retornam `enabled: false`. |
| GET | `/api/flags` | `X-Admin-Token` | Lista somente flags explicitamente configuradas, ordenadas por nome. |
| PUT | `/api/flags/{nome}` | `X-Admin-Token` | Define `{"enabled": true/false}`. |
| DELETE | `/api/flags/{nome}` | `X-Admin-Token` | Remove a configuração; a flag volta ao padrão `false`. Retorna HTTP 204. |
| PUT | `/api/flags/{nome}/rollout` | `X-Admin-Token` | Define `{"percentage": 0..100}` para uma flag já criada. |
| GET | `/api/flags/{nome}/rollout` | `X-Admin-Token` | Lê o percentual configurado (padrão 100). |
| GET | `/api/flags/{nome}/evaluate?subject=...` | Não | Avalia se a flag está habilitada para um identificador estável de sujeito (1 a 256 caracteres). |

As rotas administrativas exigem token de pelo menos 32 caracteres, definido em `FEATURE_FLAGS_ADMIN_TOKEN`. Sem configuração válida, retornam HTTP 503; token ausente ou incorreto retorna HTTP 401.

Exemplo de uso:

```bash
curl -s http://127.0.0.1:8080/api/flags/minha.flag
curl -s -X PUT http://127.0.0.1:8080/api/flags/minha.flag \
  -H "X-Admin-Token: $FEATURE_FLAGS_ADMIN_TOKEN" \
  -H "Content-Type: application/json" -d '{"enabled":true}'
curl -s http://127.0.0.1:8080/api/flags \
  -H "X-Admin-Token: $FEATURE_FLAGS_ADMIN_TOKEN"
curl -i -X DELETE http://127.0.0.1:8080/api/flags/minha.flag \
  -H "X-Admin-Token: $FEATURE_FLAGS_ADMIN_TOKEN"
```

## Rollout gradual e determinístico

Depois de criar a flag com `PUT /api/flags/{nome}`, configure uma liberação parcial:

```bash
curl -s -X PUT http://127.0.0.1:8080/api/flags/minha.flag/rollout \\
  -H "X-Admin-Token: $FEATURE_FLAGS_ADMIN_TOKEN" \\
  -H "Content-Type: application/json" -d '{"percentage":25}'
curl -s 'http://127.0.0.1:8080/api/flags/minha.flag/evaluate?subject=cliente-123'
```

O mesmo par `nome + subject` sempre cai no mesmo grupo por hash SHA-256. `percentage: 0` desliga o acesso de todos; `100` inclui todos **se** a flag estiver ligada. O booleano principal é um *kill switch*: quando `enabled: false`, a avaliação sempre é falsa. `GET /api/flags/{nome}` continua retornando apenas esse booleano principal, sem calcular o percentual. Os métodos `PUT` e `POST /bulk` preservam um percentual já configurado. `DELETE` descarta também o percentual da flag.

Este algoritmo é para distribuição gradual de funcionalidades, **não** para autenticação, autorização, dados sensíveis ou autorização de pagamentos. O campo `subject` deve ser um identificador pseudônimo e estável, não CPF, e-mail ou outro dado pessoal. A leitura da avaliação não registra identificadores e o serviço permanece restrito a `localhost`.

## Testes

```bash
mvn -B test
```

O GitHub Actions executa testes em pushes e pull requests, incluindo casos de autenticação, inventário e exclusão de flags.

## Segurança e limitações

O token administrativo não deve ser versionado nem colocado em logs. Apesar de o serviço estar restrito a localhost, **o endpoint público de leitura não deve ser exposto na internet** sem autenticação e TLS adequados. As flags são armazenadas somente em memória, não há auditoria de alterações, autorização multiusuário nem persistência; dados são perdidos ao reiniciar. O endpoint de listagem entrega um snapshot aproximado sob alterações concorrentes.

## Evolução planejada

Adicionar banco de dados, histórico de auditoria, clientes SDK e autenticação por usuário.

---

Projeto de Engenharia de Software mantido na conta [juniordev830](https://github.com/juniordev830).


## Backup e promoção de configurações

O serviço oferece snapshots versionados (versão 1) para transportar configurações
entre ambientes, mantendo o controle de acesso administrativo.

| Método | Rota | Comportamento |
| --- | --- | --- |
| GET | `/api/flags/snapshot` | Exporta as flags explicitamente configuradas, incluindo `enabled` e `rolloutPercentage`. |
| POST | `/api/flags/snapshot/validate` | Valida o snapshot e lista as flags que seriam atualizadas, sem gravar. |
| POST | `/api/flags/snapshot/import` | Mescla as configurações validadas com as existentes. Não remove flags omitidas. |

Todas as três rotas exigem `X-Admin-Token`. O formato é:

```json
{
  "version": 1,
  "flags": {
    "checkout.v2": {"enabled": true, "rolloutPercentage": 25},
    "search.experiment": {"enabled": false, "rolloutPercentage": 100}
  }
}
```

Fluxo recomendado: exporte a configuração de origem, revise o arquivo,
envie-o primeiro para `/snapshot/validate` e somente depois para
`/snapshot/import`. Um snapshot inválido é recusado integralmente,
sem modificar flags. A importação aceita entre 1 e 500 flags; nomes
devem obedecer ao padrão da API, e percentuais precisam estar entre 0 e 100.

**Importante:** a importação é um *merge*, não uma restauração destrutiva.
Ela valida todos os itens antes de escrever, mas alterações administrativas
simultâneas não possuem isolamento transacional entre flags. A exportação
reflete uma leitura do estado em memória, que é volátil e reinicia com o processo.
Não use feature flags para decisões de autorização ou controle de acesso.

O endpoint `POST /api/flags/bulk` aceita de 1 a 100 alterações por requisição,
para limitar a carga acidental.


## Avaliação de coortes em matriz

`POST /api/flags/evaluate/matrix` avalia várias flags para vários
identificadores de sujeitos numa única chamada, sem revelar o inventário
completo de configurações. Não requer token administrativo, assim como a
avaliação pública individual.

```json
{
  "subjects": ["customer-1", "customer-2"],
  "names": ["checkout.v2", "search.experiment"]
}
```

A resposta é um objeto com os identificadores dos sujeitos como chaves,
cada um contendo o mapa de flags solicitadas e decisões booleanas.
Flags desconhecidas retornam `false`. Os resultados são determinísticos
para o mesmo par de flag/sujeito e configuração.

A API rejeita sujeitos ou nomes repetidos, identificadores vazios e nomes
inválidos. Limites: 50 sujeitos, 100 flags e 1.000 decisões por requisição.
Não envie dados pessoais ou segredos como identificadores de sujeitos.


## Histórico administrativo de alterações

O serviço mantém um **histórico de auditoria volátil**, em memória, das
alterações administrativas bem-sucedidas. As operações registradas são
`update`, `delete`, `bulk-update`, `rollout-update` e
`snapshot-import`. Leituras, avaliações públicas, validações prévias
e requisições rejeitadas não geram eventos.

| Método | Rota | Autenticação | Descrição |
| --- | --- | --- | --- |
| GET | `/api/flags/audit?after=0&limit=50` | `X-Admin-Token` | Consulta eventos por cursor crescente. |
| GET | `/api/flags/audit/{id}` | `X-Admin-Token` | Consulta um evento retido pelo identificador. |

Exemplo de consulta:

```bash
curl -s 'http://127.0.0.1:8080/api/flags/audit?after=0&limit=20' \
  -H "X-Admin-Token: $FEATURE_FLAGS_ADMIN_TOKEN"
```

Cada evento contém `id`, `occurredAt` (UTC), `operation`,
`affected` (número de flags distintas) e `sampleNames` (até 12 nomes,
ordenados). A página retorna `events`, `nextAfter`, `hasMore` e
`oldestAvailable`. Para continuar a leitura, envie `nextAfter` como
`after`. O parâmetro `limit` aceita de 1 a 100; `after` deve ser
um inteiro não negativo. O campo `oldestAvailable` permite detectar
lacunas quando eventos antigos já foram descartados.

**Privacidade e limites:** o histórico nunca armazena token administrativo,
identificador de sujeito, percentual, payload ou valor da flag. Os nomes
das flags são visíveis somente ao administrador e não devem conter dados
pessoais. Apenas os **256 eventos mais recentes** são mantidos; reiniciar
o processo apaga todo o histórico. Alterações concorrentes não oferecem
ordenação transacional entre a atualização das flags e a gravação do evento.
Esse recurso auxilia a depuração, **não substitui** trilha de auditoria
persistente e imutável exigida em ambientes regulados.
