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

## Testes

```bash
mvn -B test
```

O GitHub Actions executa testes em pushes e pull requests, incluindo casos de autenticação, inventário e exclusão de flags.

## Segurança e limitações

O token administrativo não deve ser versionado nem colocado em logs. Apesar de o serviço estar restrito a localhost, **o endpoint público de leitura não deve ser exposto na internet** sem autenticação e TLS adequados. As flags são armazenadas somente em memória, não há auditoria de alterações, autorização multiusuário nem persistência; dados são perdidos ao reiniciar. O endpoint de listagem entrega um snapshot aproximado sob alterações concorrentes.

## Evolução planejada

Adicionar banco de dados, histórico de auditoria, rollout por porcentagem, clientes SDK e autenticação por usuário.

---

Projeto de Engenharia de Software mantido na conta [juniordev830](https://github.com/juniordev830).

## Guia para contribuir

Veja o [roteiro de testes e manutenção](docs/GUIA_DE_VALIDACAO.md) para validar mudanças com comandos e cenários específicos deste projeto.
