# Wiki4AI

Wiki systém pre AI agentov a vývojára. Umožňuje vytvárať projekty/topiky, ukladať markdown dokumenty, prepájať ich medzi sebou a vizualizovať prepojenia cez webové UI.

## Features

- **Projekty/Topiky** – Vytvárajte a spravujte viaceré wiki projekty
- **Markdown dokumenty** – Ukladajte a editujte dokumenty v markdown formáte
- **Wiki odkazy** – Prepájajte dokumenty pomocou `[[Dokument]]` syntaxe
- **Graf prepojení** – Vizualizácia vzťahov medzi dokumentmi ako interaktívny graf
- **REST API** – Kompletné API pre programatickú prácu s wiki
- **Web UI** – Moderné rozhranie postavené na React + TypeScript

## Tech Stack

### Backend
| Technológia | Verzia |
|---|---|
| Java | 17+ |
| Spring Boot | 3.2.x |
| Spring Data JPA | — |
| PostgreSQL / H2 | 16 / 2.x |
| SpringDoc OpenAPI (Swagger) | 2.5.x |
| Flexmark (Markdown parser) | 0.64.x |
| Lombok | — |

### Frontend
| Technológia | Verzia |
|---|---|
| React | 18 |
| TypeScript | 5.x |
| Vite | 5.x |
| Tailwind CSS | 3.x |
| React Router | 6.x |
| React Query (TanStack) | 5.x |
| react-force-graph | — |

### Infrastructure
- Docker & Docker Compose
- Maven (backend build)
- npm (frontend build)

## Getting Started

### Prerequisites

- **Java 17+** – pre backend
- **Node.js 18+** a **npm** – pre frontend
- **Docker & Docker Compose** – pre kontajnerizovaný deployment

---

### Option A: Docker (celý stack)

Najrýchlejší spôsob – spustí backend, frontend a PostgreSQL naraz:

```bash
docker compose up -d
```

Služby sú dostupné na:
- Frontend: http://localhost
- Backend API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- PostgreSQL: localhost:5432

---

### Option B: Lokálny vývoj

#### Backend

```bash
cd backend
mvn spring-boot:run
```

Backend beží na http://localhost:8080 s H2 in-memory databázou.

H2 Console: http://localhost:8080/h2-console  
JDBC URL: `jdbc:h2:mem:wiki4ai`  
Username: `sa` (heslo prázdne)

#### Frontend

```bash
cd frontend
npm install && npm run dev
```

Frontend beží na http://localhost:5173 s hot reload.

---

### Build pre produkciu

```bash
# Backend JAR
cd backend && mvn clean package -DskipTests

# Frontend static files
cd frontend && npm run build
```

## API Dokumentácia

Swagger UI je dostupné na: **http://localhost:8080/swagger-ui.html**  
OpenAPI JSON: http://localhost:8080/v3/api-docs  
OpenAPI YAML: http://localhost:8080/v3/api-docs.yaml

Podrobné príklady API volaní nájdete v [api-reference.md](./docs/api-reference.md).

## Project Structure

```
wiki4ai/
├── backend/                    # Spring Boot backend
│   ├── src/main/java/com/wiki4ai/
│   │   ├── controller/         # REST controllers
│   │   ├── dto/                # Data transfer objects
│   │   ├── entity/             # JPA entities
│   │   ├── repository/         # Spring Data repositories
│   │   ├── service/            # Business logic services
│   │   └── config/             # Configuration classes
│   ├── src/main/resources/
│   │   ├── application.yml     # Default config (H2)
│   │   └── application-postgres.yml  # PostgreSQL profile
│   └── pom.xml
├── frontend/                   # React + TypeScript frontend
│   ├── src/
│   │   ├── components/         # Reusable UI components
│   │   ├── pages/              # Page components
│   │   ├── services/           # API client services
│   │   └── App.tsx             # Main app component
│   ├── package.json
│   └── vite.config.ts
├── docs/                       # Documentation
│   └── api-reference.md        # API usage examples
├── docker-compose.yml          # Full stack (PostgreSQL)
├── Dockerfile                  # Backend container
└── README.md                   # This file
```

## Development Workflow

Tento projekt používa [Taiga](https://taiga.io) pre project management a GitLab CI/CD pre automatizované testy a deployment. Podrobnosti o workflow nájdete v [WORKFLOW.md](./WORKFLOW.md).

## License

Private project.
