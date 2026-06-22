---
name: swagger-doc-creator
description: "Use this skill whenever the user wants to create, generate, or write an OpenAPI/Swagger specification or documentation file. Triggers include: any mention of 'swagger', 'openapi', 'API spec', 'API documentation', 'API schema', '.yaml spec', '.json spec', or requests to document REST/GraphQL endpoints. Also use when converting existing API code (Express, FastAPI, Flask, Django, Spring, etc.) into OpenAPI specs, generating endpoint docs from route definitions, adding authentication schemes, or producing interactive API docs. If the user asks to 'document my API', 'create a spec', 'generate swagger docs', or 'write an openapi file', use this skill. Even if the user just pastes route handlers or controller code and asks for documentation, use this skill. Do NOT use for general YAML/JSON editing unrelated to APIs, or for building API servers themselves."
---

# Swagger / OpenAPI Specification Creator

## Overview

Generate production-ready OpenAPI 3.0+ specification files (YAML or JSON) from user descriptions, existing code, or partial specs. Output is a valid, well-structured spec that can be loaded into Swagger UI, Redoc, Postman, or any OpenAPI-compatible tool.

## Quick Reference

| Task | Approach |
|------|----------|
| Create spec from scratch | Ask for resources/endpoints, generate full OpenAPI 3.0.x YAML |
| Extract spec from code | Parse route handlers, models, and decorators → generate spec |
| Add auth to existing spec | Add `securitySchemes` + `security` at operation/global level |
| Validate spec | Use `swagger-cli` or `@apidevtools/swagger-parser` |
| Convert between formats | YAML ↔ JSON, OpenAPI 2.0 (Swagger) → 3.0 migration |

---

## Workflow

### Step 1: Gather API information

Before writing anything, establish:

1. **Base info** — API title, version, description, server URLs (prod, staging, dev)
2. **Endpoints** — HTTP method + path for each operation (e.g., `GET /users/{id}`)
3. **Request/response shapes** — body schemas, query params, path params, headers
4. **Auth** — Bearer JWT, API key, OAuth2, basic auth, or none
5. **Error responses** — standard error envelope (4xx/5xx shapes)

If the user provides source code (route handlers, controllers, models), extract this information directly. If they provide a description, ask only for what's missing — don't over-interview.

### Step 2: Generate the spec

Default to **OpenAPI 3.0.3** in **YAML** unless the user requests otherwise.

#### Spec structure

```yaml
openapi: 3.0.3
info:
  title: API Title
  version: 1.0.0
  description: |
    Brief description of the API.
  contact:
    name: API Support
    email: support@example.com

servers:
  - url: https://api.example.com/v1
    description: Production

paths:
  /resource:
    get:
      summary: Short summary
      description: Longer description if needed
      operationId: getResource
      tags:
        - Resources
      parameters: []
      responses:
        '200':
          description: Successful response
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Resource'
        '401':
          $ref: '#/components/responses/Unauthorized'
        '404':
          $ref: '#/components/responses/NotFound'

components:
  schemas: {}
  responses: {}
  securitySchemes: {}
```

#### Conventions to follow

- **Always use `$ref` for reusable schemas** — define them under `components/schemas`, never inline complex objects in path items.
- **Use `operationId`** on every operation — camelCase, unique across the spec (e.g., `listUsers`, `getUserById`, `createUser`).
- **Group with `tags`** — one primary tag per operation, matching the resource name.
- **Include `summary` AND `description`** — summary is a short label (< 10 words), description explains behavior.
- **Document all response codes** — at minimum: success (200/201/204), 400, 401, 404, 500. Use `$ref` to shared response components for standard errors.
- **Use `example` or `examples`** — add realistic example values to schemas and parameters so generated docs are immediately useful.
- **Path parameters use `{param}` syntax** and must have a matching `parameters` entry with `in: path` and `required: true`.
- **Enum values** — use `enum` for fields with a fixed set of allowed values.

#### Authentication schemes

```yaml
# Bearer JWT
components:
  securitySchemes:
    BearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT

# API Key (header)
    ApiKeyAuth:
      type: apiKey
      in: header
      name: X-API-Key

# OAuth2 with scopes
    OAuth2:
      type: oauth2
      flows:
        authorizationCode:
          authorizationUrl: https://auth.example.com/authorize
          tokenUrl: https://auth.example.com/token
          scopes:
            read: Read access
            write: Write access

# Apply globally
security:
  - BearerAuth: []

# Or per-operation (overrides global)
paths:
  /public:
    get:
      security: []  # No auth required
```

#### Pagination pattern

```yaml
parameters:
  - name: page
    in: query
    schema:
      type: integer
      minimum: 1
      default: 1
  - name: per_page
    in: query
    schema:
      type: integer
      minimum: 1
      maximum: 100
      default: 20

# Response wrapper
PaginatedResponse:
  type: object
  properties:
    data:
      type: array
      items:
        $ref: '#/components/schemas/Resource'
    meta:
      type: object
      properties:
        total:
          type: integer
        page:
          type: integer
        per_page:
          type: integer
        total_pages:
          type: integer
```

#### Standard error response

```yaml
components:
  schemas:
    Error:
      type: object
      required:
        - code
        - message
      properties:
        code:
          type: integer
        message:
          type: string
        details:
          type: array
          items:
            type: object
            properties:
              field:
                type: string
              issue:
                type: string

  responses:
    BadRequest:
      description: Invalid request parameters
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/Error'
          example:
            code: 400
            message: Validation failed
            details:
              - field: email
                issue: Must be a valid email address
    Unauthorized:
      description: Missing or invalid authentication
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/Error'
          example:
            code: 401
            message: Invalid or expired token
    NotFound:
      description: Resource not found
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/Error'
          example:
            code: 404
            message: Resource not found
    InternalError:
      description: Unexpected server error
      content:
        application/json:
          schema:
            $ref: '#/components/schemas/Error'
          example:
            code: 500
            message: Internal server error
```

#### CRUD resource pattern

For a typical resource (e.g., `User`), generate all five operations:

| Method | Path | operationId | Success Code |
|--------|------|-------------|--------------|
| GET | /users | listUsers | 200 |
| POST | /users | createUser | 201 |
| GET | /users/{id} | getUserById | 200 |
| PUT | /users/{id} | updateUser | 200 |
| DELETE | /users/{id} | deleteUser | 204 |

### Step 3: Validate the spec

After generating the YAML/JSON file, validate it:

```bash
npm install -g @apidevtools/swagger-cli
swagger-cli validate spec.yaml
```

If validation fails, fix the issues and re-validate. Common problems:
- Missing `required: true` on path parameters
- `$ref` pointing to undefined component
- Duplicate `operationId` values
- Invalid `type` values (e.g., `date` instead of `string` with `format: date`)

### Step 4: Deliver the file

Save the spec as `openapi.yaml` (or `openapi.json` if requested) to `/mnt/user-data/outputs/` and present it to the user.

---

## Extracting specs from code

When the user provides source code, map framework patterns to OpenAPI:

| Framework | Route pattern | Extract from |
|-----------|--------------|--------------|
| Express.js | `router.get('/path', handler)` | Route files, middleware, Joi/Zod schemas |
| FastAPI | `@app.get("/path")` | Decorators, Pydantic models, type hints |
| Flask | `@app.route("/path", methods=["GET"])` | Decorators, docstrings, marshmallow schemas |
| Django REST | `class ViewSet(ModelViewSet)` | Serializers, URL conf, viewsets |
| Spring Boot | `@GetMapping("/path")` | Annotations, DTOs, controller methods |
| Go (Gin/Echo) | `r.GET("/path", handler)` | Struct tags, handler signatures |

For each route found:
1. Extract the HTTP method and path
2. Identify path/query/body parameters from function signatures or validation schemas
3. Infer response schemas from return types, serializers, or response calls
4. Note any auth middleware or decorators
5. Generate the corresponding OpenAPI path item

---

## OpenAPI version differences

| Feature | OpenAPI 2.0 (Swagger) | OpenAPI 3.0+ |
|---------|----------------------|--------------|
| Request body | `parameters` with `in: body` | `requestBody` object |
| File upload | `type: file` | `multipart/form-data` with binary format |
| Multiple content types | `consumes`/`produces` | `content` with media type keys |
| Auth | `securityDefinitions` | `components/securitySchemes` |
| Reusable components | `definitions` | `components/schemas` |
| Server URLs | `host` + `basePath` | `servers` array |

Default to 3.0.3. If the user has an existing 2.0 spec, offer to migrate it.

---

## Critical rules

- **Always validate** — never deliver an unvalidated spec.
- **Use `$ref` aggressively** — inline schemas create duplication and make the spec harder to maintain. Any object used more than once belongs in `components/schemas`.
- **`required: true` on all path params** — OpenAPI mandates this; omitting it is a spec violation.
- **`type: string` with `format`** for dates, emails, UUIDs — never use `type: date` or `type: email` (these don't exist in OpenAPI).
- **No trailing slashes** in paths — `/users` not `/users/`.
- **Consistent plural nouns** for collection paths — `/users`, `/orders`, not `/user`, `/order`.
- **operationId must be unique** across the entire spec.
- **YAML indentation** — 2 spaces, no tabs.
- **Include realistic examples** — generated docs are much more useful with concrete sample data.

---

## Dependencies

- **@apidevtools/swagger-cli**: `npm install -g @apidevtools/swagger-cli` (validation)
- **js-yaml** (optional): `npm install -g js-yaml` (YAML ↔ JSON conversion)