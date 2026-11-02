# Feature Flags Service

API Spring Boot para habilitar/desabilitar flags em memória. `GET /api/flags/{nome}` retorna estado (padrão: false); `PUT /api/flags/{nome}` exige o cabeçalho `X-Admin-Token` com variável de ambiente forte.

## Estado

**MVP funcional / projeto inicial de portfólio.** Implementa o núcleo descrito abaixo, mas não é produto pronto para produção.

## Como executar

```bash
export FEATURE_FLAGS_ADMIN_TOKEN="$(python -c 'import secrets;print(secrets.token_urlsafe(40))')"
mvn spring-boot:run
```

## Testes

```bash
mvn -B test
```

O GitHub Actions executa os testes automaticamente em pushes e pull requests.

## Segurança e limitações

Servidor escuta apenas em `127.0.0.1`. Token de administrador com mínimo de 32 caracteres; sem ele, alterações retornam 503. Flags não persistem entre reinícios, não há autorização multiusuário ou Redis.

## Evolução planejada

Persistir flags, suportar porcentagem de rollout e clientes SDK com auditoria.

---
Projeto de Engenharia de Software mantido na conta [juniordev830](https://github.com/juniordev830).
